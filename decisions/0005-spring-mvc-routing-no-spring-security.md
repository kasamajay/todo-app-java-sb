# 0005. Spring MVC routing without Spring Security, with Go ServeMux behaviour reproduced at the edges

*Replaces todo-app's 0005 ("Go 1.22 stdlib `http.ServeMux` routing, no router package").*

## Context
The API needs method-aware routes with path parameters (`PUT /api/boards/{id}`, `GET /api/tasks/{id}/attachments/{aid}`). Spring MVC provides that, but several Spring Boot defaults differ observably from the Go API. The unchanged React frontend depends on the Go behaviour (decisions/0014).

## Decision
- One `@RestController` per Go handler file (`controllers/`), with the same method + path table as the Go `main.go`, using `@GetMapping("/api/boards/{id}")` and friends.
- **No Spring Security.** Its default filter chain adds CSRF protection, sessions, `WWW-Authenticate` headers and its own 401/403 bodies. Auth instead lives in `web/Auth.java`, where `requireUser` / `requireAdmin` are called as a handler's first statement. That's the same place Go wraps a handler in `RequireAuth(...)`, so 401 still comes before any 400.
- **Request bodies** are taken as `@RequestBody(required = false) byte[]` and decoded by `json/BodyDecoder` (strict, Go-equivalent). They're never bound to DTOs, because Jackson's data binding coerces types (`5` → `"5"`) and produces Spring's own 400 format.
- **Responses** are `ResponseEntity<byte[]>` built by `web/Responses`: compact JSON with Content-Type exactly `application/json`. No message converter reformats them or adds a charset.
- Defaults turned off or overridden (`application.properties`, `web/ErrorHandling.java`):
  - No whitelabel page, no `/error` controller (`ErrorMvcAutoConfiguration` excluded) and no static-resource handler.
  - Unmatched paths return Go's plain-text `404 page not found`, and wrong methods return `Method Not Allowed` (405) with an `Allow` header.
  - Spring 6 already doesn't match trailing slashes, so `/api/boards/` is a 404, as in Go.
  - Multipart is parsed lazily: the upload handler checks task ownership (404) before the body is parsed, the same order as Go.
  - Oversized or unparseable uploads return 413 `file_too_large`, and Tomcat's swallow limit is lifted so clients receive that 413 instead of a connection reset.
  - Any other exception is logged and returns 500 `internal_error`, the counterpart of Go's Recover middleware.
- Spring's per-request `Resolved [...]` warning log is silenced; request logging is Go-style (`RequestLogFilter`).

## Alternatives considered
- **Spring Security with a custom token filter:** idiomatic, but you spend as much configuration disabling behaviour as you gain.
- **DTOs with validation annotations:** idiomatic, but they give different status codes, error shapes and coercion rules than the contract requires.
- **Spring WebFlux:** no benefit for this workload, and blocking file I/O in the store would need extra care.

## Consequences
- Every route is plain Spring MVC and easy to read. A new route needs an explicit `auth.requireUser(req)` call and, if it takes a body, a `BodyDecoder` schema.
- The framework conventions that were deliberately bypassed (Spring Security, DTO binding, problem-details errors) are documented here and in the `java-contract-parity-api` skill, so they don't get "fixed" back in.
