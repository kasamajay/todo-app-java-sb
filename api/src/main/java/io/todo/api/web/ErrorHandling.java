package io.todo.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;

/**
 * Maps every failure onto the Go API's responses, so no Spring default (the
 * whitelabel page, the JSON problem-detail body, ...) leaks into the contract:
 * ApiError to its {"error":...} body, unmatched routes/methods to Go
 * ServeMux's plain-text 404/405, oversized uploads to 413, and anything else
 * to a logged 500 (the counterpart of Go's Recover middleware).
 */
@RestControllerAdvice
public class ErrorHandling {

    private static final Logger LOG = LoggerFactory.getLogger("todo-app");

    @ExceptionHandler(ApiError.class)
    public ResponseEntity<byte[]> apiError(ApiError e) {
        return Responses.error(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<byte[]> notFound() {
        return plain(404, "404 page not found\n", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<byte[]> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String allow = e.getSupportedHttpMethods() == null ? null
                : String.join(", ", e.getSupportedHttpMethods().stream().map(Object::toString).sorted().toList());
        return plain(405, "Method Not Allowed\n", allow);
    }

    /** Oversized or unparseable multipart bodies: Go's handler reports every parse failure as 413. */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<byte[]> tooLarge() {
        return Responses.error(413, "file_too_large", "attachment exceeds the 10MB limit");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unhandled(HttpServletRequest req, Exception e) {
        LOG.error("panic handling {} {}: {}", req.getMethod(), req.getRequestURI(), e.toString(), e);
        return Responses.error(500, "internal_error", "internal server error");
    }

    private static ResponseEntity<byte[]> plain(int status, String body, String allow) {
        var b = ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .header("X-Content-Type-Options", "nosniff");
        if (allow != null) {
            b.header(HttpHeaders.ALLOW, allow);
        }
        return b.body(body.getBytes(StandardCharsets.UTF_8));
    }
}
