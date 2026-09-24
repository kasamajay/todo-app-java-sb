package io.todo.api;

import io.todo.api.controllers.TasksController;
import io.todo.api.model.Board;
import io.todo.api.model.Label;
import io.todo.api.model.Task;
import io.todo.api.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports of todo-app handlers/{boards,labels,tasks}_handler_test.go, plus
 * board/task CRUD parity checks.
 */
class BoardsLabelsTasksTest extends ApiTestBase {

    private User alice;
    private User bob;
    private Board board;

    @BeforeEach
    void seed() {
        alice = seedUser("alice@example.com");
        bob = seedUser("bob@example.com");
        board = seedBoard(alice, "Board");
    }

    private static List<String> ids(Res r) {
        List<String> out = new ArrayList<>();
        r.json().forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    // --- boards_handler_test.go ------------------------------------------------------

    @Test
    void boardsDeleteCascadesToLabels() {
        Board other = seedBoard(alice, "Other");
        Label l1 = seedLabel(alice, board, "One");
        Label l2 = seedLabel(alice, board, "Two");
        Label keep = seedLabel(alice, other, "Keep");
        Task task = seedTask(alice, board, "T", l1.id);

        Res r = delete("/api/boards/" + board.id, auth(alice));
        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(state.boards().get(board.id)).isNull();
        assertThat(state.tasks().get(task.id)).isNull();
        assertThat(state.labels().get(l1.id)).isNull();
        assertThat(state.labels().get(l2.id)).isNull();
        assertThat(state.labels().get(keep.id)).isNotNull();
    }

    @Test
    void boardCrudAndOwnership() {
        Res r = postJson("/api/boards", "{\"name\":\"Work\",\"summary\":\"s\",\"start_date\":\"2026-09-01\"}", auth(alice));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.keys()).containsExactly("id", "user_id", "name", "summary", "start_date", "created_at", "updated_at");
        String id = r.json().get("id").asText();
        assertThat(postJson("/api/boards", "{\"name\":\"  \"}", auth(alice)).errorCode()).isEqualTo("invalid_name");
        assertThat(ids(get("/api/boards", auth(alice)))).containsExactlyInAnyOrder(board.id, id);
        assertThat(get("/api/boards", auth(bob)).text()).isEqualTo("[]\n");

        Res put = putJson("/api/boards/" + id, "{\"name\":\"x\"}", auth(bob));
        assertThat(put.status()).isEqualTo(404);
        assertThat(put.text()).isEqualTo("{\"error\":{\"code\":\"not_found\",\"message\":\"board not found\"}}\n");
        assertThat(delete("/api/boards/" + id, auth(bob)).status()).isEqualTo(404);

        // PUT replaces all fields (Go decodes into a fresh boardRequest).
        Res renamed = putJson("/api/boards/" + id, "{\"name\":\"Renamed\"}", auth(alice));
        assertThat(renamed.json().get("name").asText()).isEqualTo("Renamed");
        assertThat(renamed.json().get("summary").asText()).isEmpty();
    }

    // --- labels_handler_test.go -----------------------------------------------------

    @Test
    void labelsListRequiresBoardId() {
        Res r = get("/api/labels", auth(alice));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_board");
        assertThat(r.json().at("/error/message").asText()).isEqualTo("board_id is required");
    }

    @Test
    void labelsListRejectsBoardNotOwnedByCaller() {
        Res r = get("/api/labels?board_id=" + board.id, auth(bob));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_board");
    }

    @Test
    void labelsListReturnsOnlyThatBoardsLabels() {
        Label mine = seedLabel(alice, board, "Mine");
        seedLabel(alice, seedBoard(alice, "Other"), "Theirs");
        assertThat(ids(get("/api/labels?board_id=" + board.id, auth(alice)))).containsExactly(mine.id);
    }

    @Test
    void labelsCreateSuccess() {
        Res r = postJson("/api/labels", "{\"board_id\":\"" + board.id + "\",\"name\":\"Bug\",\"color\":\"#ef4444\"}", auth(alice));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.keys()).containsExactly("id", "user_id", "board_id", "name", "color", "created_at");
        assertThat(r.json().get("color").asText()).isEqualTo("#ef4444");
        assertThat(state.labels().get(r.json().get("id").asText())).isNotNull();
    }

    @Test
    void labelsCreateRejectsEmptyName() {
        Res r = postJson("/api/labels", "{\"board_id\":\"" + board.id + "\",\"name\":\" \",\"color\":\"#ef4444\"}", auth(alice));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_name");
    }

    @Test
    void labelsCreateRejectsInvalidColor() {
        Res r = postJson("/api/labels", "{\"board_id\":\"" + board.id + "\",\"name\":\"Bug\",\"color\":\"#123456\"}", auth(alice));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_color");
    }

    @Test
    void labelsCreateRejectsBoardNotOwned() {
        Res r = postJson("/api/labels", "{\"board_id\":\"" + board.id + "\",\"name\":\"Bug\",\"color\":\"#ef4444\"}", auth(bob));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_board");
    }

    @Test
    void labelsUpdateSuccess() {
        Label label = seedLabel(alice, board, "L");
        Res r = putJson("/api/labels/" + label.id, "{\"name\":\"Renamed\",\"color\":\"#16a34a\"}", auth(alice));
        assertThat(r.status()).isEqualTo(200);
        assertThat(state.labels().get(label.id).name).isEqualTo("Renamed");
        assertThat(state.labels().get(label.id).color).isEqualTo("#16a34a");
    }

    @Test
    void labelsUpdateNotFoundOrNotOwned() {
        Label label = seedLabel(alice, board, "L");
        assertThat(putJson("/api/labels/" + label.id, "{\"name\":\"x\",\"color\":\"#16a34a\"}", auth(bob)).status()).isEqualTo(404);
        assertThat(putJson("/api/labels/missing", "{\"name\":\"x\",\"color\":\"#16a34a\"}", auth(alice)).errorCode()).isEqualTo("not_found");
    }

    @Test
    void labelsUpdateRejectsInvalidColor() {
        Label label = seedLabel(alice, board, "L");
        assertThat(putJson("/api/labels/" + label.id, "{\"name\":\"x\",\"color\":\"red\"}", auth(alice)).errorCode()).isEqualTo("invalid_color");
        assertThat(state.labels().get(label.id).color).isEqualTo("#6366f1");
    }

    @Test
    void labelsDeleteSuccessStripsFromReferencingTasks() {
        Label doomed = seedLabel(alice, board, "Doomed");
        Label keep = seedLabel(alice, board, "Keep");
        Task t1 = seedTask(alice, board, "1", doomed.id, keep.id);
        Task t2 = seedTask(alice, board, "2", doomed.id);
        Task t3 = seedTask(alice, board, "3");

        assertThat(delete("/api/labels/" + doomed.id, auth(alice)).status()).isEqualTo(204);
        assertThat(state.labels().get(doomed.id)).isNull();
        assertThat(state.tasks().get(t1.id).labelIds).containsExactly(keep.id);
        assertThat(state.tasks().get(t2.id).labelIds).isEmpty();
        assertThat(state.tasks().get(t3.id)).isNotNull();
    }

    @Test
    void labelsDeleteNotFoundOrNotOwned() {
        Label label = seedLabel(alice, board, "L");
        assertThat(delete("/api/labels/" + label.id, auth(bob)).status()).isEqualTo(404);
        assertThat(delete("/api/labels/missing", auth(alice)).status()).isEqualTo(404);
        assertThat(state.labels().get(label.id)).isNotNull();
    }

    @Test
    void labelsDeleteLeavesOtherBoardsTasksUntouched() {
        Board other = seedBoard(alice, "Other");
        Label label = seedLabel(alice, board, "L");
        Task otherTask = seedTask(alice, other, "T", label.id);
        OffsetDateTime before = state.tasks().get(otherTask.id).updatedAt;

        assertThat(delete("/api/labels/" + label.id, auth(alice)).status()).isEqualTo(204);
        Task after = state.tasks().get(otherTask.id);
        assertThat(after.labelIds).containsExactly(label.id);
        assertThat(after.updatedAt).isEqualTo(before);
    }

    // --- tasks_handler_test.go --------------------------------------------------------

    @Test
    void parseDueDate() {
        assertThat(TasksController.parseDueDate("")).isNull();
        assertThat(TasksController.parseDueDate("   ")).isNull();
        assertThat(TasksController.parseDueDate("2026-10-01T00:00:00Z")).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(TasksController.parseDueDate("2026-10-01T12:30:45.123456789Z").getNano()).isEqualTo(123456789);
        for (String bad : new String[]{"2026-10-01", "not-a-date", "2026-13-01T00:00:00Z"}) {
            assertThatThrownBy(() -> TasksController.parseDueDate(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Res createTask(User user, String json) {
        return postJson("/api/tasks", json, auth(user));
    }

    @Test
    void tasksCreateAcceptsValidLabelIds() {
        Label l1 = seedLabel(alice, board, "1");
        Label l2 = seedLabel(alice, board, "2");
        Res r = createTask(alice, "{\"board_id\":\"" + board.id + "\",\"title\":\"T\",\"label_ids\":[\"" + l1.id + "\",\"" + l2.id + "\"]}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("label_ids").toString()).isEqualTo("[\"" + l1.id + "\",\"" + l2.id + "\"]");
    }

    @Test
    void tasksCreateRejectsLabelFromAnotherBoard() {
        Label other = seedLabel(alice, seedBoard(alice, "Other"), "L");
        Res r = createTask(alice, "{\"board_id\":\"" + board.id + "\",\"title\":\"T\",\"label_ids\":[\"" + other.id + "\"]}");
        assertThat(r.errorCode()).isEqualTo("invalid_label");
    }

    @Test
    void tasksCreateRejectsLabelFromAnotherUser() {
        Label bobs = seedLabel(bob, board, "L");
        Res r = createTask(alice, "{\"board_id\":\"" + board.id + "\",\"title\":\"T\",\"label_ids\":[\"" + bobs.id + "\"]}");
        assertThat(r.errorCode()).isEqualTo("invalid_label");
    }

    @Test
    void tasksCreateRejectsUnknownLabelId() {
        Res r = createTask(alice, "{\"board_id\":\"" + board.id + "\",\"title\":\"T\",\"label_ids\":[\"nope\"]}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.errorCode()).isEqualTo("invalid_label");
    }

    @Test
    void tasksCreateOmittedLabelIdsDefaultsEmpty() {
        Res r = createTask(alice, "{\"board_id\":\"" + board.id + "\",\"title\":\"T\"}");
        assertThat(r.status()).isEqualTo(201);
        // omitempty: empty labels and nil due date are left out entirely.
        assertThat(r.keys()).containsExactly("id", "user_id", "board_id", "title", "description", "status", "created_at", "updated_at");
        assertThat(r.json().get("status").asText()).isEqualTo("todo");
    }

    @Test
    void tasksUpdateReplacesLabelIds() {
        Label l1 = seedLabel(alice, board, "1");
        Label l2 = seedLabel(alice, board, "2");
        Task task = seedTask(alice, board, "T", l1.id);
        Res r = putJson("/api/tasks/" + task.id, "{\"label_ids\":[\"" + l2.id + "\"]}", auth(alice));
        assertThat(r.json().get("label_ids").get(0).asText()).isEqualTo(l2.id);
    }

    @Test
    void tasksUpdateEmptyArrayClearsLabels() {
        Label l1 = seedLabel(alice, board, "1");
        Task task = seedTask(alice, board, "T", l1.id);
        Res r = putJson("/api/tasks/" + task.id, "{\"label_ids\":[]}", auth(alice));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().has("label_ids")).isFalse();
        assertThat(state.tasks().get(task.id).labelIds).isEmpty();
    }

    @Test
    void tasksUpdateOmittedLabelIdsLeavesUnchanged() {
        Label l1 = seedLabel(alice, board, "1");
        Task task = seedTask(alice, board, "T", l1.id);
        Res r = putJson("/api/tasks/" + task.id, "{\"title\":\"New\"}", auth(alice));
        assertThat(r.json().get("title").asText()).isEqualTo("New");
        assertThat(r.json().get("label_ids").get(0).asText()).isEqualTo(l1.id);
    }

    @Test
    void tasksUpdateRejectsInvalidLabelIdNoPartialApply() {
        Task task = seedTask(alice, board, "Original");
        Res r = putJson("/api/tasks/" + task.id, "{\"title\":\"Changed\",\"label_ids\":[\"bogus\"]}", auth(alice));
        assertThat(r.errorCode()).isEqualTo("invalid_label");
        Task stored = state.tasks().get(task.id);
        assertThat(stored.title).isEqualTo("Original");
        assertThat(stored.updatedAt).isEqualTo(task.updatedAt);
    }

    @Test
    void taskStatusDueDateAndBoardRules() {
        String b = "\"board_id\":\"" + board.id + "\"";
        assertThat(createTask(alice, "{" + b + "}").errorCode()).isEqualTo("invalid_title");
        assertThat(createTask(alice, "{" + b + ",\"title\":\" \"}").errorCode()).isEqualTo("invalid_title");
        assertThat(createTask(alice, "{\"board_id\":\"missing\",\"title\":\"T\"}").errorCode()).isEqualTo("invalid_board");
        assertThat(createTask(bob, "{" + b + ",\"title\":\"T\"}").errorCode()).isEqualTo("invalid_board");
        assertThat(createTask(alice, "{" + b + ",\"title\":\"T\",\"status\":\"blocked\"}").errorCode()).isEqualTo("invalid_status");
        assertThat(createTask(alice, "{" + b + ",\"title\":\"T\",\"due_date\":\"2026-10-01\"}").errorCode()).isEqualTo("invalid_due_date");

        Res r = createTask(alice, "{" + b + ",\"title\":\"T\",\"status\":\"in_progress\",\"due_date\":\"2026-10-01T00:00:00Z\"}");
        assertThat(r.status()).isEqualTo(201);
        String id = r.json().get("id").asText();
        assertThat(r.json().get("due_date").asText()).isEqualTo("2026-10-01T00:00:00Z");

        Res kept = putJson("/api/tasks/" + id, "{\"due_date\":null,\"status\":\"done\"}", auth(alice));
        assertThat(kept.json().get("due_date").asText()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(kept.json().get("status").asText()).isEqualTo("done");
        assertThat(putJson("/api/tasks/" + id, "{\"due_date\":\"\"}", auth(alice)).json().has("due_date")).isFalse();
        assertThat(putJson("/api/tasks/" + id, "{\"title\":\"\"}", auth(alice)).errorCode()).isEqualTo("invalid_title");
        assertThat(putJson("/api/tasks/" + id, "{}", auth(bob)).status()).isEqualTo(404);
        assertThat(delete("/api/tasks/" + id, auth(bob)).status()).isEqualTo(404);
    }

    @Test
    void tasksListFiltersByBoardAndOwner() {
        Board other = seedBoard(alice, "Other");
        Task t1 = seedTask(alice, board, "1");
        Task t2 = seedTask(alice, other, "2");
        seedTask(bob, seedBoard(bob, "Bob's"), "3");
        assertThat(ids(get("/api/tasks", auth(alice)))).containsExactlyInAnyOrder(t1.id, t2.id);
        assertThat(ids(get("/api/tasks?board_id=" + board.id, auth(alice)))).containsExactly(t1.id);
        assertThat(get("/api/tasks?board_id=" + board.id, auth(bob)).text()).isEqualTo("[]\n");
    }

    @Test
    void taskBoardReassignment() {
        Board other = seedBoard(alice, "Other");
        Task task = seedTask(alice, board, "T");
        assertThat(putJson("/api/tasks/" + task.id, "{\"board_id\":\"" + other.id + "\"}", auth(alice)).json().get("board_id").asText()).isEqualTo(other.id);
        assertThat(putJson("/api/tasks/" + task.id, "{\"board_id\":\"missing\"}", auth(alice)).errorCode()).isEqualTo("invalid_board");
    }
}
