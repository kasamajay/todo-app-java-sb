# 0008. Hot reload under Docker Desktop on Windows: polling recompile + devtools trigger file

## Context
The dev containers hot-reload on source changes. Vite serves updated frontend modules and pushes HMR updates. For Java, a source edit has to be **compiled** and the app **restarted**. That's the job `air` does for Go and `uvicorn --reload` does for Python.

Docker Desktop's Windows bind mount doesn't forward inotify events into the Linux container, so anything waiting for filesystem events sees nothing.

Two more things came up while setting this up:
- **Restart during compile:** with devtools watching `target/classes` directly, a restart fired *while Maven was still rewriting class files*. The first restart failed with `NoClassDefFoundError: io/todo/api/config/AppConfig`, and a second restart succeeded once the compile finished. It worked, but noisily, and a slower compile could have left the app down.
- **CRLF line endings:** a script edited on the Windows host picked up CRLF line endings, and `sh` in the container failed with `set: Illegal option -`.

The todo-app issue with `npm run dev` exiting immediately in this container context also still applies to the `web` service.

## Decision
- `api/dev-watch.sh` (the dev container's command):
  1. Runs an initial `mvn compile`.
  2. Starts a **polling loop**: once a second, `find src/main -newer <stamp>` checks for changes, and on a change it runs `mvn -o compile`. After a *successful* compile it touches `target/classes/.reloadtrigger`.
  3. Runs `mvn spring-boot:run` with **spring-boot-devtools** on the classpath.
- `application.properties` sets `spring.devtools.restart.trigger-file=.reloadtrigger`, so devtools restarts only when that file changes, never mid-compile. A failed compile doesn't touch the trigger, so the last good build keeps running. It also sets `spring.devtools.livereload.enabled=false`, since Vite already handles the browser.
- `target/` lives in an anonymous volume and `~/.m2` in a named volume (`m2`). Builds stay inside the container and fast, and dependencies survive rebuilds.
- `.gitattributes` forces `*.sh` to LF, so a Windows checkout with `core.autocrlf=true` can't break the script.
- `web/vite.config.js` keeps `server.watch.usePolling`, and the `web` command runs `node node_modules/vite/bin/vite.js` directly. Both are unchanged from todo-app.

Verified live:
- Touching `BoardsController.java` gave `dev-watch: source change detected, recompiling...`, then `dev-watch: compiled - devtools will restart the app`, then one clean `todo-app api listening on :8080`. The API answered again about **8 s after the save**.
- Touching `web/src/main.jsx` gave `[vite] page reload src/main.jsx`.

## Alternatives considered
- **Devtools alone, with the IDE compiling:** the standard setup, but there's no IDE compiler inside the container, and the host's compiler output would cross the slow bind mount.
- **Gradle continuous build (`-t`):** relies on file-system events, which don't arrive through this bind mount.
- **Restarting the whole container on change:** simple, but a full JVM + Spring startup plus Maven every time is several times slower than a devtools restart.

## Consequences
- Edit-to-ready takes about 8 s, versus about 1–2 s for Go/`air` and uvicorn. Compilation and a Spring context restart cost more.
- These are Windows-bind-mount workarounds. They're harmless, but unnecessary, on a native Linux Docker host.
- A devtools restart rebuilds the Spring context, so the in-memory stores reload from the JSON files. That's safe because every write is persisted immediately (decisions/0004).
