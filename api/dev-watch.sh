#!/bin/sh
# Dev-mode hot reload for the Spring Boot API - the counterpart of air (Go)
# and uvicorn --reload (Python). See decisions/0008.
#
#   1. A polling loop recompiles src/main whenever a file there changes.
#      Polling (find -newer, once a second) because Docker Desktop's Windows
#      bind mount doesn't propagate file-change events into the container.
#   2. spring-boot:run runs the app with spring-boot-devtools. Devtools only
#      restarts when target/classes/.reloadtrigger changes (trigger-file in
#      application.properties), which this script touches after a
#      successful compile - so a restart never sees half-written classes.
set -e

echo "dev-watch: initial compile (downloads dependencies on first run)..."
mvn -q -B compile

(
  stamp=/tmp/dev-watch.stamp
  touch "$stamp"
  while true; do
    sleep 1
    if [ -n "$(find src/main -type f -newer "$stamp" 2>/dev/null | head -n 1)" ]; then
      touch "$stamp"
      echo "dev-watch: source change detected, recompiling..."
      if mvn -q -o -B compile; then
        touch target/classes/.reloadtrigger
        echo "dev-watch: compiled - devtools will restart the app"
      else
        echo "dev-watch: compile FAILED - fix the error and save again"
      fi
    fi
  done
) &

exec mvn -B spring-boot:run
