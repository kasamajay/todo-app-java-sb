package io.todo.api.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reproduces Go's encoding/json output where the frontend or the on-disk data
 * files depend on it:
 * <ul>
 *   <li>time.Time marshals as RFC3339Nano - fractional seconds with trailing
 *       zeros trimmed (none at all when zero), "Z" for UTC, "+hh:mm"
 *       otherwise. The zero time "0001-01-01T00:00:00Z" is always emitted
 *       (encoding/json's omitempty never applies to structs).</li>
 *   <li>[]byte marshals as standard base64 (with padding); nil is null.</li>
 * </ul>
 * Java keeps nanosecond precision, so Go-written times round-trip exactly.
 */
public final class GoJson {

    public static final OffsetDateTime ZERO_TIME = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Like json.MarshalIndent(v, "", "  "): two-space indent, "key": value. */
    private static final ObjectWriter INDENTED = MAPPER.writer(new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private static final Pattern RFC3339 = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$");

    private GoJson() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static boolean isZero(OffsetDateTime t) {
        return t.isEqual(ZERO_TIME);
    }

    /** Format like Go's time.Time.MarshalJSON (RFC3339Nano). */
    public static String formatTime(OffsetDateTime t) {
        StringBuilder sb = new StringBuilder(35);
        sb.append(String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond()));
        int nanos = t.getNano();
        if (nanos != 0) {
            String frac = String.format("%09d", nanos);
            int end = frac.length();
            while (end > 0 && frac.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(frac, 0, end);
        }
        int offset = t.getOffset().getTotalSeconds();
        if (offset == 0) {
            sb.append('Z');
        } else {
            int abs = Math.abs(offset);
            sb.append(offset > 0 ? '+' : '-').append(String.format("%02d:%02d", abs / 3600, (abs % 3600) / 60));
        }
        return sb.toString();
    }

    /**
     * Parse an RFC3339 timestamp the way Go's time.Parse(time.RFC3339, ...)
     * does, including fractional seconds of any length.
     *
     * @throws IllegalArgumentException if raw isn't a valid RFC3339 timestamp
     */
    public static OffsetDateTime parseTime(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("timestamp is null");
        }
        Matcher m = RFC3339.matcher(raw);
        if (!m.matches()) {
            throw new IllegalArgumentException("not an RFC3339 timestamp: " + raw);
        }
        String frac = m.group(7) == null ? "" : m.group(7);
        int nanos = frac.isEmpty() ? 0 : Integer.parseInt((frac.length() > 9 ? frac.substring(0, 9) : frac + "000000000".substring(frac.length())));
        ZoneOffset zone;
        String tz = m.group(8);
        if (tz.equals("Z")) {
            zone = ZoneOffset.UTC;
        } else {
            int hh = Integer.parseInt(tz.substring(1, 3));
            int mm = Integer.parseInt(tz.substring(4, 6));
            if (hh > 23 || mm > 59) {
                throw new IllegalArgumentException("invalid time zone offset: " + tz);
            }
            int sign = tz.charAt(0) == '+' ? 1 : -1;
            zone = ZoneOffset.ofTotalSeconds(sign * (hh * 3600 + mm * 60));
        }
        try {
            return OffsetDateTime.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                    nanos, zone);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("invalid timestamp: " + raw, e);
        }
    }

    /** Reads an optional time field, treating a missing/empty value as the zero time. */
    public static OffsetDateTime timeField(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || v.asText().isEmpty()) {
            return ZERO_TIME;
        }
        return parseTime(v.asText());
    }

    public static String b64(byte[] b) {
        return b == null ? null : Base64.getEncoder().encodeToString(b);
    }

    public static byte[] unb64(JsonNode v) {
        return v == null || v.isNull() ? null : Base64.getDecoder().decode(v.asText());
    }

    public static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "" : v.asText();
    }

    public static ObjectNode object() {
        return NODES.objectNode();
    }

    public static ArrayNode array() {
        return NODES.arrayNode();
    }

    /** Compact JSON, like Go's json.Encoder (plus its trailing newline). */
    public static byte[] compact(JsonNode node) {
        try {
            return (MAPPER.writeValueAsString(node) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] indented(JsonNode node) {
        try {
            return INDENTED.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
