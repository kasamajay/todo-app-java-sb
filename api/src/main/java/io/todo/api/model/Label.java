package io.todo.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.time.OffsetDateTime;
import java.util.List;

public class Label {

    /**
     * The fixed palette labels may use. web/src/theme.js's labelColors mirrors
     * this exact list (same hex values, same order) - kept in sync manually.
     */
    public static final List<String> COLORS = List.of(
            "#6366f1", // brand indigo
            "#ef4444", // danger red
            "#f59e0b", // warning amber
            "#16a34a", // success green
            "#0ea5e9", // blue
            "#8b5cf6", // purple
            "#ec4899", // pink
            "#6b7280"  // gray
    );

    public String id = "";
    public String userId = "";
    public String boardId = "";
    public String name = "";
    public String color = "";
    public OffsetDateTime createdAt = GoJson.ZERO_TIME;

    public static boolean isValidColor(String c) {
        return COLORS.contains(c);
    }

    public ObjectNode toJson() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("user_id", userId);
        o.put("board_id", boardId);
        o.put("name", name);
        o.put("color", color);
        o.put("created_at", GoJson.formatTime(createdAt));
        return o;
    }

    public static Label fromJson(JsonNode n) {
        Label l = new Label();
        l.id = GoJson.text(n, "id");
        l.userId = GoJson.text(n, "user_id");
        l.boardId = GoJson.text(n, "board_id");
        l.name = GoJson.text(n, "name");
        l.color = GoJson.text(n, "color");
        l.createdAt = GoJson.timeField(n, "created_at");
        return l;
    }

    public Label copy() {
        return fromJson(toJson());
    }
}
