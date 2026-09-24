# Todo App (Java / Spring Boot API)

Full-stack todo app: a **Java 21 / Spring Boot 3** API with custom auth (HMAC
tokens, PBKDF2-SHA512 password hashing, account lockout, opt-in 2FA, Sign in
with Google) and JSON file storage, plus a React 18 + Vite 5 frontend with a
drag-and-drop Kanban board, labels, due dates, attachments and an admin panel.

This is the third implementation of the same app:

| Project | Backend |
|---|---|
| [todo-app](https://github.com/kasamajay/todo-app) | Go (stdlib only) |
| [todo-app-py](https://github.com/kasamajay/todo-app-py) | Python (FastAPI) |
| **todo-app-java-sb** | **Java (Spring Boot)** |

**The React frontend is copied from todo-app unchanged.** The Spring Boot API
exposes exactly the same HTTP contract (routes, status codes, error bodies,
JSON shapes) and uses the same on-disk data and token formats, so a data
directory can move between all three backends. See
[`decisions/0014`](decisions/0014-java-spring-boot-port.md).

Everything runs via **Docker Desktop** - no native JDK, Maven, Node, npm, or
make installation is required or used.

## Quick start

```
docker compose up
```

- API: http://localhost:8080
- Web app: http://localhost:5173
- Admin panel: http://localhost:5173/admin

The first start takes a minute or two: Maven downloads dependencies into a
named volume (`m2`), which later starts reuse.

On first boot the API creates an `admin@todo.io` account with a random
password and **prints it once** to the API container logs:

```
docker compose logs api
```

Look for a block like:

```
Bootstrapped admin account (shown only once):
  email:    admin@todo.io
  password: <random>
```

Save it - it is never shown again (though you can always reset it via
"Forgot password?" on the admin login, since the reset token is logged
server-side).

**Same ports as todo-app and todo-app-py.** All three use 5173 / 8080 / 8081,
so only one can run at a time - `docker compose down` in the other project
first.

## Dev mode

- `web`: Vite's dev server compiles each `.jsx` on demand and pushes changes
  to the browser over an HMR WebSocket; it proxies `/api/*` to the `api`
  container.
- `api`: `api/dev-watch.sh` runs `mvn spring-boot:run` with
  **spring-boot-devtools**, plus a polling loop that recompiles `src/main`
  whenever a file changes. After a successful compile it touches a trigger
  file and devtools restarts the app - roughly **8 s from save to ready**. A
  failed compile leaves the last good build running. Polling is needed because
  Docker Desktop's Windows bind mount doesn't forward file-change events
  ([`decisions/0008`](decisions/0008-polling-for-windows-bind-mount-hotreload.md)).

## Production mode

Production mode compiles and bundles the frontend at image build time and
serves it as static files from **nginx** - no Node process runs at all:

```
docker compose -f docker-compose.prod.yml up -d --build
```

- Web app: http://localhost:8081
- Admin panel: http://localhost:8081/admin
- Stop: `docker compose -f docker-compose.prod.yml down`

```
browser -> nginx :8081 --+-- /assets/*, index.html  (prebuilt Vite bundle)
                         +-- /api/*  -> api :8080   (java -jar, not exposed on the host)
```

- `web/Dockerfile` runs `vite build` in a throwaway `node:20` stage and copies
  only `dist/` into `nginx:alpine`; `web/nginx.conf` does the `/api` proxy,
  the `/admin` -> `index.html` fallback, gzip, and caching.
- `api/Dockerfile` builds the jar with Maven + JDK in a throwaway stage and
  extracts it into layers; the runtime image is `eclipse-temurin:21-jre-alpine`
  with a thin `app.jar` (our code) + `lib/*.jar` (dependencies) as separate
  image layers - no JDK, Maven, sources or devtools.
- It runs under its own Compose project (`todo-app-java-sb-prod`) but **shares
  `api/data`** with dev. Don't run both stacks at once.
- Google sign-in in prod: add `http://localhost:8081/api/auth/google/callback`
  as an extra Authorized redirect URI in Google Cloud Console. Override the
  prod URLs with `PROD_GOOGLE_REDIRECT_URI` / `PROD_FRONTEND_BASE_URL` in `.env`.
- Code changes need a rebuild (`--build`) - there is no hot reload.

See [`decisions/0013`](decisions/0013-production-mode-nginx-static-bundle.md) and
[`diagrams/dev-vs-prod-serving.md`](diagrams/dev-vs-prod-serving.md).

## Public access via ngrok

Expose the app via [ngrok](https://ngrok.com) (requires `ngrok` installed and authenticated: `ngrok config add-authtoken <token>`):

```
.\start.ps1          # dev mode, tunnels :5173
.\start.ps1 -Prod    # production mode, rebuilds and tunnels :8081
```

The script brings the stack up if needed, tunnels the `web` container's port, and prints a public HTTPS URL; Vite (dev) or nginx (prod) proxies `/api/*`, so one URL serves the whole app. Ctrl+C stops the tunnel only.

It refuses to start when a conflicting stack is running - this app's other mode (shared `api/data`) or any todo-app / todo-app-py stack (same ports) - and prints the `down` command to run first. See [`decisions/0009`](decisions/0009-ngrok-for-public-exposure.md).

## Sign in with Google (optional)

Disabled until you configure your own OAuth client:

1. https://console.cloud.google.com/apis/credentials → select or create a project.
2. **OAuth consent screen** → "External", **Testing** mode (add your Google account as a test user).
3. **Credentials → Create Credentials → OAuth client ID** → **Web application**.
4. **Authorized redirect URIs** → add `http://localhost:5173/api/auth/google/callback`.
5. `cp .env.example .env` and fill in `GOOGLE_CLIENT_ID=` / `GOOGLE_CLIENT_SECRET=`.
6. `docker compose up -d api` (recreate, so Compose re-reads `.env`).

If you already set this up for todo-app or todo-app-py, the same OAuth client,
redirect URIs and `.env` work here - the ports and callback paths are identical.
For Google sign-in over a static ngrok domain, see
[`decisions/0012`](decisions/0012-optional-ngrok-static-domain-for-google-oauth.md).

## Tests

```
docker compose run --rm --no-deps api mvn -q -B test
```

- **JUnit 5 (109 tests)** against a real embedded Tomcat on a random port,
  each test on a fresh temp data dir: the 50 Go tests ported one-to-one
  (PBKDF2 vectors, tokens, Google OAuth, 2FA, labels, tasks, board cascade)
  plus parity tests for the Go wire details the frontend relies on.
- **Contract suite** (`contract/`): black-box HTTP tests shared with
  todo-app-py, passing against the Go, Python and Java APIs alike. Point it at
  a running API **on throwaway data** (it registers random users):

  ```
  docker run --rm -v "$PWD/contract:/c" -e CONTRACT_BASE_URL=http://host.docker.internal:8080 python:3.12-slim sh -c "pip install -q pytest==8.3.4 httpx==0.28.1 && pytest -q /c"
  ```

## CI/CD

GitHub Actions ([`decisions/0015`](decisions/0015-ci-artifacts-fat-jar-and-layered-image.md)):

- **`ci.yml`** (PRs + `main`): JUnit (`mvn verify`) → the executable jar (`app.jar` + SHA-256, kept 30 days as a workflow artifact) → the production stack under Compose with the contract suite run through nginx → both images built; on `main` they're pushed to GHCR as `ghcr.io/kasamajay/todo-app-java-sb-{api,web}:<sha>` and `:latest`.
- **`release.yml`** (tag `vX.Y.Z`): the version is set from the tag, then tests, a GitHub Release with `app.jar` + checksum, and images tagged `X.Y.Z`.
- Deploying (CD) is not wired up yet. The GHCR images are the deploy unit.

What gets built: **one executable fat jar, not a war**. `app.jar` contains our classes (`BOOT-INF/classes`) and all 30 dependency jars nested unchanged (`BOOT-INF/lib`, embedded Tomcat included); `java -jar` starts Spring Boot's `JarLauncher`, which loads those nested jars and calls `TodoApiApplication`. The production image instead *extracts* the jar into layers: a thin `app.jar` (~92 KB) plus `lib/*.jar` (~19 MB) as separate Docker layers, so a code change ships ~100 KB instead of 20 MB.

## Makefile / raw docker compose commands

| Make target      | Equivalent command                              |
|------------------|---------------------------------------------------|
| `install-tools`  | `docker compose build`                             |
| `dev-api`        | `docker compose up api`                             |
| `dev-web`        | `docker compose up web`                             |
| `dev`            | `docker compose up`                                 |
| `test`           | `docker compose run --rm --no-deps api mvn -q -B test` |
| `build`          | `docker compose build`                              |
| `down`           | `docker compose down`                               |
| `prod-build`     | `docker compose -f docker-compose.prod.yml build`   |
| `prod-up`        | `docker compose -f docker-compose.prod.yml up -d --build` |
| `prod-down`      | `docker compose -f docker-compose.prod.yml down`    |

## Project layout

```
api/          Spring Boot app (Maven): src/main/java/io/todo/api, JUnit tests in src/test, JSON storage in api/data/
web/          React 18 + Vite 5 frontend (identical to todo-app)
contract/     Black-box HTTP contract suite shared with todo-app-py
decisions/    ADRs - why things are built the way they are
diagrams/     Mermaid diagrams: architecture, auth flow, data model, dev vs prod serving
features/     What each feature does - API routes, key files, UI flow
```

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register` | none |
| POST | `/api/auth/login` | none |
| POST | `/api/auth/logout` | bearer |
| GET | `/api/auth/me` | bearer |
| POST | `/api/auth/forgot-password` | none |
| POST | `/api/auth/reset-password` | none |
| POST | `/api/auth/2fa/verify` | none (pending challenge) |
| PUT | `/api/auth/2fa` | bearer |
| GET | `/api/auth/google/login` | none |
| GET | `/api/auth/google/callback` | none |
| GET/POST | `/api/boards` | bearer |
| PUT/DELETE | `/api/boards/{id}` | bearer (owner) |
| GET/POST | `/api/tasks` | bearer |
| PUT/DELETE | `/api/tasks/{id}` | bearer (owner) |
| GET/POST | `/api/labels` | bearer |
| PUT/DELETE | `/api/labels/{id}` | bearer (owner) |
| POST/GET | `/api/tasks/{id}/attachments` | bearer (owner) |
| GET/DELETE | `/api/tasks/{id}/attachments/{aid}` | bearer (owner) |
| GET | `/api/admin/users` | bearer + admin |
| POST | `/api/admin/users/{id}/unlock` | bearer + admin |
