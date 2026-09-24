package io.todo.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Task {

    public static final String STATUS_TODO = "todo";
    public static final Set<String> VALID_STATUSES = Set.of("todo", "in_progress", "done");

    public String id = "";
    public String userId = "";
    public String boardId = "";
    public String title = "";
    public String description = "";
    public String status = STATUS_TODO;
    /** null = no due date (a nil *time.Time in Go). */
    public OffsetDateTime dueDate;
    public List<String> labelIds = new ArrayList<>();
    public OffsetDateTime createdAt = GoJson.ZERO_TIME;
    public OffsetDateTime updatedAt = GoJson.ZERO_TIME;

    public ObjectNode toJson() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("user_id", userId);
        o.put("board_id", boardId);
        o.put("title", title);
        o.put("description", description);
        o.put("status", status);
        // Both omitempty in Go: a nil due date and an empty label list are
        // left out entirely (the frontend treats a missing key as "none").
        if (dueDate != null) {
            o.put("due_date", GoJson.formatTime(dueDate));
        }
        if (!labelIds.isEmpty()) {
            ArrayNode arr = o.putArray("label_ids");
            labelIds.forEach(arr::add);
        }
        o.put("created_at", GoJson.formatTime(createdAt));
        o.put("updated_at", GoJson.formatTime(updatedAt));
        return o;
    }

    public static Task fromJson(JsonNode n) {
        Task t = new Task();
        t.id = GoJson.text(n, "id");
        t.userId = GoJson.text(n, "user_id");
        t.boardId = GoJson.text(n, "board_id");
        t.title = GoJson.text(n, "title");
        t.description = GoJson.text(n, "description");
        t.status = n.hasNonNull("status") ? n.get("status").asText() : STATUS_TODO;
        String due = GoJson.text(n, "due_date");
        t.dueDate = due.isEmpty() ? null : GoJson.parseTime(due);
        t.labelIds = new ArrayList<>();
        for (JsonNode id : n.path("label_ids")) {
            t.labelIds.add(id.asText());
        }
        t.createdAt = GoJson.timeField(n, "created_at");
        t.updatedAt = GoJson.timeField(n, "updated_at");
        return t;
    }

    public Task copy() {
        return fromJson(toJson());
    }
}
