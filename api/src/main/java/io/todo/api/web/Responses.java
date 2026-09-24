package io.todo.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.todo.api.json.GoJson;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.function.Function;

/**
 * Response helpers matching Go's writeJSON/writeError: compact JSON, a
 * trailing newline, and Content-Type exactly "application/json" (no charset
 * parameter). Bodies are written as raw bytes so no Spring message converter
 * reformats them or adds a charset.
 */
public final class Responses {

    private Responses() {
    }

    public static ResponseEntity<byte[]> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(GoJson.compact(body));
    }

    public static <T> ResponseEntity<byte[]> jsonList(int status, Collection<T> items, Function<T, JsonNode> toJson) {
        ArrayNode arr = GoJson.array();
        for (T item : items) {
            arr.add(toJson.apply(item));
        }
        return json(status, arr);
    }

    public static ResponseEntity<byte[]> error(int status, String code, String message) {
        var err = GoJson.object();
        err.putObject("error").put("code", code).put("message", message);
        return json(status, err);
    }

    public static ResponseEntity<byte[]> noContent() {
        return ResponseEntity.status(204).build();
    }
}
