package io.todo.api.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A minimal JSON-file-backed collection store with atomic
 * (write-temp-then-rename) persistence - a port of todo-app's Go
 * internal/storage Store[T]. It keeps the full collection in memory behind a
 * lock and rewrites its file on every put/delete, so the app must run as a
 * single process (it always does: one JVM).
 *
 * <p>Entities go in and come out as copies, matching Go's value semantics:
 * mutating a fetched entity changes nothing until {@link #put} is called,
 * so a rejected request can never leave a half-applied change in memory.
 *
 * <p>The file format is identical to the Go/Python backends' ({id: entity},
 * keys sorted, two-space indent), so data directories are interchangeable.
 */
public class JsonStore<T> {

    private final Object lock = new Object();
    private final Path path;
    private final Function<T, ObjectNode> toJson;
    private final Function<T, T> copy;
    private final Map<String, T> data = new TreeMap<>();

    public JsonStore(Path path, Function<JsonNode, T> fromJson, Function<T, ObjectNode> toJson, Function<T, T> copy) {
        this.path = path;
        this.toJson = toJson;
        this.copy = copy;
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            persist();
            return;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (new String(raw, java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
            return;
        }
        try {
            JsonNode root = GoJson.MAPPER.readTree(raw);
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                data.put(e.getKey(), fromJson.apply(e.getValue()));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("parsing " + path + ": " + e.getMessage(), e);
        }
    }

    public T get(String id) {
        synchronized (lock) {
            T v = data.get(id);
            return v == null ? null : copy.apply(v);
        }
    }

    public List<T> list() {
        return find(v -> true);
    }

    public List<T> find(Predicate<T> pred) {
        synchronized (lock) {
            List<T> out = new ArrayList<>();
            for (T v : data.values()) {
                if (pred.test(v)) {
                    out.add(copy.apply(v));
                }
            }
            return out;
        }
    }

    /** Upsert v under id and persist atomically, rolling back the in-memory change if the write fails. */
    public void put(String id, T v) {
        synchronized (lock) {
            T prev = data.put(id, copy.apply(v));
            try {
                persist();
            } catch (RuntimeException e) {
                if (prev != null) {
                    data.put(id, prev);
                } else {
                    data.remove(id);
                }
                throw e;
            }
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            T prev = data.remove(id);
            if (prev == null) {
                return;
            }
            try {
                persist();
            } catch (RuntimeException e) {
                data.put(id, prev);
                throw e;
            }
        }
    }

    private void persist() {
        ObjectNode root = GoJson.object();
        data.forEach((id, v) -> root.set(id, toJson.apply(v)));
        writeFileAtomic(path, GoJson.indented(root));
    }

    /**
     * Write bytes via a temp file in the same directory, fsync, then an atomic
     * rename, so a crash mid-write never leaves a partially-written file.
     */
    public static void writeFileAtomic(Path path, byte[] content) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, path.getFileName() + ".tmp-", "");
            try {
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ch.write(java.nio.ByteBuffer.wrap(content));
                    ch.force(true);
                }
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
