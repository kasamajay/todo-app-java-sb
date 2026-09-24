package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.auth.Secrets;
import io.todo.api.json.BodyDecoder;
import io.todo.api.json.GoJson;
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

import java.util.Map;

import static io.todo.api.json.BodyDecoder.Kind.STRING;

/** Label routes (Go: labels_handler.go). Labels are per-board, colour from a fixed palette. */
@RestController
public class LabelsController {

    private static final Map<String, BodyDecoder.Kind> SCHEMA =
            BodyDecoder.schema("board_id", STRING, "name", STRING, "color", STRING);

    private final AppState state;
    private final Auth auth;

    public LabelsController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    private static void validate(BodyDecoder.Decoded body) {
        if (body.str("name").isBlank()) {
            throw new ApiError(400, "invalid_name", "label name is required");
        }
        if (!Label.isValidColor(body.str("color"))) {
            throw new ApiError(400, "invalid_color", "color must be one of the allowed label colors");
        }
    }

    @GetMapping("/api/labels")
    public ResponseEntity<byte[]> list(HttpServletRequest req,
                                       @RequestParam(name = "board_id", required = false, defaultValue = "") String boardId) {
        User user = auth.requireUser(req);
        if (boardId.isEmpty()) {
            throw new ApiError(400, "invalid_board", "board_id is required");
        }
        Board board = state.boards().get(boardId);
        if (board == null || !board.userId.equals(user.id)) {
            throw new ApiError(400, "invalid_board", "board not found");
        }
        return Responses.jsonList(200, state.labels().listByBoard(user.id, boardId), Label::toJson);
    }

    @PostMapping("/api/labels")
    public ResponseEntity<byte[]> create(HttpServletRequest req, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        var body = BodyDecoder.decode(raw, SCHEMA);
        Board board = state.boards().get(body.str("board_id"));
        if (board == null || !board.userId.equals(user.id)) {
            throw new ApiError(400, "invalid_board", "board not found");
        }
        validate(body);

        Label label = new Label();
        label.id = Secrets.newId();
        label.userId = user.id;
        label.boardId = board.id;
        label.name = body.str("name");
        label.color = body.str("color");
        label.createdAt = GoJson.now();
        state.labels().put(label.id, label);
        return Responses.json(201, label.toJson());
    }

    private Label ownedLabel(User user, String id) {
        Label label = state.labels().get(id);
        if (label == null || !label.userId.equals(user.id)) {
            throw ApiError.notFound("label not found");
        }
        return label;
    }

    @PutMapping("/api/labels/{id}")
    public ResponseEntity<byte[]> update(HttpServletRequest req, @PathVariable String id, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        Label label = ownedLabel(user, id);
        var body = BodyDecoder.decode(raw, SCHEMA);
        validate(body);

        label.name = body.str("name");
        label.color = body.str("color");
        state.labels().put(label.id, label);
        return Responses.json(200, label.toJson());
    }

    @DeleteMapping("/api/labels/{id}")
    public ResponseEntity<byte[]> delete(HttpServletRequest req, @PathVariable String id) {
        User user = auth.requireUser(req);
        Label label = ownedLabel(user, id);

        // Cascade: strip this label from every task that references it rather
        // than deleting those tasks (decisions/0006 - no dangling references,
        // but the referencing resource itself shouldn't disappear).
        for (Task task : state.tasks().listByBoardAny(label.boardId)) {
            if (!task.labelIds.remove(label.id)) {
                continue;
            }
            task.updatedAt = GoJson.now();
            state.tasks().put(task.id, task);
        }
        state.labels().delete(label.id);
        return Responses.noContent();
    }
}
