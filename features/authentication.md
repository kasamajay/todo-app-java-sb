# Authentication

Register/login/logout/me/forgot-password/reset-password, account lockout, and timing-safe login.

## What it does
- **Register** (`POST /api/auth/register`) — email + password (min 8 chars), rejects duplicate emails (409). On success, hashes the password (PBKDF2-HMAC-SHA512, see [decisions/0002](../decisions/0002-hand-rolled-pbkdf2.md)), creates the user, and returns `{token, user}` (auto-login).
- **Login** (`POST /api/auth/login`) — verifies email/password, mints and returns a token on success.
  - **Timing-safe**: PBKDF2 always runs, whether or not the email exists — against the real user's salt/hash if found, or a fixed package-level dummy salt/hash if not — so response latency can't be used to enumerate registered emails.
  - **Account lockout**: after 3 failed attempts, `LockedUntil` is set to 30 minutes in the future on the user record; further login attempts return 403 `account_locked` until it expires or an admin unlocks the account (see [admin-panel.md](admin-panel.md)). A successful login resets the failed-attempt counter.
- **Logout** (`POST /api/auth/logout`) — returns 204. Tokens are stateless (see [decisions/0003](../decisions/0003-custom-hmac-tokens-not-jwt.md)), so this is a no-op server-side; the frontend discards the token from `localStorage`.
- **Me** (`GET /api/auth/me`) — returns the current user (from the bearer token) as a `PublicUser` (no password hash/salt).
- **Forgot password** (`POST /api/auth/forgot-password`) — if the email matches an account, generates a reset token (1h expiry) and logs it server-side (see [decisions/0007](../decisions/0007-no-email-infra-reset-token-logged.md)); always responds 200 regardless of whether the email exists.
- **Reset password** (`POST /api/auth/reset-password`) — `{token, new_password}`, validates the token and expiry, rehashes the password, and clears any lockout state.
- **Sign in with Google** (`GET /api/auth/google/login` → `GET /api/auth/google/callback`) — OAuth 2.0 Authorization Code flow (see [decisions/0010](../decisions/0010-google-oauth-authorization-code-flow.md)); on the regular login screen only, not `/admin`. Matches an existing account by `GoogleID` first, then auto-links to an existing password account by verified email, else creates a new password-less account. A password `Login` attempt against a Google-only account (no password set) returns the same generic `invalid_credentials` as a nonexistent email. Disabled (routes respond `503`) unless `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` are configured.
- **Two-factor authentication** — opt-in per account (`User.TwoFactorEnabled`, off by default), toggled by the account owner via `PUT /api/auth/2fa` (see [decisions/0011](../decisions/0011-opt-in-email-code-two-factor-auth.md)). When enabled, a successful password `Login` or Google sign-in doesn't mint a token immediately — it issues a 6-digit code (10-minute expiry, logged server-side rather than emailed, same reasoning as [decisions/0007](../decisions/0007-no-email-infra-reset-token-logged.md)) and responds `{"two_factor_required": true, "user_id": ...}` (password path) or redirects to `#google_2fa_required=<user_id>` (Google path). The client then calls `POST /api/auth/2fa/verify {user_id, code}` to complete login. Wrong codes are capped at 5 attempts (separate from the account-lockout mechanism), after which the code is invalidated and a fresh login is required.

## API
| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register` | none |
| POST | `/api/auth/login` | none |
| POST | `/api/auth/logout` | bearer |
| GET | `/api/auth/me` | bearer |
| POST | `/api/auth/forgot-password` | none |
| POST | `/api/auth/reset-password` | none |
| GET | `/api/auth/google/login` | none |
| GET | `/api/auth/google/callback` | none |
| POST | `/api/auth/2fa/verify` | none |
| PUT | `/api/auth/2fa` | bearer |

## Key files
- `api/src/main/java/io/todo/api/controllers/AuthController.java` — register/login/logout/me/forgot/reset handlers, plus `verifyTwoFactor`/`updateTwoFactor` and the shared `issueTwoFactorChallenge` helper.
- `api/src/main/java/io/todo/api/controllers/GoogleAuthController.java` — `login`/`callback` (the latter also gates on `twoFactorEnabled`).
- `api/src/main/java/io/todo/api/auth/Passwords.java` — PBKDF2-HMAC-SHA512 on `javax.crypto.Mac`.
- `api/src/main/java/io/todo/api/auth/Tokens.java`, `Secrets.java` — token mint/verify, signing secret, random IDs/codes.
- `api/src/main/java/io/todo/api/auth/GoogleApi.java` — `java.net.http.HttpClient` calls to Google's token-exchange and userinfo endpoints.
- `api/src/main/java/io/todo/api/auth/Secrets.java` — `sixDigitCode` (`SecureRandom`).
- `api/src/main/java/io/todo/api/web/Auth.java` — `requireUser` extracts and verifies the bearer token.
- `api/src/main/java/io/todo/api/model/User.java` — `failedLoginCount`, `lockedUntil`, `resetToken`, `resetTokenExpires`, `googleId`, `twoFactorEnabled`, `twoFactorCode`, `twoFactorCodeExpires`, `twoFactorAttempts`.
- `web/src/components/Login.jsx` — single component covering all password modes (login/register/forgot/reset/twoFactor) plus the Google button (`allowGoogle` prop) and the 2FA code-entry step (`initialTwoFactorUserId` prop, for the Google-redirect case).
- `web/src/admin/AdminLogin.jsx` — renders `Login` with `allowGoogle={false}`.
- `web/src/App.jsx` — picks up `#google_token=`/`#google_error=`/`#google_2fa_required=` from the URL hash on mount after the Google redirect.
- `web/src/components/TwoFactorSettings.jsx` — settings modal (opened from `BoardsList.jsx`'s header) to toggle 2FA on/off.
- `web/src/api.js` — `getToken`/`setToken`/`clearToken`, and `onUnauthorized` hook that bounces the app back to the login view on any 401.

## Related
[diagrams/auth-sequence.md](../diagrams/auth-sequence.md) — sequence diagram of the login request flow.
