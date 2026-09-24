# 0007. No email infrastructure — reset tokens are logged, not emailed

> **In todo-app-java-sb:** Unchanged. The reset token is logged with the same wording by `AuthController.forgotPassword`. The rest of this record is kept as written for todo-app (Go).

## Context
The spec requires `forgot-password`/`reset-password` endpoints, but nothing in the stack (stdlib-only Go, no third-party services) provides a way to actually send email.

## Decision
`AuthHandler.ForgotPassword` generates a reset token and expiry on the user record when the email matches an account, and logs it server-side (`log.Printf`) instead of emailing it: `password reset requested for <email>: reset_token=<token> (expires ...)`. The endpoint always responds `200 {"message": "if that email exists..."}` regardless of whether the email was found, so the response itself can't be used to enumerate registered accounts. `ResetPassword` accepts `{token, new_password}` and validates the token/expiry before rehashing the password.

## Alternatives considered
- **Integrate a real email provider (SMTP, SendGrid, etc.):** would need credentials/config and an external dependency, well outside a stdlib-only Go backend's scope.
- **Skip forgot/reset-password entirely:** the spec explicitly lists these as required endpoints, so this wasn't an option.

## Consequences
- In this environment, "sending" a reset link means reading it out of `docker compose logs api` — fine for local/dev use, not usable as-is in production.
- Swapping in a real email provider later is a single change inside `ForgotPassword` (replace the `log.Printf` with an actual send) — the token generation/expiry/validation logic doesn't need to change.
