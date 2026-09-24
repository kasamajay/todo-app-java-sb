---
name: docker-dev-workflow
description: >
  Building, running, and testing todo-app-java-sb entirely through Docker
  Compose - no native JDK/Maven/Node/make - plus exposing it via ngrok
  (start.ps1), including the real bugs hit setting this up: devtools
  restarting mid-compile, CRLF breaking dev-watch.sh, Windows bind-mount
  file-watching, npm run dev exiting immediately, Vite blocking ngrok's Host
  header, Spring's /error controller, and the ports shared with todo-app and
  todo-app-py. Use when `docker compose up` fails, hot reload misbehaves,
  ngrok exposure isn't working, or you're about to change a Dockerfile,
  docker-compose*.yml, dev-watch.sh, or vite.config.js.
---

# Docker dev workflow (todo-app-java-sb)

## Overview

Everything runs via Docker Compose:
- `api`: `maven:3.9-eclipse-temurin-21` running `api/dev-watch.sh` (polling recompile + `mvn spring-boot:run` with devtools).
- `web`: `node:20` running Vite.

`target/` is an anonymous volume and `~/.m2` the named volume `m2`, so the first start downloads dependencies once.

The host ports are **the same as todo-app (Go) and todo-app-py (Python)**: 5173, 8080 and 8081. Only one of the three apps can be up at a time.

## Quick Start

```
docker compose up                                      # api :8080, web :5173
docker compose logs -f api                             # dev-watch + app logs; admin password on first run
docker compose run --rm --no-deps api mvn -q -B test   # JUnit
docker compose down
.\start.ps1  /  .\start.ps1 -Prod                      # + ngrok tunnel
```

## Known issues (hit for real)

1. **Devtools restarted while Maven was still compiling.** Symptom: `NoClassDefFoundError: io/todo/api/config/AppConfig`, then a second restart. **Fix applied:** `spring.devtools.restart.trigger-file=.reloadtrigger`. `dev-watch.sh` touches `target/classes/.reloadtrigger` only after a *successful* `mvn -o compile`. Don't remove the trigger file setting.
2. **`dev-watch.sh: 12: set: Illegal option -`.** The script had CRLF line endings (edited on Windows). **Fix applied:** `.gitattributes` forces `*.sh text eol=lf`. If you edit the script with a tool that writes CRLF, run `sed -i 's/\r$//' api/dev-watch.sh`.
3. **Hot reload doesn't fire on host edits.** Windows bind mounts don't forward inotify events. `dev-watch.sh` polls with `find src/main -newer <stamp>` once a second, and Vite uses `usePolling`. Expect about 8 s from save to ready: compile plus Spring context restart.
4. **`npm run dev` exits immediately** in this container context (found in todo-app). Keep `node node_modules/vite/bin/vite.js`.
5. **ngrok requests get Vite's `Blocked request. This host is not allowed`.** `web/vite.config.js` `allowedHosts` lists ngrok's suffixes. In production, nginx accepts any Host.
6. **`GET /error` answered 500** (Spring Boot's BasicErrorController); Go returns 404. **Fix applied:** `ErrorMvcAutoConfiguration` is excluded in `application.properties`.
7. **Tests or the dev server touching real data.** Tests set `todo.data-dir` to a temp dir and `AppState.load()` a fresh `@TempDir` per test. The contract suite registers real users, so run it only against an API whose `/app/data` is a throwaway mount.

## Best Practices

### ✅ DO
- After changing `dev-watch.sh`, `docker-compose*.yml`, a Dockerfile or `vite.config.js`, prove hot reload still works: touch a controller and look for `dev-watch: compiled` followed by a single `listening on` line in `docker compose logs api`.
- Reset data for a fresh admin bootstrap: `docker compose down`, delete everything in `api/data/` except `.gitkeep`, then `docker compose up -d`.
- Use `MSYS_NO_PATHCONV=1` when passing Unix-style paths to `docker` from Git Bash.

### ❌ DON'T
- Don't start this app while todo-app or todo-app-py is up. The ports collide, and `start.ps1` will refuse anyway.
- Don't point devtools back at raw classpath changes without the trigger file.
- Don't run more than one API process against the same `api/data`.
