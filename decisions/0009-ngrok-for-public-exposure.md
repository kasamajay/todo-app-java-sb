# 0009. ngrok tunnel on the web container's port only

## Context
The user wants to reach the running app from outside this machine (another device, or to share a link), the same way the sibling `portfolio-website` project does. ngrok was already installed and authenticated on this machine (`ngrok config check` passes), so no install/auth setup was needed here, unlike the real friction `portfolio-website/decisions/0007-ngrok-for-public-exposure.md` documents with an outdated winget build.

## Decision
`start.ps1` tunnels only the `web` container's port (5173), not `api`'s (8080). The browser already only ever talks to Vite's dev server, which proxies `/api/*` to the `api` container over the internal Docker network (see `diagrams/system-architecture.md`) — so one ngrok tunnel exposes the whole app, frontend and API, through a single URL, and `/admin` is reachable at `<ngrok-url>/admin` with no separate tunnel needed.

This required one code change: Vite 5 rejects requests whose `Host` header it doesn't recognize (`Blocked request. This host is not allowed` — a DNS-rebinding protection), and a tunneled request arrives with `Host: <random>.ngrok-free.app`, not `localhost:5173`. `web/vite.config.js` now sets `server.allowedHosts` to ngrok's known domain suffixes (`.ngrok-free.app`, `.ngrok-free.dev`, `.ngrok.app`, `.ngrok.io`, `.ngrok.dev` — a leading dot matches any subdomain, covering ngrok's randomly-assigned tunnel names) rather than `allowedHosts: true`, to stay scoped to the one external domain this project actually expects traffic from instead of disabling the protection entirely.

`start.ps1` also deliberately does **not** run `docker compose down` on exit (Ctrl+C stops only the ngrok process) — unlike portfolio-website's script, where the local server is a plain child process of the script itself, this project's Docker Compose stack is the normal persistent dev environment (`decisions/0001`), and tearing it down just because a temporary public tunnel session ended would be surprising.

## Alternatives considered
- **Tunnel both `api` (8080) and `web` (5173) separately:** would need the frontend to know the API's public URL (breaking the same-origin `/api/*` proxy this app relies on) and a second ngrok tunnel, which the free tier may not even allow concurrently. Unnecessary given the proxy already collapses everything to one origin.
- **`server.allowedHosts: true`:** simpler, but disables Vite's host-checking protection entirely rather than scoping it to the specific external domain (ngrok) this project actually expects to be reached through.
- **Tear down `docker compose` on script exit (matching portfolio-website exactly):** more symmetrical with the sibling project, but would surprise anyone using `docker compose up` as their normal, longer-lived dev loop independent of when they happen to want a public link.

## Consequences
- Anyone with the ngrok URL can reach the full app, including the `/admin` login — acceptable for personal testing/demoing, but worth remembering this isn't a hardened production deployment (dev-grade CORS/TLS-termination assumptions, no rate limiting beyond the existing account-lockout logic).
- ngrok's free tier issues a new random subdomain each run (confirmed: `.ngrok-free.dev` on one run, hence that suffix is in `allowedHosts` alongside `.ngrok-free.app`), so the public URL changes every time `start.ps1` is restarted — nothing to hardcode or bookmark long-term.
- **Confirmed working, not just planned**: loading the tunnel URL in a real browser and registering/logging in round-trips correctly, and Vite's HMR websocket connects cleanly through the tunnel (`[vite] connected.` in the console, no `clientPort` override needed) — the theoretical concern about ngrok's 443→5173 TLS-terminated mapping breaking HMR did not materialize in practice.
- **A first-time browser visit to a free-tier ngrok URL shows ngrok's own interstitial "you are about to visit..." warning page before reaching the app** (a `curl` request doesn't see it — it's triggered by a normal browser User-Agent). This is ngrok's abuse-prevention page, not an app bug; click "Visit Site" through it. It reappears per-browser/per-tunnel, not per-request.
