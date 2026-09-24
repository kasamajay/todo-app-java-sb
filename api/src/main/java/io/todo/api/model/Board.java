package io.todo.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.time.OffsetDateTime;

public class Board {
    public String id = "";
    public String userId = "";
    public String name = "";
    public String summary = "";
    public String startDate = "";
    public OffsetDateTime createdAt = GoJson.ZERO_TIME;
    public OffsetDateTime updatedAt = GoJson.ZERO_TIME;

    public ObjectNode toJson() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("user_id", userId);
        o.put("name", name);
        o.put("summary", summary);
        o.put("start_date", startDate);
        o.put("created_at", GoJson.formatTime(createdAt));
        o.put("updated_at", GoJson.formatTime(updatedAt));
        return o;
    }

    public static Board fromJson(JsonNode n) {
        Board b = new Board();
        b.id = GoJson.text(n, "id");
        b.userId = GoJson.text(n, "user_id");
        b.name = GoJson.text(n, "name");
        b.summary = GoJson.text(n, "summary");
        b.startDate = GoJson.text(n, "start_date");
        b.createdAt = GoJson.timeField(n, "created_at");
        b.updatedAt = GoJson.timeField(n, "updated_at");
        return b;
    }

    public Board copy() {
        return fromJson(toJson());
    }
}
