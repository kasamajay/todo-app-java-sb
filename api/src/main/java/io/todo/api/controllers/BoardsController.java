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
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

import static io.todo.api.json.BodyDecoder.Kind.STRING;

/** Board routes (Go: boards_handler.go). Ownership failures are 404, never 403. */
@RestController
public class BoardsController {

    private static final Map<String, BodyDecoder.Kind> SCHEMA =
            BodyDecoder.schema("name", STRING, "summary", STRING, "start_date", STRING);

    private final AppState state;
    private final Auth auth;

    public BoardsController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    @GetMapping("/api/boards")
    public ResponseEntity<byte[]> list(HttpServletRequest req) {
        User user = auth.requireUser(req);
        return Responses.jsonList(200, state.boards().listByUser(user.id), Board::toJson);
    }

    @PostMapping("/api/boards")
    public ResponseEntity<byte[]> create(HttpServletRequest req, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        var body = BodyDecoder.decode(raw, SCHEMA);
        if (body.str("name").isBlank()) {
            throw new ApiError(400, "invalid_name", "board name is required");
        }

        OffsetDateTime now = GoJson.now();
        Board board = new Board();
        board.id = Secrets.newId();
        board.userId = user.id;
        board.name = body.str("name");
        board.summary = body.str("summary");
        board.startDate = body.str("start_date");
        board.createdAt = now;
        board.updatedAt = now;
        state.boards().put(board.id, board);
        return Responses.json(201, board.toJson());
    }

    private Board ownedBoard(User user, String id) {
        Board board = state.boards().get(id);
        if (board == null || !board.userId.equals(user.id)) {
            throw ApiError.notFound("board not found");
        }
        return board;
    }

    @PutMapping("/api/boards/{id}")
    public ResponseEntity<byte[]> update(HttpServletRequest req, @PathVariable String id, @RequestBody(required = false) byte[] raw) {
        User user = auth.requireUser(req);
        Board board = ownedBoard(user, id);
        var body = BodyDecoder.decode(raw, SCHEMA);
        if (body.str("name").isBlank()) {
            throw new ApiError(400, "invalid_name", "board name is required");
        }

        board.name = body.str("name");
        board.summary = body.str("summary");
        board.startDate = body.str("start_date");
        board.updatedAt = GoJson.now();
        state.boards().put(board.id, board);
        return Responses.json(200, board.toJson());
    }

    @DeleteMapping("/api/boards/{id}")
    public ResponseEntity<byte[]> delete(HttpServletRequest req, @PathVariable String id) {
        User user = auth.requireUser(req);
        Board board = ownedBoard(user, id);

        // Cascade (decisions/0006): every task on this board and every
        // attachment on each of those tasks (metadata + blob), then the
        // board's labels.
        for (Task task : state.tasks().listByBoardAny(board.id)) {
            for (Attachment att : state.attachments().listByTask(task.id)) {
                state.attachments().deleteBlob(att.id);
                state.attachments().delete(att.id);
            }
            state.tasks().delete(task.id);
        }
        for (Label label : state.labels().listByBoardAny(board.id)) {
            state.labels().delete(label.id);
        }
        state.boards().delete(board.id);
        return Responses.noContent();
    }
}
