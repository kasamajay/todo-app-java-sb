package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.auth.Secrets;
import io.todo.api.json.BodyDecoder;
import io.todo.api.json.GoJson;
import io.todo.api.model.Attachment;
import io.todo.api.model.Board;
import io.todo.api.model.Label;
import io.todo.api.model.Task;
import io.todo.api.model.User;
import io.todo.api.web.ApiError;
import io.todo.api.web.Auth;
import io.todo.api.web.Responses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.todo.api.json.BodyDecoder.Kind.OPT_STRING;
import static io.todo.api.json.BodyDecoder.Kind.OPT_STRING_LIST;
import static io.todo.api.json.BodyDecoder.Kind.STRING;

/** Task routes (Go: tasks_handler.go). PUT is a partial update: absent or null fields are unchanged. */
@RestController
public class TasksController {

    private static final Map<String, BodyDecoder.Kind> SCHEMA = BodyDecoder.schema(
            "board_id", STRING,
            "title", OPT_STRING,
            "description", OPT_STRING,
            "status", OPT_STRING,
            "due_date", OPT_STRING,
            "label_ids", OPT_STRING_LIST);

    private final AppState state;
    private final Auth auth;

    public TasksController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    /**
     * An empty string means "no due date" (null); anything else must be a
     * valid RFC3339 timestamp or IllegalArgumentException is thrown.
     */
    public static OffsetDateTime parseDueDate(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        return GoJson.parseTime(raw);
    }

    private static OffsetDateTime dueDate(String raw) {
        try {
            return parseDueDate(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "invalid_due_date", "due date must be a valid RFC3339 timestamp");
        }
    }

    private static String status(String raw) {
        if (!Task.VALID_STATUSES.contains(raw)) {
            throw new ApiError(400, "invalid_status", "status must be todo, in_progress, or done");
        }
        return raw;
    }

    /** Every id must name a label belonging to boardId and userId. */
    private void validateLabelIds(List<String> ids, String boardId, String userId) {
        for (String id : ids) {
            Label label = state.labels().get(id);
            if (label == null || !label.boardId.equals(boardId) || !label.userId.equals(userId)) {
                throw new ApiError(400, "invalid_label", "one or more label ids are invalid for this board");
            }
        }
    }

    @GetMapping("/api/tasks")
    public ResponseEntity<byte[]> list(HttpServletRequest req,
                                       @RequestParam(name = "board_id", required = false, defaultValue = "") String boardId) {
        User user = auth.requireUser(req);
        List<Task> tasks = boardId.isEmpty() ? state.tasks().listByUser(user.id) : state.tasks().listByBoard(user.id, boardId);
        return Responses.jsonList(200, tasks, Task::toJson);
    }

    @PostMapping("/api/tasks")
    public ResponseEntity<byte[]> create(HttpServletRequest req, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        var body = BodyDecoder.decode(raw, SCHEMA);
        String title = body.optStr("title");
        if (title == null || title.isBlank()) {
            throw new ApiError(400, "invalid_title", "task title is required");
        }

        Board board = state.boards().get(body.str("board_id"));
        if (board == null || !board.userId.equals(user.id)) {
            throw new ApiError(400, "invalid_board", "board not found");
        }

        String status = body.optStr("status") != null ? status(body.optStr("status")) : Task.STATUS_TODO;
        String description = body.optStr("description") != null ? body.optStr("description") : "";
        OffsetDateTime due = body.optStr("due_date") != null ? dueDate(body.optStr("due_date")) : null;

        List<String> labelIds = new ArrayList<>();
        if (body.optList("label_ids") != null) {
            validateLabelIds(body.optList("label_ids"), board.id, user.id);
            labelIds = body.optList("label_ids");
        }

        OffsetDateTime now = GoJson.now();
        Task task = new Task();
        task.id = Secrets.newId();
        task.userId = user.id;
        task.boardId = board.id;
        task.title = title;
        task.description = description;
        task.status = status;
        task.dueDate = due;
        task.labelIds = labelIds;
        task.createdAt = now;
        task.updatedAt = now;
        state.tasks().put(task.id, task);
        return Responses.json(201, task.toJson());
    }

    private Task ownedTask(User user, String id) {
        Task task = state.tasks().get(id);
        if (task == null || !task.userId.equals(user.id)) {
            throw ApiError.notFound("task not found");
        }
        return task;
    }

    @PutMapping("/api/tasks/{id}")
    public ResponseEntity<byte[]> update(HttpServletRequest req, @PathVariable String id, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);
        var body = BodyDecoder.decode(raw, SCHEMA);

        // The task is a copy from the store, so a validation failure part-way
        // through leaves the stored task untouched (Go mutates a value copy
        // for the same effect).
        if (body.optStr("title") != null) {
            if (body.optStr("title").isBlank()) {
                throw new ApiError(400, "invalid_title", "task title cannot be empty");
            }
            task.title = body.optStr("title");
        }
        if (body.optStr("description") != null) {
            task.description = body.optStr("description");
        }
        if (body.optStr("status") != null) {
            task.status = status(body.optStr("status"));
        }
        if (body.optStr("due_date") != null) {
            task.dueDate = dueDate(body.optStr("due_date"));
        }
        if (body.optList("label_ids") != null) {
            // Validated against the task's current board - a request that both
            // reassigns the board and sets label_ids would validate against the
            // old board. Not reachable from the frontend (TaskForm never
            // reassigns boards); same documented edge case as the Go API.
            validateLabelIds(body.optList("label_ids"), task.boardId, user.id);
            task.labelIds = body.optList("label_ids");
        }
        String boardId = body.str("board_id");
        if (!boardId.isEmpty() && !boardId.equals(task.boardId)) {
            Board board = state.boards().get(boardId);
            if (board == null || !board.userId.equals(user.id)) {
                throw new ApiError(400, "invalid_board", "board not found");
            }
            task.boardId = board.id;
        }
        task.updatedAt = GoJson.now();

        state.tasks().put(task.id, task);
        return Responses.json(200, task.toJson());
    }

    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<byte[]> delete(HttpServletRequest req, @PathVariable String id) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);
        for (Attachment att : state.attachments().listByTask(task.id)) {
            state.attachments().deleteBlob(att.id);
            state.attachments().delete(att.id);
        }
        state.tasks().delete(task.id);
        return Responses.noContent();
    }
}
