package io.todo.api.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import io.todo.api.web.ApiError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON request-body decoding that behaves like the Go API's decodeJSON
 * (json.Decoder with DisallowUnknownFields decoding into a struct):
 * <ul>
 *   <li>the body must start with one JSON value - anything after it is
 *       ignored, as json.Decoder.Decode only reads the first value;
 *       empty/invalid is an error</li>
 *   <li>the value must be an object, or null (which Go decodes as "no fields")</li>
 *   <li>unknown fields are rejected; names match case-insensitively, as
 *       encoding/json does</li>
 *   <li>each field's JSON type must match its kind exactly (no coercion); a
 *       JSON null leaves a plain field at its zero value and a pointer field
 *       unset (null = "not provided")</li>
 * </ul>
 * Every violation is ApiError 400 invalid_body, the response every Go handler
 * returns for a decode error. Jackson is used only as a tokenizer here -
 * never for data binding, whose coercion rules differ from Go's.
 */
public final class BodyDecoder {

    /** Field kinds, named after the Go field types they mirror. */
    public enum Kind {
        /** Go string - null decodes to "". */
        STRING,
        /** Go bool - null decodes to false. */
        BOOL,
        /** Go *string - null/absent decodes to Java null ("not provided"). */
        OPT_STRING,
        /** Go *[]string - null/absent decodes to Java null ("not provided"). */
        OPT_STRING_LIST
    }

    private BodyDecoder() {
    }

    /** A schema with fields in declaration order, e.g. schema("email", STRING, "password", STRING). */
    public static Map<String, Kind> schema(Object... pairs) {
        Map<String, Kind> s = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            s.put((String) pairs[i], (Kind) pairs[i + 1]);
        }
        return s;
    }

    public static Decoded decode(byte[] raw, Map<String, Kind> schema) {
        JsonNode value;
        try (JsonParser p = GoJson.MAPPER.createParser(raw == null ? new byte[0] : raw)) {
            JsonToken first = p.nextToken();
            if (first == null) {
                throw ApiError.invalidBody();
            }
            value = p.readValueAsTree();
        } catch (IOException e) {
            throw ApiError.invalidBody();
        }

        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Kind> f : schema.entrySet()) {
            out.put(f.getKey(), zero(f.getValue()));
        }
        if (value == null || value.isNull()) {
            return new Decoded(out);
        }
        if (!value.isObject()) {
            throw ApiError.invalidBody();
        }

        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String name = resolve(e.getKey(), schema);
            if (name == null) {
                throw ApiError.invalidBody(); // DisallowUnknownFields
            }
            out.put(name, convert(schema.get(name), e.getValue()));
        }
        return new Decoded(out);
    }

    private static String resolve(String key, Map<String, Kind> schema) {
        if (schema.containsKey(key)) {
            return key;
        }
        for (String name : schema.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return name;
            }
        }
        return null;
    }

    private static Object zero(Kind kind) {
        return switch (kind) {
            case STRING -> "";
            case BOOL -> Boolean.FALSE;
            case OPT_STRING, OPT_STRING_LIST -> null;
        };
    }

    private static Object convert(Kind kind, JsonNode v) {
        if (v.isNull()) {
            return zero(kind);
        }
        switch (kind) {
            case STRING, OPT_STRING -> {
                if (!v.isTextual()) {
                    throw ApiError.invalidBody();
                }
                return v.textValue();
            }
            case BOOL -> {
                if (!v.isBoolean()) {
                    throw ApiError.invalidBody();
                }
                return v.booleanValue();
            }
            case OPT_STRING_LIST -> {
                if (!v.isArray()) {
                    throw ApiError.invalidBody();
                }
                List<String> list = new ArrayList<>();
                for (JsonNode item : v) {
                    if (item.isNull()) {
                        list.add("");
                    } else if (item.isTextual()) {
                        list.add(item.textValue());
                    } else {
                        throw ApiError.invalidBody();
                    }
                }
                return list;
            }
            default -> throw new IllegalStateException("unknown kind " + kind);
        }
    }

    /** A decoded body: typed accessors over the schema's fields. */
    public record Decoded(Map<String, Object> values) {
        public String str(String name) {
            return (String) values.get(name);
        }

        public boolean bool(String name) {
            return (Boolean) values.get(name);
        }

        /** A pointer field: null means "not provided". */
        public String optStr(String name) {
            return (String) values.get(name);
        }

        @SuppressWarnings("unchecked")
        public List<String> optList(String name) {
            return (List<String>) values.get(name);
        }
    }
}
