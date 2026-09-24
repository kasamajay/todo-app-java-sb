package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import io.todo.api.web.ApiError;
import io.todo.api.web.Auth;
import io.todo.api.web.Responses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only routes (Go: admin_handler.go). Non-admins get 403 - here the
 * gatekeeping is the point, unlike the 404s used for ownership checks.
 */
@RestController
public class AdminController {

    private final AppState state;
    private final Auth auth;

    public AdminController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<byte[]> listUsers(HttpServletRequest req) {
        auth.requireAdmin(req);
        return Responses.jsonList(200, state.users().list(), User::publicView);
    }

    @PostMapping("/api/admin/users/{id}/unlock")
    public ResponseEntity<byte[]> unlock(HttpServletRequest req, @PathVariable String id) {
        auth.requireAdmin(req);
        User user = state.users().get(id);
        if (user == null) {
            throw ApiError.notFound("user not found");
        }
        user.failedLoginCount = 0;
        user.lockedUntil = GoJson.ZERO_TIME;
        state.users().put(user.id, user);
        return Responses.json(200, user.publicView());
    }
}
