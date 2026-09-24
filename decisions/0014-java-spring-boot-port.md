# 0014. Java (Spring Boot) port of the API with strict contract and data parity

## Context
todo-app has a React/Vite frontend and a stdlib-only Go API; [todo-app-py](https://github.com/kasamajay/todo-app-py) ported that API to Python/FastAPI. todo-app-java-sb is the same application with the API written in **Java / Spring Boot**. It keeps every feature (password auth with lockout, password reset, opt-in 2FA, Sign in with Google, boards, tasks with due dates and labels, attachments, admin panel) and the same dev mode (Vite HMR + API hot reload) and production mode (nginx serving a prebuilt bundle and proxying `/api`).

## Decision
**Stack:**
- Java 21 (LTS), Spring Boot 3.3, Spring MVC on embedded Tomcat, built with Maven (`api/pom.xml`).
- Dependencies: `spring-boot-starter-web`; `spring-boot-devtools` (dev only, excluded from the packaged jar); `spring-boot-starter-test` (tests).
- No Spring Security, JPA or database (decisions/0005, 0004).
- Maven runs inside the `maven:3.9` image, so there's no Maven wrapper; it would only add files.

**The React frontend is copied from todo-app unchanged**, and the Java API reproduces the Go API's HTTP contract exactly:
- The same routes, status codes and `{"error":{"code","message"}}` bodies.
- JSON as Go's `encoding/json` produces it: field order, `omitempty`, RFC3339Nano times (at Go's full **nanosecond** precision, via `OffsetDateTime`) with the zero time still emitted, and `[]byte` as base64 with nil as `null`. See `model/*` and `json/GoJson.java`.
- Request decoding as strict as Go's `DisallowUnknownFields` (`json/BodyDecoder.java`).
- Go ServeMux's plain-text 404/405 (decisions/0005), the Google OAuth cookie and 302 redirects, the 10MB attachment limit with 413, and `Content-Disposition` on downloads.
- Log lines with the same wording (admin bootstrap, logged reset tokens and 2FA codes).

**Data and token compatibility** with both other backends: the same JSON file layout, PBKDF2 parameters over the same bytes (decisions/0002), token format and `secret.key` (decisions/0003).

**One process.** The store keeps each collection in memory and assumes a single writer (decisions/0004), and a single JVM satisfies that. Tomcat's request threads share lock-guarded stores that hand out copies, matching Go's value semantics, so a rejected request never leaves a half-applied change in memory.

**Same host ports as todo-app and todo-app-py** (5173 / 8080 / 8081). Existing Google OAuth redirect URIs and `.env` files work unchanged, but only one of the three apps can run at a time. `start.ps1` checks all six Compose projects.

## Verification
- **JUnit (109 tests):** all 50 Go unit/handler tests ported, plus parity tests mirroring todo-app-py's (wire format, strict decoding, 404/405, 413, cookies, lockout/unlock, reset, Google callback paths with a mocked `GoogleApi`, attachments, reading Go-written data). They run on a real embedded Tomcat, because multipart limits and error routing only behave faithfully there.
- **Contract suite:** todo-app-py's black-box HTTP suite (`api/tests/contract`) passes unchanged against this API. It also passes against the Go and Python APIs, so there are three backends on one contract.
- **Data compatibility:**
  - The Go and Python APIs both logged in a user created by this API, from its data dir.
  - This API accepted a Go-minted token.
  - Every record in a copy of real Go dev data round-trips through the Java models **byte-identically** (nanoseconds included).

## Alternatives considered
- **Spring Boot the conventional way** (Spring Security, JPA/H2, DTO validation, problem-details errors): less custom code, but the frontend would need changes and the data could no longer be shared. That defeats the point of the port.
- **Quarkus / Micronaut / plain Javalin:** all viable, but Spring Boot was the requested stack.
- **Gradle:** Maven was chosen for its ubiquity and simple Docker caching.

## Consequences
- Behaviour changes must be made in all three projects (or deliberately diverge), and the contract suite is how to check they still agree.
- Dev hot reload takes about 8 s edit-to-ready (compile + context restart), versus about 1–2 s for the Go and Python ports (decisions/0008).
- The production API image is ~230MB (Temurin 21 JRE on Alpine + a ~20MB jar), versus ~15MB for the static Go binary and ~170MB for Python.
