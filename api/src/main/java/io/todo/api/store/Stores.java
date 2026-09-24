package io.todo.api.store;

import io.todo.api.model.Attachment;
import io.todo.api.model.Board;
import io.todo.api.model.Label;
import io.todo.api.model.Task;
import io.todo.api.model.User;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** The per-entity stores and their typed query helpers (Go: *_store.go). */
public final class Stores {

    private Stores() {
    }

    public static final class UserStore extends JsonStore<User> {
        public UserStore(Path path) {
            super(path, User::fromJson, User::toJson, User::copy);
        }

        public User findByEmail(String email) {
            String e = email.strip().toLowerCase();
            return find(u -> u.email.toLowerCase().equals(e)).stream().findFirst().orElse(null);
        }

        public User findByResetToken(String token) {
            if (token.isEmpty()) {
                return null;
            }
            return find(u -> u.resetToken.equals(token)).stream().findFirst().orElse(null);
        }

        public User findByGoogleId(String googleId) {
            if (googleId.isEmpty()) {
                return null;
            }
            return find(u -> u.googleId.equals(googleId)).stream().findFirst().orElse(null);
        }
    }

    public static final class BoardStore extends JsonStore<Board> {
        public BoardStore(Path path) {
            super(path, Board::fromJson, Board::toJson, Board::copy);
        }

        public List<Board> listByUser(String userId) {
            return find(b -> b.userId.equals(userId));
        }
    }

    public static final class TaskStore extends JsonStore<Task> {
        public TaskStore(Path path) {
            super(path, Task::fromJson, Task::toJson, Task::copy);
        }

        public List<Task> listByUser(String userId) {
            return find(t -> t.userId.equals(userId));
        }

        public List<Task> listByBoard(String userId, String boardId) {
            return find(t -> t.userId.equals(userId) && t.boardId.equals(boardId));
        }

        public List<Task> listByBoardAny(String boardId) {
            return find(t -> t.boardId.equals(boardId));
        }
    }

    public static final class LabelStore extends JsonStore<Label> {
        public LabelStore(Path path) {
            super(path, Label::fromJson, Label::toJson, Label::copy);
        }

        public List<Label> listByBoard(String userId, String boardId) {
            return find(l -> l.userId.equals(userId) && l.boardId.equals(boardId));
        }

        public List<Label> listByBoardAny(String boardId) {
            return find(l -> l.boardId.equals(boardId));
        }
    }

    public static final class AttachmentStore extends JsonStore<Attachment> {
        private final Path dataDir;

        public AttachmentStore(Path path, Path dataDir) {
            super(path, Attachment::fromJson, Attachment::toJson, Attachment::copy);
            this.dataDir = dataDir;
        }

        public List<Attachment> listByTask(String taskId) {
            return find(a -> a.taskId.equals(taskId));
        }

        public Path blobPath(String attachmentId) {
            return dataDir.resolve("attachments").resolve(attachmentId);
        }

        public void writeBlob(String attachmentId, byte[] content) {
            writeFileAtomic(blobPath(attachmentId), content);
        }

        public byte[] readBlob(String attachmentId) throws IOException {
            return Files.readAllBytes(blobPath(attachmentId));
        }

        public void deleteBlob(String attachmentId) {
            try {
                Files.delete(blobPath(attachmentId));
            } catch (NoSuchFileException e) {
                // already gone - same as Go's DeleteBlob treating IsNotExist as success
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
