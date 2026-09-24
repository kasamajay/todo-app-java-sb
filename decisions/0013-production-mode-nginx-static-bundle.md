# 0013. Production mode: nginx serves a prebuilt Vite bundle, no Node at runtime

> **In todo-app-java-sb:** The same topology. The Go binary is replaced by a multi-stage image: Maven + JDK build the jar in a throwaway stage, and the runtime stage is `eclipse-temurin:21-jre-alpine` with only `app.jar`. nginx, `web/Dockerfile` and `web/nginx.conf` are unchanged. The rest of this record is kept as written for todo-app (Go).

## Context
The only way to run the app was dev mode: the `web` container runs Vite's dev server on `node:20`, compiling JSX per request and proxying `/api/*` to the api, while the `api` container runs `air` over a bind-mounted source tree. That is right for development but wrong for production: a Node process plus the whole toolchain at runtime, unbundled per-module requests, dev-only behaviour (HMR client, polling watchers), and the source tree exposed through the dev server. The user asked for a production mode where the JSX is compiled and bundled, nginx serves the bundle, and there is no Node web layer.

## Decision
Add a separate `docker-compose.prod.yml` (Compose project `todo-app-prod`) alongside the untouched dev `docker-compose.yml`:

- **`web/Dockerfile`** – multi-stage. A `node:20` build stage runs `npm ci` and `vite build` (invoking vite's bin directly, same as `Dockerfile.dev`); the runtime stage is `nginx:1.27-alpine` with only `dist/` copied in. Node exists only in the discarded build stage.
- **`web/nginx.conf`** – takes over what the Vite dev proxy did: `location /api/` → `proxy_pass http://api:8080`. Also: `try_files ... /index.html` so `/admin` (picked in `main.jsx` by pathname) works; `Cache-Control: immutable` on content-hashed `/assets/*` and `no-cache` on `index.html`; gzip; `client_max_body_size 11m` because nginx's 1m default would reject the api's 10MB attachments with 413.
- **`api/Dockerfile`** – multi-stage; `CGO_ENABLED=0` static binary on `alpine:3.20` (CA bundle needed for the Google OAuth HTTPS code exchange). No Go toolchain, no `air`, no source mount.
- nginx is published on host port **8081**; the api is not published at all – nginx is the single entry point.
- Prod-specific Google OAuth URLs come from `PROD_GOOGLE_REDIRECT_URI` / `PROD_FRONTEND_BASE_URL`, defaulting to `http://localhost:8081/...`, so dev's `.env` values (localhost:5173 or an ngrok domain) don't leak into prod.

## Alternatives considered
- **Replace dev mode with the nginx stack**: loses Vite HMR and `air` hot reload, and every frontend edit would need an image rebuild. Rejected – the user chose to keep both.
- **Have the Go api serve `dist/` itself** (`http.FileServer`): one fewer container, but mixes static-asset concerns (caching, gzip, SPA fallback, body limits) into the api and couples the frontend build into the Go image. nginx is the conventional, purpose-built fit and is what was asked for.
- **`vite preview`**: still a Node server at runtime – exactly what the request rules out.
- **Port 5173 or 80**: 5173 would clash with the dev stack; 80 is often taken on Windows. 8081 lets both stacks be built side by side, at the cost of one extra Google redirect URI.
- **`scratch`/distroless for the api**: smaller, but alpine keeps a shell for `docker compose exec` debugging and gets CA certs trivially; the image is ~15MB either way.

## Consequences
- Prod and dev share the `api/data` bind mount (same users/boards/tasks). The JSON store assumes a single writer process, so the two stacks must not run at the same time.
- Frontend changes in prod need `docker compose -f docker-compose.prod.yml up -d --build`; there's no hot reload by design.
- Google sign-in in prod needs `http://localhost:8081/api/auth/google/callback` registered in Google Cloud Console (one-time).
- `start.ps1` gained a `-Prod` switch: it runs `docker compose -f docker-compose.prod.yml up -d --build` and tunnels 8081 instead of 5173, using the same `NGROK_DOMAIN` handling as decisions/0012. nginx's `server_name _` accepts ngrok's Host header, so no equivalent of Vite's `allowedHosts` is needed. Because of the shared `api/data`, the script refuses to start either stack while the other is running and prints the `down` command instead of stopping it itself.
- Resulting images: web ≈ 48MB (nginx + 177kB JS bundle, 55kB gzipped), api ≈ 15MB.
