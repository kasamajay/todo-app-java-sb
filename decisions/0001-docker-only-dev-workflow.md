# 0001. Docker Compose for everything, no native Go/Node/make install

> **In todo-app-java-sb:** The decision carries over as-is. Here the `api` container is `maven:3.9-eclipse-temurin-21` running `dev-watch.sh` (polling recompile + spring-boot-devtools) instead of `golang:1.22` + `air`, and no native JDK/Maven install is needed either. See 0014. The rest of this record is kept as written for todo-app (Go).

## Context
The app was speced as Go 1.22 (needed for the stdlib `http.ServeMux` method+wildcard routing used throughout the API) with a Makefile (`install-tools`, `dev-api`, `dev-web`, `test`, `build`). The dev machine only had Go 1.20.6 installed and no `make` on PATH, but did have Docker Desktop running.

## Decision
Everything runs in Docker Compose: a `golang:1.22` container for the API (with `air` for hot reload) and a `node:20` container for the Vite dev server, wired together with a `docker-compose.yml`. The `Makefile` targets are thin wrappers around `docker compose` commands (`dev-api` → `docker compose up api`, `test` → `docker compose run --rm api go test ./...`, etc.), and the README documents the raw `docker compose` commands directly since `make` isn't available either.

## Alternatives considered
- **Install Go 1.22+ and Node natively:** matches the spec's tooling most literally, but the user explicitly asked to avoid native installs in favor of Docker Desktop, which was already installed and running.
- **Target Go 1.20 with a hand-rolled router:** would build with the existing toolchain, but deviates from the explicit "Go 1.22" requirement and loses the clean stdlib routing the newer version provides.

## Consequences
- No version drift between developers — the Go/Node versions are pinned in the Dockerfiles, not the host machine.
- Docker Desktop's Windows bind mount doesn't propagate file-change events into containers by default, which broke hot reload for both `air` and Vite until polling was enabled (see [0008](0008-polling-for-windows-bind-mount-hotreload.md)).
- `api/data` is bind-mounted to the host so JSON storage, the HMAC secret, and uploaded attachments persist across `docker compose down`/`up` cycles.
