---
name: java-contract-parity-api
description: >
  Conventions for api/ in todo-app-java-sb: a Spring Boot port that must stay
  byte-compatible with todo-app's Go API (same routes, status codes, error
  bodies, JSON field order/omission, time format, cookies, redirects, data
  files and token format) because the React frontend is shared unchanged.
  Use when adding or changing an endpoint, a model field, request decoding,
  error handling or storage, or when tempted to "Spring-ify" something
  (Spring Security, DTO binding, problem-details errors, JPA).
---

# Java API with Go-contract parity (todo-app-java-sb)

## Overview

`web/` is identical to todo-app's, so the API's HTTP behaviour **is** the spec. Every controller in `api/src/main/java/io/todo/api/controllers/` mirrors a Go handler in `../todo-app/api/internal/handlers/`: the same check order, error codes and messages. The on-disk JSON and the tokens are interchangeable with the Go and Python backends. See `../../../decisions/0014-java-spring-boot-port.md`.

## Rules

- **Responses:** return `Responses.json(status, node)` / `Responses.jsonList(...)` / `Responses.noContent()` (`ResponseEntity<byte[]>`), or throw `new ApiError(status, code, message)`. Don't return POJOs for Jackson to serialize, because that changes field order, time format and content type.
- **Request bodies:** take `@RequestBody(required = false) byte[] raw` and call `BodyDecoder.decode(raw, BodyDecoder.schema(...))`.
  - Use `STRING`/`BOOL` for Go value fields (null → zero value).
  - Use `OPT_STRING`/`OPT_STRING_LIST` for Go pointer fields (null/absent → `null` = "not provided").
  - Never bind to DTOs and never use `@Valid`.
- **Auth:** call `auth.requireUser(req)` or `auth.requireAdmin(req)` as the handler's first statement. Don't add Spring Security. Ownership failures are **404** (never confirm existence); admin gatekeeping is 403.
- **Models:** `toJson` field order is the Go struct order.
  - `omitempty` strings, ints and lists are left out when empty.
  - Times always go through `GoJson.formatTime`, including `ZERO_TIME`.
  - `byte[]` becomes base64, and `null` becomes JSON `null`.
  - A new field needs the same change in the Go model (and the Python port) to keep sharing data.
- **Storage:** stores return copies. Fetch, mutate, `put()`. One JVM process only, because the store is in-memory with a single writer.
- **Errors:** add a Spring exception to `ErrorHandling` rather than letting a framework default body escape. Unknown routes must stay plain-text `404 page not found`.
- **Logging:** keep the wording of the lines users grep for (admin bootstrap, `password reset requested for …`, `two-factor code requested for …`).

## Checking parity

```
docker compose run --rm --no-deps api mvn -q -B test      # includes ParityTest
# contract suite against a running API on throwaway data (see docker-dev-workflow):
docker run --rm -v "$PWD/contract:/c" -e CONTRACT_BASE_URL=http://host.docker.internal:8080 python:3.12-slim sh -c "pip install -q pytest==8.3.4 httpx==0.28.1 && pytest -q /c"
```

Run the contract suite against every backend after a behaviour change. If only some pass, they've diverged.
