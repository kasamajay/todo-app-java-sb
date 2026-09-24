package io.todo.api.json;

import io.todo.api.web.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.todo.api.json.BodyDecoder.Kind.BOOL;
import static io.todo.api.json.BodyDecoder.Kind.OPT_STRING;
import static io.todo.api.json.BodyDecoder.Kind.OPT_STRING_LIST;
import static io.todo.api.json.BodyDecoder.Kind.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Go encoding/json compatibility of the time format and body decoding. */
class JsonFormatTest {

    @Test
    void timeFormatMatchesGoRfc3339Nano() {
        assertThat(GoJson.formatTime(GoJson.ZERO_TIME)).isEqualTo("0001-01-01T00:00:00Z");
        // Java keeps Go's nanoseconds exactly.
        assertThat(GoJson.formatTime(GoJson.parseTime("2026-09-24T13:00:42.338551558Z"))).isEqualTo("2026-09-24T13:00:42.338551558Z");
        assertThat(GoJson.formatTime(GoJson.parseTime("2026-09-24T13:00:42.500000Z"))).isEqualTo("2026-09-24T13:00:42.5Z");
        assertThat(GoJson.formatTime(GoJson.parseTime("2026-09-24T13:00:42+05:30"))).isEqualTo("2026-09-24T13:00:42+05:30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01", "not-a-date", "2026-13-01T00:00:00Z", "2026-10-01 00:00:00Z", "2026-02-30T00:00:00Z"})
    void parseTimeRejectsNonRfc3339(String raw) {
        assertThatThrownBy(() -> GoJson.parseTime(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    private static BodyDecoder.Decoded decode(String raw) {
        return BodyDecoder.decode(raw.getBytes(StandardCharsets.UTF_8),
                BodyDecoder.schema("name", STRING, "on", BOOL, "title", OPT_STRING, "ids", OPT_STRING_LIST));
    }

    @Test
    void decodesLikeGo() {
        var d = decode("{\"NAME\":\"x\",\"on\":true,\"ids\":[\"a\",null]} trailing ignored");
        assertThat(d.str("name")).isEqualTo("x");
        assertThat(d.bool("on")).isTrue();
        assertThat(d.optStr("title")).isNull();
        assertThat(d.optList("ids")).isEqualTo(List.of("a", ""));

        var nulls = decode("{\"name\":null,\"title\":null,\"ids\":null}");
        assertThat(nulls.str("name")).isEmpty();
        assertThat(nulls.optStr("title")).isNull();
        assertThat(nulls.optList("ids")).isNull();
        assertThat(decode("null").str("name")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "{", "[]", "\"s\"", "5", "{\"bogus\":1}", "{\"name\":5}", "{\"on\":\"true\"}",
            "{\"ids\":\"a\"}", "{\"ids\":[1]}", "{\"title\":false}"})
    void rejectsWhatGoRejects(String raw) {
        assertThatThrownBy(() -> decode(raw)).isInstanceOfSatisfying(ApiError.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("invalid_body");
        });
    }
}
