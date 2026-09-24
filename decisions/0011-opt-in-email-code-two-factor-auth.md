# 0011. Opt-in two-factor authentication via a logged 6-digit email code

> **In todo-app-java-sb:** Unchanged. Codes come from `SecureRandom` and are logged with the same wording. The rest of this record is kept as written for todo-app (Go).

## Context
The app needed a second login factor, but there is still no email infrastructure (`decisions/0007`) and no requirement to force this on every account. It must apply to both the password `Login` flow and the Google `GoogleCallback` flow, since both are ways to reach the same account.

## Decision
Two-factor auth is opt-in per account (`User.TwoFactorEnabled`, default `false`, toggled by the account owner via `PUT /api/auth/2fa` — no admin override). When enabled, a successful primary auth (password match, or a resolved Google profile) does not immediately mint a token. Instead it generates a 6-digit numeric code via `crypto/rand` (`auth.GenerateSixDigitCode`), stores it with a 10-minute expiry (`User.TwoFactorCode`/`TwoFactorCodeExpires`), and — following the exact pattern `ForgotPassword` established in `0007` — logs it server-side instead of emailing it. `Login` responds `200 {"two_factor_required": true, "user_id": ...}` instead of a token; `GoogleCallback` redirects to `/#google_2fa_required=<user_id>` instead of `/#google_token=...`. The client then calls the new public `POST /api/auth/2fa/verify {user_id, code}`, which mints the real token on a match.

This is deliberately the same insertion point in both flows — immediately after primary auth/lockout checks succeed, immediately before `mint()` — so 2FA behaves identically regardless of which login path reached it, including for the admin account if its owner opts in.

Wrong-code attempts are capped independently of the existing account-lockout mechanism: a separate `TwoFactorAttempts` counter invalidates the pending code after 5 wrong guesses (clearing `TwoFactorCode`/`TwoFactorCodeExpires`/`TwoFactorAttempts`), simply forcing the user to log in again for a fresh code. This is intentionally *not* wired into `FailedLoginCount`/`LockedUntil`.

## Alternatives considered
- **Reuse `FailedLoginCount`/`LockedUntil` for wrong 2FA codes too**: would give a 30-minute account lockout for what's usually just a fat-fingered code, conflating two different security semantics — "this password attempt looks like credential stuffing" vs. "this code entry looks like guessing a 6-digit space, which only has ~1M possibilities and needs its own, smaller retry budget." Kept separate so each mechanism's threshold and consequence fit what it's actually defending against.
- **Time-based OTP (TOTP, e.g. Google Authenticator-style)**: would need a shared-secret provisioning flow (QR code, base32 secret) and a TOTP library or hand-rolled HMAC-based implementation — real added complexity for an app that already has no email delivery to base a recovery flow on. A short-lived, `crypto/rand` code logged server-side (matching `0007`'s already-accepted trade-off) is proportionate to this being a stdlib-only dev/demo app.
- **Mandatory 2FA for all accounts**: rejected per explicit product requirement — opt-in only.

## Consequences
- As with `0007`, "receiving" the code means reading `docker compose logs api` — fine for local/dev, not production-usable as-is. Swapping in real email delivery later is, again, a single change inside `issueTwoFactorChallenge`.
- `Login`'s response shape is now conditional: callers must check for `two_factor_required` before assuming `token`/`user` are present.
- `GoogleCallback` gains a third redirect outcome (`#google_2fa_required=`) alongside the existing `#google_token=`/`#google_error=` pair.
- A user who enables 2FA and then loses access to reading the server log (i.e. in a real deployment, loses access to their email) has no account-recovery path — acceptable for now since this mirrors `0007`'s existing reset-token limitation.
