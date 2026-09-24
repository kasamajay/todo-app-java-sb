package io.todo.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.todo.api.auth.Passwords;
import io.todo.api.auth.Secrets;
import io.todo.api.auth.Tokens;
import io.todo.api.json.GoJson;
import io.todo.api.model.Board;
import io.todo.api.model.Label;
import io.todo.api.model.Task;
import io.todo.api.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Every test runs against the real embedded Tomcat (multipart limits, 404/405
 * handling and headers only behave faithfully there) with a fresh temp data
 * dir - the counterpart of the Go tests' t.TempDir() stores.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "todo.data-dir=${java.io.tmpdir}/todo-api-test-boot")
public abstract class ApiTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected AppState state;

    @TempDir
    protected Path dataDir;

    protected final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void freshData() {
        state.load(dataDir);
    }

    // --- HTTP ---------------------------------------------------------------

    public record Res(int status, HttpHeaders headers, byte[] body) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return GoJson.MAPPER.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("not JSON: " + text(), e);
            }
        }

        public String errorCode() {
            return json().path("error").path("code").asText();
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        /** Top-level field names in order. */
        public List<String> keys() {
            List<String> out = new ArrayList<>();
            json().fieldNames().forEachRemaining(out::add);
            return out;
        }
    }

    protected Res send(String method, String path, byte[] body, String contentType, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Res(r.statusCode(), r.headers(), r.body());
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    protected Res get(String path, String... headers) {
        return send("GET", path, null, null, headers);
    }

    protected Res delete(String path, String... headers) {
        return send("DELETE", path, null, null, headers);
    }

    protected Res postJson(String path, String json, String... headers) {
        return send("POST", path, json.getBytes(StandardCharsets.UTF_8), "application/json", headers);
    }

    protected Res putJson(String path, String json, String... headers) {
        return send("PUT", path, json.getBytes(StandardCharsets.UTF_8), "application/json", headers);
    }

    /** A multipart upload with one file part (partType null = no Content-Type on the part). */
    protected Res upload(String path, String field, String filename, byte[] content, String partType, String... headers) {
        String boundary = "----todoTestBoundary" + Secrets.newId();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Consumer<String> w = s -> out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        w.accept("--" + boundary + "\r\n");
        w.accept("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n");
        if (partType != null) {
            w.accept("Content-Type: " + partType + "\r\n");
        }
        w.accept("\r\n");
        out.writeBytes(content);
        w.accept("\r\n--" + boundary + "--\r\n");
        return send("POST", path, out.toByteArray(), "multipart/form-data; boundary=" + boundary, headers);
    }

    // --- seeding ------------------------------------------------------------

    protected User seedUser(String email, String password, Consumer<User> customize) {
        User u = new User();
        u.id = Secrets.newId();
        u.email = email;
        u.createdAt = GoJson.now();
        if (password != null) {
            Passwords.Hashed h = Passwords.hash(password);
            u.passwordHash = h.hash();
            u.salt = h.salt();
        }
        if (customize != null) {
            customize.accept(u);
        }
        state.users().put(u.id, u);
        return u;
    }

    protected User seedUser(String email) {
        return seedUser(email, null, null);
    }

    protected String[] auth(User user) {
        String token = Tokens.mint(state.secret(), new Tokens.Claims(user.id, user.isAdmin, GoJson.now().plusHours(1)));
        return new String[]{"Authorization", "Bearer " + token};
    }

    protected Board seedBoard(User user, String name) {
        Board b = new Board();
        b.id = Secrets.newId();
        b.userId = user.id;
        b.name = name;
        b.createdAt = GoJson.now();
        b.updatedAt = b.createdAt;
        state.boards().put(b.id, b);
        return b;
    }

    protected Label seedLabel(User user, Board board, String name) {
        Label l = new Label();
        l.id = Secrets.newId();
        l.userId = user.id;
        l.boardId = board.id;
        l.name = name;
        l.color = "#6366f1";
        l.createdAt = GoJson.now();
        state.labels().put(l.id, l);
        return l;
    }

    protected Task seedTask(User user, Board board, String title, String... labelIds) {
        Task t = new Task();
        t.id = Secrets.newId();
        t.userId = user.id;
        t.boardId = board.id;
        t.title = title;
        t.labelIds = new ArrayList<>(List.of(labelIds));
        t.createdAt = GoJson.now();
        t.updatedAt = t.createdAt;
        state.tasks().put(t.id, t);
        return t;
    }
}
