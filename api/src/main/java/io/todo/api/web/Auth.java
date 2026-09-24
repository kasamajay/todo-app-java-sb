package io.todo.api.web;

import io.todo.api.AppState;
import io.todo.api.auth.Tokens;
import io.todo.api.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The counterparts of the Go API's RequireAuth / RequireAdmin middleware,
 * called as the first statement of each protected handler (the same place Go
 * wraps the handler), with byte-identical 401/403 bodies. No Spring Security:
 * its session/CSRF/filter defaults would change the contract.
 */
@Component
public class Auth {

    private final AppState state;

    public Auth(AppState state) {
        this.state = state;
    }

    /** Extract and verify the bearer token and load its user, or throw 401. */
    public User requireUser(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiError.unauthorized();
        }
        Tokens.Claims claims;
        try {
            claims = Tokens.verify(state.secret(), header.substring("Bearer ".length()));
        } catch (Tokens.TokenException e) {
            throw ApiError.unauthorized();
        }
        User user = state.users().get(claims.userId());
        if (user == null) {
            throw ApiError.unauthorized();
        }
        return user;
    }

    /** requireUser, then reject non-admins with 403. */
    public User requireAdmin(HttpServletRequest req) {
        User user = requireUser(req);
        if (!user.isAdmin) {
            throw new ApiError(403, "forbidden", "admin access required");
        }
        return user;
    }
}
