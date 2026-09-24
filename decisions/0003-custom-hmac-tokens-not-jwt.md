# 0003. Custom HMAC-SHA256 tokens, not JWT

> **In todo-app-java-sb:** The same token format and claims, in `api/src/main/java/io/todo/api/auth/Tokens.java` (`javax.crypto.Mac` HmacSHA256, no JWT library). Tokens are interchangeable with the Go and Python backends given the same `data/secret.key`. The rest of this record is kept as written for todo-app (Go).

## Context
The spec explicitly called for "Custom HMAC-SHA256 tokens (not JWT)" for authentication, to be implemented with stdlib crypto primitives.

## Decision
`api/internal/auth/token.go` mints tokens of the form `base64url(json claims).hex(HMAC-SHA256(claims, secret))`, where claims are `{uid, adm, exp}`. The signing secret is 32 random bytes generated once via `crypto/rand` and persisted to `data/secret.key` on first boot, so tokens remain valid across process restarts. `Verify` recomputes the HMAC and compares it with `hmac.Equal` (constant-time) before trusting the claims, and separately checks `exp` against `time.Now()`.

Tokens are stateless — there is no server-side session store or revocation list. `POST /api/auth/logout` therefore just returns 204; the actual "logout" is the frontend discarding the token from `localStorage`.

## Alternatives considered
- **JWT (`github.com/golang-jwt/jwt` or similar):** the conventional choice, but explicitly excluded by the spec and would add an external dependency.
- **Server-side session store (e.g. a `sessions.json` keyed by token):** would allow true logout/revocation, but adds a stateful collection to keep in sync and wasn't required by the spec; skipped to keep the auth model simple and match "custom HMAC tokens" literally.

## Consequences
- A token issued before a user is deleted, demoted from admin, or otherwise changed stays valid (with its original claims) until it expires (`TokenTTL` = 24h) — there's no way to force-invalidate a specific token early.
- Adding true logout/revocation later would need a denylist store keyed by token or a token ID, checked in `middleware.RequireAuth`.
