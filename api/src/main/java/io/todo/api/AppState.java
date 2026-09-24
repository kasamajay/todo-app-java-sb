package io.todo.api;

import io.todo.api.auth.Passwords;
import io.todo.api.auth.Secrets;
import io.todo.api.config.AppConfig;
import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import io.todo.api.store.Stores.AttachmentStore;
import io.todo.api.store.Stores.BoardStore;
import io.todo.api.store.Stores.LabelStore;
import io.todo.api.store.Stores.TaskStore;
import io.todo.api.store.Stores.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The stores and the signing secret, loaded from the data directory at
 * startup (Go: the top of main.go). {@link #load} can re-point the whole
 * state at another directory; the test suite uses that to give every test a
 * fresh temp data dir without restarting Spring.
 */
@Component
public class AppState {

    static final Logger LOG = LoggerFactory.getLogger("todo-app");
    public static final String ADMIN_EMAIL = "admin@todo.io";

    private volatile Loaded loaded;

    public record Loaded(Path dataDir, byte[] secret, UserStore users, BoardStore boards, TaskStore tasks,
                         LabelStore labels, AttachmentStore attachments) {
    }

    public AppState(AppConfig config) {
        if (!config.googleConfigured()) {
            LOG.info("Google OAuth not configured (GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET unset) - Sign in with Google is disabled");
        }
        load(Path.of(config.dataDir()));
    }

    public synchronized void load(Path dataDir) {
        try {
            Files.createDirectories(dataDir.resolve("attachments"));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create data directory", e);
        }
        Loaded l = new Loaded(
                dataDir,
                Secrets.loadOrCreateSecret(dataDir),
                new UserStore(dataDir.resolve("users.json")),
                new BoardStore(dataDir.resolve("boards.json")),
                new TaskStore(dataDir.resolve("tasks.json")),
                new LabelStore(dataDir.resolve("labels.json")),
                new AttachmentStore(dataDir.resolve("attachments.json"), dataDir));
        bootstrapAdmin(l.users());
        this.loaded = l;
    }

    /**
     * Create admin@todo.io with a random password if no users exist yet,
     * logging the plaintext exactly once (it is never stored or shown again).
     */
    static void bootstrapAdmin(UserStore users) {
        if (!users.list().isEmpty()) {
            return;
        }
        String plaintext = Secrets.urlSafe(18);
        Passwords.Hashed h = Passwords.hash(plaintext);
        User admin = new User();
        admin.id = Secrets.newId();
        admin.email = ADMIN_EMAIL;
        admin.passwordHash = h.hash();
        admin.salt = h.salt();
        admin.isAdmin = true;
        admin.createdAt = GoJson.now();
        users.put(admin.id, admin);

        LOG.info("=====================================================");
        LOG.info("Bootstrapped admin account (shown only once):");
        LOG.info("  email:    {}", ADMIN_EMAIL);
        LOG.info("  password: {}", plaintext);
        LOG.info("=====================================================");
    }

    public Path dataDir() {
        return loaded.dataDir();
    }

    public byte[] secret() {
        return loaded.secret();
    }

    public UserStore users() {
        return loaded.users();
    }

    public BoardStore boards() {
        return loaded.boards();
    }

    public TaskStore tasks() {
        return loaded.tasks();
    }

    public LabelStore labels() {
        return loaded.labels();
    }

    public AttachmentStore attachments() {
        return loaded.attachments();
    }
}
