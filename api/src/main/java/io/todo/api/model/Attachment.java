package io.todo.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.time.OffsetDateTime;

public class Attachment {
    public String id = "";
    public String taskId = "";
    public String userId = "";
    public String filename = "";
    public String contentType = "";
    public long sizeBytes;
    public OffsetDateTime createdAt = GoJson.ZERO_TIME;

    public ObjectNode toJson() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("task_id", taskId);
        o.put("user_id", userId);
        o.put("filename", filename);
        o.put("content_type", contentType);
        o.put("size_bytes", sizeBytes);
        o.put("created_at", GoJson.formatTime(createdAt));
        return o;
    }

    public static Attachment fromJson(JsonNode n) {
        Attachment a = new Attachment();
        a.id = GoJson.text(n, "id");
        a.taskId = GoJson.text(n, "task_id");
        a.userId = GoJson.text(n, "user_id");
        a.filename = GoJson.text(n, "filename");
        a.contentType = GoJson.text(n, "content_type");
        a.sizeBytes = n.path("size_bytes").asLong(0);
        a.createdAt = GoJson.timeField(n, "created_at");
        return a;
    }

    public Attachment copy() {
        return fromJson(toJson());
    }
}
