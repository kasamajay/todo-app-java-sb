package io.todo.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.todo.api.auth.Passwords;
import io.todo.api.json.GoJson;
import io.todo.api.model.Board;
import io.todo.api.model.Task;
import io.todo.api.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract-parity tests: wire behaviour the Go API has that the unchanged
 * React frontend or a shared data directory relies on (mirrors todo-app-py's
 * tests/test_parity.py).
 */
class ParityTest extends ApiTestBase {

    private static final String INVALID_BODY = "{\"error\":{\"code\":\"invalid_body\",\"message\":\"request body must be valid JSON\"}}\n";

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "\"str\"", "{\"email\":\"a@b.c\",\"password\":\"password123\",\"extra\":1}",
            "{\"email\":5,\"password\":\"password123\"}"})
    void strictBodyDecoding(String raw) {
        Res r = send("POST", "/api/auth/register", raw.getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text()).isEqualTo(INVALID_BODY);
    }

    @Test
    void noBodyAtAllIsInvalidBody() {
        assertThat(send("POST", "/api/auth/login", null, null).text()).isEqualTo(INVALID_BODY);
    }

    @Test
    void bodyDecodingGoLeniencies() {
        assertThat(postJson("/api/auth/register", "null").errorCode()).isEqualTo("invalid_email");
        assertThat(postJson("/api/auth/register", "{\"EMAIL\":\"x@y.z\",\"Password\":\"password123\"} trailing").status()).isEqualTo(201);
    }

    @Test
    void unknownRoutesAndMethodsMatchGoServeMux() {
        Res notFound = get("/api/nope");
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.text()).isEqualTo("404 page not found\n");
        assertThat(get("/api/boards/").status()).isEqualTo(404); // no trailing-slash match
        assertThat(get("/").text()).isEqualTo("404 page not found\n");
        Res notAllowed = send("PATCH", "/api/boards", null, null);
        assertThat(notAllowed.status()).isEqualTo(405);
        assertThat(notAllowed.text()).isEqualTo("Method Not Allowed\n");
        assertThat(get("/error").status()).isEqualTo(404);
        assertThat(get("/actuator/health").status()).isEqualTo(404);
    }

    @Test
    void attachmentRoundTripLimitsAndCascade() throws Exception {
        User alice = seedUser("alice@example.com");
        User bob = seedUser("bob@example.com");
        Task task = seedTask(alice, seedBoard(alice, "B"), "T");
        String base = "/api/tasks/" + task.id + "/attachments";

        Res r = upload(base, "file", "../notes.txt", "hello world".getBytes(), "text/plain", auth(alice));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.keys()).containsExactly("id", "task_id", "user_id", "filename", "content_type", "size_bytes", "created_at");
        JsonNode att = r.json();
        assertThat(att.get("filename").asText()).isEqualTo("notes.txt");
        assertThat(att.get("content_type").asText()).isEqualTo("text/plain");
        assertThat(att.get("size_bytes").asInt()).isEqualTo(11);
        String aid = att.get("id").asText();

        Res dl = get(base + "/" + aid, auth(alice));
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.text()).isEqualTo("hello world");
        assertThat(dl.header("content-type")).isEqualTo("text/plain");
        assertThat(dl.header("content-disposition")).isEqualTo("inline; filename=\"notes.txt\"");
        assertThat(dl.header("content-length")).isEqualTo("11");

        assertThat(get(base, auth(bob)).status()).isEqualTo(404);
        assertThat(get(base + "/missing", auth(alice)).json().at("/error/message").asText()).isEqualTo("attachment not found");
        Res noFile = upload(base, "other", "a.txt", "x".getBytes(), null, auth(alice));
        assertThat(noFile.status()).isEqualTo(400);
        assertThat(noFile.errorCode()).isEqualTo("invalid_file");
        assertThat(send("POST", base, "raw".getBytes(), "text/plain", auth(alice)).errorCode()).isEqualTo("file_too_large");

        int limit = 10 * 1024 * 1024;
        assertThat(upload(base, "file", "ok.bin", new byte[limit], "application/octet-stream", auth(alice)).status()).isEqualTo(201);
        Res big = upload(base, "file", "big.bin", new byte[limit + 1], "application/octet-stream", auth(alice));
        assertThat(big.status()).isEqualTo(413);
        assertThat(big.text()).isEqualTo("{\"error\":{\"code\":\"file_too_large\",\"message\":\"attachment exceeds the 10MB limit\"}}\n");

        var blob = state.attachments().blobPath(aid);
        assertThat(Files.exists(blob)).isTrue();
        assertThat(delete("/api/tasks/" + task.id, auth(alice)).status()).isEqualTo(204);
        assertThat(state.attachments().listByTask(task.id)).isEmpty();
        assertThat(Files.exists(blob)).isFalse();
    }

    @Test
    void attachmentContentTypeSniffedAndDelete() {
        User alice = seedUser("alice@example.com");
        Task task = seedTask(alice, seedBoard(alice, "B"), "T");
        String base = "/api/tasks/" + task.id + "/attachments";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'x', 'x'};
        Res r = upload(base, "file", "a.png", png, null, auth(alice));
        assertThat(r.json().get("content_type").asText()).isEqualTo("image/png");
        assertThat(delete(base + "/" + r.json().get("id").asText(), auth(alice)).status()).isEqualTo(204);
        assertThat(get(base, auth(alice)).text()).isEqualTo("[]\n");
    }

    @Test
    void uploadToSomeoneElsesTaskIs404EvenWhenOversized() {
        User alice = seedUser("alice@example.com");
        User bob = seedUser("bob@example.com");
        Task task = seedTask(alice, seedBoard(alice, "B"), "T");
        Res r = upload("/api/tasks/" + task.id + "/attachments", "file", "big.bin", new byte[11 * 1024 * 1024], null, auth(bob));
        assertThat(r.status()).isEqualTo(404);
    }

    /**
     * users.json / tasks.json exactly as the Go backend writes them
     * (nanosecond times, base64 []byte, null hash for a Google-only account,
     * omitted empty fields) load, serve, and round-trip.
     */
    @Test
    void readsAndServesGoWrittenData() throws Exception {
        Passwords.Hashed h = Passwords.hash("gopassword");
        String users = """
                {
                  "u1": {"id":"u1","email":"go@example.com","password_hash":"%s","salt":"%s","is_admin":true,
                         "failed_login_count":0,"locked_until":"0001-01-01T00:00:00Z","reset_token_expires":"0001-01-01T00:00:00Z",
                         "two_factor_enabled":false,"two_factor_code_expires":"0001-01-01T00:00:00Z","created_at":"2026-09-22T10:00:00.123456789Z"},
                  "u2": {"id":"u2","email":"g@example.com","password_hash":null,"salt":null,"google_id":"sub","is_admin":false,
                         "failed_login_count":0,"locked_until":"0001-01-01T00:00:00Z","reset_token_expires":"0001-01-01T00:00:00Z",
                         "two_factor_enabled":false,"two_factor_code_expires":"0001-01-01T00:00:00Z","created_at":"2026-09-22T10:00:00Z"}
                }""".formatted(Base64.getEncoder().encodeToString(h.hash()), Base64.getEncoder().encodeToString(h.salt()));
        String tasks = """
                {"t1":{"id":"t1","user_id":"u1","board_id":"b1","title":"Go task","description":"","status":"done",
                       "due_date":"2026-10-01T00:00:00Z","created_at":"2026-09-22T10:00:00.5Z","updated_at":"2026-09-22T10:00:00.5Z"}}""";
        var dir = Files.createTempDirectory(dataDir, "go");
        Files.writeString(dir.resolve("users.json"), users);
        Files.writeString(dir.resolve("tasks.json"), tasks);
        state.load(dir);

        assertThat(state.users().list()).hasSize(2); // no bootstrap admin: users exist
        Res login = postJson("/api/auth/login", "{\"email\":\"go@example.com\",\"password\":\"gopassword\"}");
        assertThat(login.status()).isEqualTo(200);
        Res list = get("/api/tasks", "Authorization", "Bearer " + login.json().get("token").asText());
        assertThat(list.text()).isEqualTo("[{\"id\":\"t1\",\"user_id\":\"u1\",\"board_id\":\"b1\",\"title\":\"Go task\",\"description\":\"\","
                + "\"status\":\"done\",\"due_date\":\"2026-10-01T00:00:00Z\",\"created_at\":\"2026-09-22T10:00:00.5Z\","
                + "\"updated_at\":\"2026-09-22T10:00:00.5Z\"}]\n");
        assertThat(postJson("/api/auth/login", "{\"email\":\"g@example.com\",\"password\":\"xxxxxxxx\"}").status()).isEqualTo(401);

        // Rewritten file keeps Go's shape: null hash, omitted empty strings, sorted ids.
        state.users().put("u2", state.users().get("u2"));
        JsonNode written = GoJson.MAPPER.readTree(Files.readAllBytes(dir.resolve("users.json")));
        assertThat(written.fieldNames()).toIterable().containsExactly("u1", "u2");
        assertThat(written.get("u2").get("password_hash").isNull()).isTrue();
        assertThat(written.get("u2").has("reset_token")).isFalse();
        assertThat(written.get("u1").get("created_at").asText()).isEqualTo("2026-09-22T10:00:00.123456789Z");
    }

    @Test
    void storeHandsOutCopies() {
        Board b = seedBoard(seedUser("a@example.com"), "Original");
        Board fetched = state.boards().get(b.id);
        fetched.name = "Mutated but never put";
        assertThat(state.boards().get(b.id).name).isEqualTo("Original");
    }
}
