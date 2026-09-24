# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A full-stack todo app: a Java 21 **Spring Boot 3** API (`api/`, Maven) with custom auth (HMAC-SHA256 tokens, PBKDF2-SHA512 on `javax.crypto.Mac`, account lockout, opt-in 2FA, Google OAuth) and JSON file storage, plus a React 18 + Vite 5 frontend (`web/`) with a Kanban board and an admin panel. Everything runs via Docker Compose — there is no native JDK/Maven/Node/make on this machine, and none is required.

It is the third implementation of **todo-app** (`../todo-app`, Go) alongside **todo-app-py** (`../todo-app-py`, FastAPI). `web/` is copied from todo-app **unchanged**. The Java API must keep the Go API's HTTP contract byte-compatible:
- the same routes, status codes and `{"error":{"code","message"}}` bodies
- the same JSON field names, order and omission, and the same time format
- the same redirects and cookies
- the same on-disk data and token formats (decisions/0014)

When changing API behaviour, check the Go handler in `../todo-app/api/internal/handlers/` first. Keep the contract suite (`contract/`) passing against all backends.

## Commands

```
docker compose up                                        # full stack: api :8080, web :5173 (first start downloads Maven deps)
docker compose logs api                                  # admin bootstrap password on first run; dev-watch recompile/restart lines
docker compose down
docker compose run --rm --no-deps api mvn -q -B test     # JUnit suite (109 tests, real Tomcat, temp data dirs)
docker compose run --rm --no-deps api mvn -B test -Dtest=ParityTest   # one class
.\start.ps1  /  .\start.ps1 -Prod                        # dev / prod stack + ngrok tunnel
docker compose -f docker-compose.prod.yml up -d --build  # prod: nginx :8081 + java -jar
```

Contract suite, run against an API on **throwaway data only**, since it registers random users. Mount a temp dir at `/app/data` via a Compose override, never `api/data`:
```
docker run --rm -v "$PWD/contract:/c" -e CONTRACT_BASE_URL=http://host.docker.internal:8080 python:3.12-slim sh -c "pip install -q pytest==8.3.4 httpx==0.28.1 && pytest -q /c"
```

**Same host ports as todo-app and todo-app-py**, so only one of the three apps runs at a time. `start.ps1` refuses if any of the six Compose projects (`todo-app[-prod]`, `todo-app-py[-prod]`, `todo-app-java-sb[-prod]`) other than its own is running.

## CI/CD

`.github/workflows/ci.yml` (test → package jar → contract suite against the prod stack → images, pushed to GHCR only on `main`) and `release.yml` (tag `vX.Y.Z` → Release with `app.jar` + images `:X.Y.Z`). `api/Dockerfile` extracts the fat jar into layers (thin `app.jar` + `lib/`), so keep dependencies and app in separate `COPY` layers. Lint workflows with `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest`. See decisions/0015.

## Git workflow

Never commit directly to `main`. Branch, verify, push, and open a PR (`gh pr create`). Don't merge PRs yourself.

## Architecture

### Docker layer
- **Dev** (`docker-compose.yml`, project `todo-app-java-sb`):
  - `api` is `maven:3.9-eclipse-temurin-21` running `api/dev-watch.sh`. The script polls `src/main` every second, recompiles with `mvn -o compile` on change, and after a *successful* compile touches `target/classes/.reloadtrigger`. `mvn spring-boot:run` runs the app with devtools configured to restart only on that trigger file (decisions/0008).
  - `target/` is an anonymous volume and `~/.m2` a named volume (`m2`).
  - Keep `*.sh` LF (`.gitattributes`). A CRLF `dev-watch.sh` fails with `set: Illegal option -`.
  - `web` is identical to todo-app. It runs Vite via `node node_modules/vite/bin/vite.js`, not `npm run dev`, and Vite proxies `/api` to `http://api:8080`.
- **Prod** (`docker-compose.prod.yml`, project `todo-app-java-sb-prod`):
  - `api/Dockerfile` builds `app.jar` in a Maven/JDK stage and extracts it into layers. The runtime is `eclipse-temurin:21-jre-alpine` running `java -jar /app/app.jar`: a thin jar with `lib/*.jar` on its manifest Class-Path (decisions/0015).
  - nginx serves the bundle on 8081 and proxies `/api/`. The API isn't published on the host.
  - Prod shares `api/data` with dev. Prod Google URLs come from `PROD_GOOGLE_REDIRECT_URI` / `PROD_FRONTEND_BASE_URL`.

### Backend (`api/src/main/java/io/todo/api`)
- `AppState` — loads the stores and the secret from the data dir and bootstraps `admin@todo.io` when there are no users. `load(Path)` re-points everything at another dir, which the tests use per test. `config/AppConfig` maps the Go env vars (empty = unset).
- `store/JsonStore` (+ `Stores.*`) — an in-memory `TreeMap` behind a lock, with an atomic file rewrite on every put/delete, in Go's file format.
  - Entities are **copied in and out** (Go value semantics): mutate a fetched entity, then `put()`.
  - Must be a single process.
- `model/*` — mutable entities with hand-written `toJson`/`fromJson` reproducing Go's `encoding/json`: field order, `omitempty` (task `due_date`/`label_ids` omitted when empty), the zero time emitted as `"0001-01-01T00:00:00Z"`, RFC3339Nano times at nanosecond precision (`json/GoJson`), `byte[]` as base64 (null → `null`). `User.publicView()` is the only user shape sent to clients.
- `json/BodyDecoder` — strict decoding equivalent to Go's `DisallowUnknownFields`, over Jackson's tokenizer:
  - Unknown fields, wrong JSON types and empty or invalid bodies → 400 `invalid_body`.
  - Keys match case-insensitively.
  - `STRING`/`BOOL` fields take zero values on null. `OPT_STRING`/`OPT_STRING_LIST` fields are `null` = "not provided" (task PUT is a partial update).
  - **Never bind request bodies to DTOs.**
- `web/` pieces:
  - `Responses`: compact JSON as `ResponseEntity<byte[]>`, Content-Type exactly `application/json`.
  - `ApiError` + `ErrorHandling`: Go ServeMux plain-text 404/405, 413 for multipart failures, 500 fallback.
  - `Auth`: `requireUser`/`requireAdmin`, called as a handler's first statement. **No Spring Security** (decisions/0005).
  - `RequestLogFilter`: Go-style request log lines.
- `controllers/*` — one per Go handler file, with the same check order and messages.
  - Ownership failures are **404**; admin routes are 403.
  - Login always runs PBKDF2 (dummy hash for unknown or Google-only accounts). 3 failures lock the account for 30 minutes.
  - Cascades: board → tasks → attachments (+ blobs) and labels; task → attachments; label → stripped from tasks.
  - Uploads: multipart is resolved lazily (ownership check first), capped at 10MB + 1024 per request; 413 `file_too_large`.
- `auth/`:
  - `Passwords`: PBKDF2 on `Mac` over UTF-8 bytes, 100k iterations, 64-byte key, 16-byte salt.
  - `Tokens`: `base64url(json{"uid","adm","exp"}).hex(hmac)`, 24h.
  - `Secrets`: `secret.key`, IDs, 2FA codes, OAuth state.
  - `GoogleApi`: a bean using `java.net.http`, mocked in tests.
- `application.properties` disables the whitelabel page, the `/error` controller (`ErrorMvcAutoConfiguration`) and static resources, lifts Tomcat's swallow limit (so clients get the 413), and sets Go's log format.
- Log lines that users grep for (admin bootstrap block, `password reset requested for … reset_token=…`, `two-factor code requested for … code=…`) must keep their exact wording.

### Frontend (`web/src`)
Unchanged from todo-app. See `../todo-app/CLAUDE.md`. In short: no router (`main.jsx` switches on pathname), `api.js` fetch wrapper, inline styles, native HTML5 drag-and-drop.

## Documentation

- [`decisions/`](decisions/README.md) — ADRs. 0001–0013 come from todo-app with a Java note; 0002, 0005 and 0008 were rewritten for Java; 0014 records the port.
- [`diagrams/`](diagrams/README.md), [`features/`](features/README.md) — architecture/flow diagrams and per-feature reference, with Java file paths.
