# Admin panel

A separate `/admin` mini-app for listing users and unlocking locked accounts.

## What it does
- **Bootstrap**: on first API startup, if the user store is empty, `AppState.bootstrapAdmin` creates `admin@todo.io` with a securely random generated password (`SecureRandom`, 18 bytes, base64url-encoded) and prints the plaintext to the server log exactly once — it's never stored or shown again. Subsequent restarts are no-ops (the store already has users).
- **List users** (`GET /api/admin/users`) returns every user as a `PublicUser` (no password hash/salt/reset token), including their lock status and failed-login count.
- **Unlock** (`POST /api/admin/users/{id}/unlock`) clears `FailedLoginCount` and `LockedUntil` on the target user, letting them log in again immediately instead of waiting out the 30-minute lock (see [authentication.md](authentication.md)).
- Both routes require `RequireAuth` **and** `RequireAdmin` — a non-admin bearer token gets 403, not 404, since admin-only access is the point being enforced here (unlike board/task ownership, which hides existence with 404).

## API
| Method | Path | Auth |
|---|---|---|
| GET | `/api/admin/users` | bearer + admin |
| POST | `/api/admin/users/{id}/unlock` | bearer + admin |

## Key files
- `api/src/main/java/io/todo/api/controllers/AdminController.java`
- `api/src/main/java/io/todo/api/AppState.java` — first-run admin creation (`bootstrapAdmin`)
- `api/src/main/java/io/todo/api/web/Auth.java` — `requireAdmin`
- `web/src/admin/AdminApp.jsx` — top-level view machine (`login` / `users`), verifies `is_admin` on the rehydrated user and bounces non-admins back to login
- `web/src/admin/AdminLogin.jsx` — wraps the shared `Login.jsx` with `defaultEmail="admin@todo.io"` and `allowRegister={false}`
- `web/src/admin/UsersTable.jsx` — the user list + unlock button

## UI flow
`web/src/main.jsx` picks `AdminApp` instead of `App` when `window.location.pathname` starts with `/admin` — the only place the app looks at the URL path directly; everything else is `useState`-driven view switching (no React Router, per spec).
