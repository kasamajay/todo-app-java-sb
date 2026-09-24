package io.todo.api.web;

/**
 * An error response in the Go API's shape: {"error":{"code":...,"message":...}}.
 * Thrown from handlers and rendered by {@link ErrorHandling}.
 */
public class ApiError extends RuntimeException {

    private final int status;
    private final String code;

    public ApiError(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiError invalidBody() {
        return new ApiError(400, "invalid_body", "request body must be valid JSON");
    }

    public static ApiError unauthorized() {
        return new ApiError(401, "unauthorized", "missing or invalid token");
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, "not_found", message);
    }
}
