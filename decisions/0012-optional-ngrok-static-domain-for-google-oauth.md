# 0012. Optional ngrok static domain for stable Google OAuth redirects

## Context
`decisions/0009` and `decisions/0010` both flagged the same limitation: ngrok's free-tier tunnel gets a new random subdomain every run, but Google OAuth requires an exact, pre-registered redirect URI in Google Cloud Console — so Google sign-in worked at `localhost:5173` but not through an ngrok session. The user wants Google sign-in to work over ngrok too, without manually reconfiguring Google Console every time they restart the tunnel.

## Decision
Support an optional `NGROK_DOMAIN` value (set in `.env`) that `start.ps1` passes to ngrok as `--domain=<value>`, pinning the tunnel to a reserved, unchanging hostname instead of a random one. ngrok's free tier includes one such static domain per account (`<name>.ngrok-free.app`), reserved once via the ngrok dashboard.

`GOOGLE_REDIRECT_URI` and `FRONTEND_BASE_URL` in `docker-compose.yml` changed from hardcoded `http://localhost:5173/...` literals to `${VAR:-http://localhost:5173/...}` interpolation (the same mechanism already used for `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`), so a user who sets a static domain can point them at `https://<domain>/...` via `.env` instead. This is a one-time setup: reserve the domain, add three `.env` values, and register the callback URL once in Google Console — after that, every `.\start.ps1` run gets the same public URL, and Google sign-in works through it with no further changes.

`web/vite.config.js`'s `allowedHosts` already covers `.ngrok-free.app` as a wildcard suffix (from `decisions/0009`), so a static subdomain needs no change there.

## Alternatives considered
- **Paid ngrok reserved domain**: unnecessary — the free tier already includes one static domain, which is enough for a single dev/demo deployment.
- **Dynamically deriving the redirect URI from the incoming request's `Host` header** instead of a fixed `.env` value: doesn't actually solve the problem. Google still requires the *exact* redirect URI to be pre-registered in Console; computing it dynamically on our side wouldn't remove the need to register a fixed value there, since a truly random URL still couldn't be pre-registered. A static domain removes the randomness at the source instead.
- **Just document the manual "update Console + `.env` every session" workflow**: works, but is exactly the recurring friction the user asked to avoid; a one-time static-domain setup is strictly better for no real added complexity.

## Consequences
- Reserving the domain (ngrok dashboard) and registering its callback URL (Google Console) both touch the user's own accounts and can't be automated on their behalf — this remains a one-time manual setup, documented in `README.md`.
- Anyone who doesn't set `NGROK_DOMAIN` keeps today's exact behavior: a random ngrok URL each run, Google sign-in only at `localhost:5173`. No regression for the common case.
- If `NGROK_DOMAIN` is set but the domain hasn't actually been reserved in the ngrok dashboard yet, `ngrok http --domain=...` will fail to start the tunnel — `start.ps1` doesn't try to validate or create the domain itself, since that's an ngrok account action outside this script's scope.
