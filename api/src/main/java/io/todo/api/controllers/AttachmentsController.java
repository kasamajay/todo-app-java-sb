package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.auth.Secrets;
import io.todo.api.json.GoJson;
import io.todo.api.model.Attachment;
import io.todo.api.model.Task;
import io.todo.api.model.User;
import io.todo.api.web.ApiError;
import io.todo.api.web.Auth;
import io.todo.api.web.ContentSniffer;
import io.todo.api.web.Responses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * Attachment routes (Go: attachments_handler.go). Blobs live under
 * data/attachments/&lt;id&gt;, metadata in attachments.json. 10MB per file.
 */
@RestController
public class AttachmentsController {

    public static final int MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

    private final AppState state;
    private final Auth auth;

    public AttachmentsController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    private static ApiError tooLarge() {
        return new ApiError(413, "file_too_large", "attachment exceeds the 10MB limit");
    }

    private Task ownedTask(User user, String taskId) {
        Task task = state.tasks().get(taskId);
        if (task == null || !task.userId.equals(user.id)) {
            throw ApiError.notFound("task not found");
        }
        return task;
    }

    @PostMapping("/api/tasks/{id}/attachments")
    public ResponseEntity<byte[]> upload(HttpServletRequest req, @PathVariable String id) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);

        // Multipart is resolved lazily (application.properties), so the body is
        // only parsed here - after the ownership check, as in the Go handler.
        // A non-multipart body, an unparseable one, or one over the request cap
        // is 413, since Go reports every ParseMultipartForm failure that way.
        if (!(req instanceof MultipartHttpServletRequest multipart)) {
            throw tooLarge();
        }
        MultipartFile file = multipart.getFile("file");
        if (file == null) {
            throw new ApiError(400, "invalid_file", "a 'file' form field is required");
        }
        if (file.getSize() > MAX_ATTACHMENT_BYTES) {
            throw tooLarge();
        }
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readNBytes(MAX_ATTACHMENT_BYTES + 1);
        } catch (IOException e) {
            throw new ApiError(500, "internal_error", "failed to read upload");
        }
        if (content.length > MAX_ATTACHMENT_BYTES) {
            throw tooLarge();
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            contentType = ContentSniffer.detect(content);
        }
        // Go's multipart.FileHeader.Filename is the base name of the part's filename.
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        filename = filename.substring(filename.lastIndexOf('/') + 1);

        Attachment attachment = new Attachment();
        attachment.id = Secrets.newId();
        attachment.taskId = task.id;
        attachment.userId = user.id;
        attachment.filename = filename;
        attachment.contentType = contentType;
        attachment.sizeBytes = content.length;
        attachment.createdAt = GoJson.now();

        try {
            state.attachments().writeBlob(attachment.id, content);
        } catch (RuntimeException e) {
            throw new ApiError(500, "internal_error", "failed to store attachment");
        }
        try {
            state.attachments().put(attachment.id, attachment);
        } catch (RuntimeException e) {
            state.attachments().deleteBlob(attachment.id);
            throw new ApiError(500, "internal_error", "failed to store attachment");
        }
        return Responses.json(201, attachment.toJson());
    }

    @GetMapping("/api/tasks/{id}/attachments")
    public ResponseEntity<byte[]> list(HttpServletRequest req, @PathVariable String id) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);
        return Responses.jsonList(200, state.attachments().listByTask(task.id), Attachment::toJson);
    }

    private Attachment ownedAttachment(Task task, String aid) {
        Attachment attachment = state.attachments().get(aid);
        if (attachment == null || !attachment.taskId.equals(task.id)) {
            throw ApiError.notFound("attachment not found");
        }
        return attachment;
    }

    @GetMapping("/api/tasks/{id}/attachments/{aid}")
    public ResponseEntity<byte[]> download(HttpServletRequest req, @PathVariable String id, @PathVariable String aid) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);
        Attachment attachment = ownedAttachment(task, aid);
        byte[] content;
        try {
            content = state.attachments().readBlob(attachment.id);
        } catch (IOException e) {
            throw ApiError.notFound("attachment content not found");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, attachment.contentType)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(content.length))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.filename + "\"")
                .body(content);
    }

    @DeleteMapping("/api/tasks/{id}/attachments/{aid}")
    public ResponseEntity<byte[]> delete(HttpServletRequest req, @PathVariable String id, @PathVariable String aid) {
        User user = auth.requireUser(req);
        Task task = ownedTask(user, id);
        Attachment attachment = ownedAttachment(task, aid);
        try {
            state.attachments().deleteBlob(attachment.id);
        } catch (RuntimeException e) {
            throw new ApiError(500, "internal_error", "failed to delete attachment content");
        }
        state.attachments().delete(attachment.id);
        return Responses.noContent();
    }
}
