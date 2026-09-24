"""Black-box HTTP contract suite: the same assertions run against ANY
todo-app backend, Go or Python, to prove they expose an identical API.

Copied unchanged (apart from this docstring) from todo-app-py's
api/tests/contract. Skipped unless CONTRACT_BASE_URL is set; run it from a
throwaway Python container, e.g.:

    docker run --rm -v "$PWD/contract:/c" -e CONTRACT_BASE_URL=http://host.docker.internal:8080       python:3.12-slim sh -c "pip install -q pytest==8.3.4 httpx==0.28.1 && pytest -q /c"

Every run registers fresh, randomly-named users, so point it at a throwaway
data directory, never real data.
"""

import os
import secrets
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

BASE_URL = os.environ.get("CONTRACT_BASE_URL", "")
pytestmark = pytest.mark.skipif(not BASE_URL, reason="CONTRACT_BASE_URL not set")

PUBLIC_USER_KEYS = ["id", "email", "is_admin", "failed_login_count", "locked_until", "two_factor_enabled", "created_at"]
ZERO = "0001-01-01T00:00:00Z"


@pytest.fixture(scope="module")
def api():
    with httpx.Client(base_url=BASE_URL, timeout=30, follow_redirects=False) as c:
        yield c


def new_email():
    return f"contract-{secrets.token_hex(6)}@example.com"


def register(api, email=None, password="password123"):
    r = api.post("/api/auth/register", json={"email": email or new_email(), "password": password})
    assert r.status_code == 201, r.text
    body = r.json()
    return body["user"], {"Authorization": "Bearer " + body["token"]}


def err(r):
    return (r.status_code, r.json()["error"]["code"])


def test_register_login_me_logout(api):
    email = new_email()
    r = api.post("/api/auth/register", json={"email": email.upper(), "password": "password123"})
    assert r.status_code == 201 and r.headers["content-type"] == "application/json"
    body = r.json()
    assert list(body) == ["token", "user"]
    assert list(body["user"]) == PUBLIC_USER_KEYS
    assert body["user"]["email"] == email and body["user"]["locked_until"] == ZERO and body["user"]["is_admin"] is False

    assert err(api.post("/api/auth/register", json={"email": email, "password": "password123"})) == (409, "email_taken")
    assert err(api.post("/api/auth/register", json={"email": "bad", "password": "password123"})) == (400, "invalid_email")
    assert err(api.post("/api/auth/register", json={"email": new_email(), "password": "short"})) == (400, "invalid_password")

    r = api.post("/api/auth/login", json={"email": email, "password": "password123"})
    assert r.status_code == 200 and list(r.json()) == ["token", "user"]
    h = {"Authorization": "Bearer " + r.json()["token"]}
    me = api.get("/api/auth/me", headers=h)
    assert me.status_code == 200 and me.json()["email"] == email and list(me.json()) == PUBLIC_USER_KEYS
    r = api.post("/api/auth/logout", headers=h)
    assert r.status_code == 204 and r.content == b""
    assert err(api.post("/api/auth/login", json={"email": email, "password": "wrong-pass"})) == (401, "invalid_credentials")
    assert err(api.post("/api/auth/login", json={"email": new_email(), "password": "password123"})) == (401, "invalid_credentials")


def test_error_envelopes_and_routing(api):
    assert api.get("/api/boards").json() == {"error": {"code": "unauthorized", "message": "missing or invalid token"}}
    assert api.get("/api/boards", headers={"Authorization": "Bearer x.y"}).status_code == 401
    _, h = register(api)
    assert api.get("/api/admin/users", headers=h).json() == {"error": {"code": "forbidden", "message": "admin access required"}}
    for raw in (b"", b"{", b"[]", b'{"name":"x","bogus":1}', b'{"name":5}'):
        r = api.post("/api/boards", content=raw, headers=h)
        assert r.json() == {"error": {"code": "invalid_body", "message": "request body must be valid JSON"}}, raw
    r = api.get("/api/definitely-not-a-route")
    assert r.status_code == 404 and r.text == "404 page not found\n"
    r = api.patch("/api/boards", headers=h)
    assert r.status_code == 405 and r.text == "Method Not Allowed\n"


def test_lockout(api):
    email = new_email()
    register(api, email)
    for _ in range(3):
        assert err(api.post("/api/auth/login", json={"email": email, "password": "wrong-pass"})) == (401, "invalid_credentials")
    assert err(api.post("/api/auth/login", json={"email": email, "password": "password123"})) == (403, "account_locked")


def test_forgot_and_reset_password(api):
    msg = {"message": "if that email exists, a reset link has been sent"}
    email = new_email()
    register(api, email)
    for e in (email, new_email()):
        r = api.post("/api/auth/forgot-password", json={"email": e})
        assert r.status_code == 200 and r.json() == msg
    assert err(api.post("/api/auth/reset-password", json={"token": "bogus", "new_password": "newpassword"})) == (400, "invalid_token")
    assert err(api.post("/api/auth/reset-password", json={"token": "bogus", "new_password": "short"})) == (400, "invalid_password")


def test_two_factor_challenge(api):
    email = new_email()
    user, h = register(api, email)
    r = api.put("/api/auth/2fa", json={"enabled": True}, headers=h)
    assert r.status_code == 200 and r.json()["two_factor_enabled"] is True and list(r.json()) == PUBLIC_USER_KEYS
    r = api.post("/api/auth/login", json={"email": email, "password": "password123"})
    assert r.status_code == 200 and r.json() == {"two_factor_required": True, "user_id": user["id"]}
    assert err(api.post("/api/auth/2fa/verify", json={"user_id": user["id"], "code": "not-it"})) == (401, "two_factor_code_incorrect")
    assert err(api.post("/api/auth/2fa/verify", json={"user_id": "", "code": ""})) == (400, "invalid_body")
    assert err(api.post("/api/auth/2fa/verify", json={"user_id": "nobody", "code": "123456"})) == (401, "two_factor_not_pending")
    assert api.put("/api/auth/2fa", json={"enabled": False}, headers=h).json()["two_factor_enabled"] is False
    assert "token" in api.post("/api/auth/login", json={"email": email, "password": "password123"}).json()


def test_google_endpoints(api):
    r = api.get("/api/auth/google/login")
    if r.status_code == 503:
        assert r.json()["error"]["code"] == "google_oauth_not_configured"
    else:
        assert r.status_code == 302 and urlparse(r.headers["location"]).netloc == "accounts.google.com"
        assert "google_oauth_state=" in r.headers["set-cookie"] and "HttpOnly" in r.headers["set-cookie"]
    r = api.get("/api/auth/google/callback?error=access_denied")
    assert r.status_code == 302
    frag = parse_qs(urlparse(r.headers["location"]).fragment)
    assert frag["google_error"][0] in ("google_denied", "google_oauth_not_configured")


def test_boards_labels_tasks_attachments(api):
    _, h = register(api)
    _, other = register(api)

    r = api.post("/api/boards", json={"name": "Contract", "summary": "sum", "start_date": "2026-09-01"}, headers=h)
    assert r.status_code == 201
    board = r.json()
    assert list(board) == ["id", "user_id", "name", "summary", "start_date", "created_at", "updated_at"]
    assert err(api.post("/api/boards", json={"name": " "}, headers=h)) == (400, "invalid_name")
    assert [b["id"] for b in api.get("/api/boards", headers=h).json()] == [board["id"]]
    assert api.get("/api/boards", headers=other).json() == []
    assert err(api.put(f"/api/boards/{board['id']}", json={"name": "x"}, headers=other)) == (404, "not_found")
    r = api.put(f"/api/boards/{board['id']}", json={"name": "Renamed"}, headers=h)
    assert r.status_code == 200 and (r.json()["name"], r.json()["summary"]) == ("Renamed", "")

    # Labels
    assert err(api.get("/api/labels", headers=h)) == (400, "invalid_board")
    assert err(api.get(f"/api/labels?board_id={board['id']}", headers=other)) == (400, "invalid_board")
    mk = lambda **b: api.post("/api/labels", json={"board_id": board["id"], **b}, headers=h)
    assert err(mk(name="x", color="#000000")) == (400, "invalid_color")
    assert err(mk(name="", color="#ef4444")) == (400, "invalid_name")
    bug = mk(name="Bug", color="#ef4444").json()
    feat = mk(name="Feature", color="#16a34a").json()
    assert list(bug) == ["id", "user_id", "board_id", "name", "color", "created_at"]
    assert {l["id"] for l in api.get(f"/api/labels?board_id={board['id']}", headers=h).json()} == {bug["id"], feat["id"]}
    r = api.put(f"/api/labels/{feat['id']}", json={"name": "Feat", "color": "#0ea5e9"}, headers=h)
    assert (r.json()["name"], r.json()["color"]) == ("Feat", "#0ea5e9")
    assert err(api.delete(f"/api/labels/{bug['id']}", headers=other)) == (404, "not_found")

    # Tasks
    t = lambda **b: api.post("/api/tasks", json={"board_id": board["id"], **b}, headers=h)
    assert err(t()) == (400, "invalid_title")
    assert err(t(title="x", status="nope")) == (400, "invalid_status")
    assert err(t(title="x", due_date="2026-10-01")) == (400, "invalid_due_date")
    assert err(t(title="x", label_ids=["missing"])) == (400, "invalid_label")
    assert err(api.post("/api/tasks", json={"board_id": board["id"], "title": "x"}, headers=other)) == (400, "invalid_board")
    plain = t(title="Plain").json()
    assert list(plain) == ["id", "user_id", "board_id", "title", "description", "status", "created_at", "updated_at"]
    task = t(title="Full", description="d", status="in_progress", due_date="2026-10-01T00:00:00Z", label_ids=[bug["id"], feat["id"]]).json()
    assert list(task) == ["id", "user_id", "board_id", "title", "description", "status", "due_date", "label_ids", "created_at", "updated_at"]
    assert task["due_date"] == "2026-10-01T00:00:00Z" and task["label_ids"] == [bug["id"], feat["id"]]

    r = api.put(f"/api/tasks/{task['id']}", json={"status": "done", "due_date": ""}, headers=h)
    assert r.status_code == 200 and r.json()["status"] == "done" and "due_date" not in r.json()
    assert r.json()["label_ids"] == [bug["id"], feat["id"]] and r.json()["title"] == "Full"
    assert err(api.put(f"/api/tasks/{task['id']}", json={"title": "x", "label_ids": ["missing"]}, headers=h)) == (400, "invalid_label")
    assert err(api.put(f"/api/tasks/{task['id']}", json={}, headers=other)) == (404, "not_found")
    assert {x["id"] for x in api.get(f"/api/tasks?board_id={board['id']}", headers=h).json()} == {plain["id"], task["id"]}
    assert api.get(f"/api/tasks?board_id={board['id']}", headers=other).json() == []

    # Deleting a label strips it from tasks.
    assert api.delete(f"/api/labels/{bug['id']}", headers=h).status_code == 204
    after = next(x for x in api.get("/api/tasks", headers=h).json() if x["id"] == task["id"])
    assert after["label_ids"] == [feat["id"]]

    # Attachments
    base = f"/api/tasks/{task['id']}/attachments"
    r = api.post(base, files={"file": ("notes.txt", b"hello contract", "text/plain")}, headers=h)
    assert r.status_code == 201
    att = r.json()
    assert list(att) == ["id", "task_id", "user_id", "filename", "content_type", "size_bytes", "created_at"]
    assert (att["filename"], att["content_type"], att["size_bytes"]) == ("notes.txt", "text/plain", 14)
    r = api.get(f"{base}/{att['id']}", headers=h)
    assert r.content == b"hello contract" and r.headers["content-type"] == "text/plain"
    assert r.headers["content-disposition"] == 'inline; filename="notes.txt"'
    assert [a["id"] for a in api.get(base, headers=h).json()] == [att["id"]]
    assert err(api.get(base, headers=other)) == (404, "not_found")
    assert err(api.post(base, files={"nope": ("a", b"x")}, headers=h)) == (400, "invalid_file")
    assert err(api.post(base, files={"file": ("big.bin", b"\0" * (10 * 1024 * 1024 + 1))}, headers=h)) == (413, "file_too_large")
    assert api.delete(f"{base}/{att['id']}", headers=h).status_code == 204
    assert err(api.get(f"{base}/{att['id']}", headers=h)) == (404, "not_found")

    # Board delete cascades to tasks and labels.
    assert api.delete(f"/api/boards/{board['id']}", headers=h).status_code == 204
    assert api.get("/api/tasks", headers=h).json() == []
    assert api.get("/api/boards", headers=h).json() == []
