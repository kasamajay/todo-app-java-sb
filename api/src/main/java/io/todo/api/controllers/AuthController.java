package io.todo.api.controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.AppState;
import io.todo.api.auth.Passwords;
import io.todo.api.auth.Secrets;
import io.todo.api.auth.Tokens;
import io.todo.api.json.BodyDecoder;
import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import io.todo.api.web.ApiError;
import io.todo.api.web.Auth;
import io.todo.api.web.Responses;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static io.todo.api.json.BodyDecoder.Kind.BOOL;
import static io.todo.api.json.BodyDecoder.Kind.STRING;

/** Password auth, password reset, and two-factor routes (Go: auth_handler.go). */
@RestController
public class AuthController {

    static final Logger LOG = LoggerFactory.getLogger("todo-app");

    static final int MAX_FAILED_LOGINS = 3;
    static final Duration LOCK_DURATION = Duration.ofMinutes(30);
    static final Duration RESET_TOKEN_TTL = Duration.ofHours(1);
    static final int MIN_PASSWORD_LENGTH = 8;
    static final Duration TWO_FACTOR_CODE_TTL = Duration.ofMinutes(10);
    static final int TWO_FACTOR_MAX_ATTEMPTS = 5;

    private static final Map<String, BodyDecoder.Kind> CREDENTIALS = BodyDecoder.schema("email", STRING, "password", STRING);

    private static volatile Passwords.Hashed dummy;

    private final AppState state;
    private final Auth auth;

    public AuthController(AppState state, Auth auth) {
        this.state = state;
        this.auth = auth;
    }

    /**
     * A fixed salt/hash pair used when the submitted email doesn't match a
     * user (or the account has no password), so PBKDF2 still runs at full
     * cost and response timing can't reveal account existence.
     */
    private static Passwords.Hashed dummyLoginParams() {
        if (dummy == null) {
            synchronized (AuthController.class) {
                if (dummy == null) {
                    dummy = new Passwords.Hashed(Passwords.hash("dummy-password-for-timing-only").hash(),
                            "fixed-dummy-salt-16b".getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return dummy;
    }

    static String mint(AppState state, User user) {
        return Tokens.mint(state.secret(), new Tokens.Claims(user.id, user.isAdmin, GoJson.now().plus(Tokens.TTL)));
    }

    static ResponseEntity<byte[]> tokenResponse(AppState state, int status, User user) {
        ObjectNode body = GoJson.object();
        body.put("token", mint(state, user));
        body.set("user", user.publicView());
        return Responses.json(status, body);
    }

    /** Go's len() on a string counts UTF-8 bytes, not characters. */
    private static int byteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<byte[]> register(@RequestBody(required = false) byte[] raw) {
        var req = BodyDecoder.decode(raw, CREDENTIALS);

        String email = req.str("email").strip().toLowerCase();
        if (email.isEmpty() || !email.contains("@")) {
            throw new ApiError(400, "invalid_email", "a valid email is required");
        }
        if (byteLength(req.str("password")) < MIN_PASSWORD_LENGTH) {
            throw new ApiError(400, "invalid_password", "password must be at least 8 characters");
        }
        if (state.users().findByEmail(email) != null) {
            throw new ApiError(409, "email_taken", "an account with that email already exists");
        }

        Passwords.Hashed h = Passwords.hash(req.str("password"));
        User user = new User();
        user.id = Secrets.newId();
        user.email = email;
        user.passwordHash = h.hash();
        user.salt = h.salt();
        user.createdAt = GoJson.now();
        state.users().put(user.id, user);

        return tokenResponse(state, 201, user);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<byte[]> login(@RequestBody(required = false) byte[] raw) {
        var req = BodyDecoder.decode(raw, CREDENTIALS);
        String email = req.str("email").strip().toLowerCase();

        User user = state.users().findByEmail(email);
        boolean hasPassword = user != null && user.hasPassword();
        Passwords.Hashed params = hasPassword ? new Passwords.Hashed(user.passwordHash, user.salt) : dummyLoginParams();

        // Always run PBKDF2 - found or not, password set or not - so response
        // timing doesn't reveal whether the email is registered or is a
        // Google-only account with no password set.
        boolean match = Passwords.verify(req.str("password"), params.hash(), params.salt());

        if (!hasPassword) {
            throw new ApiError(401, "invalid_credentials", "invalid email or password");
        }
        if (user.isLocked()) {
            throw new ApiError(403, "account_locked", "account is locked, try again later");
        }
        if (!match) {
            user.failedLoginCount++;
            if (user.failedLoginCount >= MAX_FAILED_LOGINS) {
                user.lockedUntil = GoJson.now().plus(LOCK_DURATION);
            }
            try {
                state.users().put(user.id, user);
            } catch (RuntimeException e) {
                LOG.error("failed to persist failed login count for {}: {}", user.id, e.toString());
            }
            throw new ApiError(401, "invalid_credentials", "invalid email or password");
        }

        user.failedLoginCount = 0;
        user.lockedUntil = GoJson.ZERO_TIME;
        state.users().put(user.id, user);

        if (user.twoFactorEnabled) {
            User updated = issueTwoFactorChallenge(state, user);
            ObjectNode body = GoJson.object();
            body.put("two_factor_required", true);
            body.put("user_id", updated.id);
            return Responses.json(200, body);
        }
        return tokenResponse(state, 200, user);
    }

    /** Tokens are stateless, so logout is a client-side discard - nothing to revoke here. */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<byte[]> logout(HttpServletRequest httpReq) {
        auth.requireUser(httpReq);
        return Responses.noContent();
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<byte[]> me(HttpServletRequest httpReq) {
        return Responses.json(200, auth.requireUser(httpReq).publicView());
    }

    @PostMapping("/api/auth/forgot-password")
    public ResponseEntity<byte[]> forgotPassword(@RequestBody(required = false) byte[] raw) {
        var req = BodyDecoder.decode(raw, BodyDecoder.schema("email", STRING));

        User user = state.users().findByEmail(req.str("email").strip().toLowerCase());
        if (user != null) {
            user.resetToken = Secrets.newId();
            user.resetTokenExpires = GoJson.now().plus(RESET_TOKEN_TTL);
            boolean saved = true;
            try {
                state.users().put(user.id, user);
            } catch (RuntimeException e) {
                saved = false;
            }
            if (saved) {
                // No email infrastructure exists in this project (decisions/0007),
                // so the reset token is logged server-side instead of emailed.
                LOG.info("password reset requested for {}: reset_token={} (expires {})",
                        user.email, user.resetToken, rfc3339(user.resetTokenExpires));
            }
        }

        // Always 200 regardless of whether the email exists, so this endpoint
        // can't be used to enumerate registered accounts.
        ObjectNode body = GoJson.object();
        body.put("message", "if that email exists, a reset link has been sent");
        return Responses.json(200, body);
    }

    @PostMapping("/api/auth/reset-password")
    public ResponseEntity<byte[]> resetPassword(@RequestBody(required = false) byte[] raw) {
        var req = BodyDecoder.decode(raw, BodyDecoder.schema("token", STRING, "new_password", STRING));
        if (byteLength(req.str("new_password")) < MIN_PASSWORD_LENGTH) {
            throw new ApiError(400, "invalid_password", "password must be at least 8 characters");
        }

        String token = req.str("token");
        User user = state.users().findByResetToken(token);
        if (user == null || token.isEmpty() || GoJson.now().isAfter(user.resetTokenExpires)) {
            throw new ApiError(400, "invalid_token", "reset token is invalid or expired");
        }

        Passwords.Hashed h = Passwords.hash(req.str("new_password"));
        user.passwordHash = h.hash();
        user.salt = h.salt();
        user.resetToken = "";
        user.resetTokenExpires = GoJson.ZERO_TIME;
        user.failedLoginCount = 0;
        user.lockedUntil = GoJson.ZERO_TIME;
        state.users().put(user.id, user);

        ObjectNode body = GoJson.object();
        body.put("message", "password has been reset");
        return Responses.json(200, body);
    }

    /**
     * Generate a 6-digit code, persist it with a short expiry, and log it
     * server-side instead of emailing it (decisions/0007, 0011). Shared by
     * login and the Google callback, which must gate on 2FA identically.
     */
    static User issueTwoFactorChallenge(AppState state, User user) {
        user.twoFactorCode = Secrets.sixDigitCode();
        user.twoFactorCodeExpires = GoJson.now().plus(TWO_FACTOR_CODE_TTL);
        user.twoFactorAttempts = 0;
        state.users().put(user.id, user);
        LOG.info("two-factor code requested for {}: code={} (expires {})",
                user.email, user.twoFactorCode, rfc3339(user.twoFactorCodeExpires));
        return user;
    }

    /**
     * Completes a login put on hold by issueTwoFactorChallenge. Public route
     * (the caller isn't fully authenticated yet), gated by the code itself
     * plus a small attempt budget rather than a bearer token.
     */
    @PostMapping("/api/auth/2fa/verify")
    public ResponseEntity<byte[]> verifyTwoFactor(@RequestBody(required = false) byte[] raw) {
        var req = BodyDecoder.decode(raw, BodyDecoder.schema("user_id", STRING, "code", STRING));
        String userId = req.str("user_id").strip();
        String code = req.str("code").strip();
        if (userId.isEmpty() || code.isEmpty()) {
            throw new ApiError(400, "invalid_body", "user_id and code are required");
        }

        User user = state.users().get(userId);
        if (user == null || user.twoFactorCode.isEmpty()) {
            throw new ApiError(401, "two_factor_not_pending", "no verification code is pending for this account");
        }

        if (GoJson.now().isAfter(user.twoFactorCodeExpires)) {
            user.clearTwoFactorChallenge();
            persistQuietly(user, "failed to clear expired two-factor code for {}: {}");
            throw new ApiError(401, "two_factor_code_expired", "verification code has expired, please log in again");
        }

        if (!code.equals(user.twoFactorCode)) {
            user.twoFactorAttempts++;
            if (user.twoFactorAttempts >= TWO_FACTOR_MAX_ATTEMPTS) {
                user.clearTwoFactorChallenge();
                persistQuietly(user, "failed to invalidate two-factor code for {}: {}");
                throw new ApiError(401, "two_factor_too_many_attempts", "too many incorrect attempts, please log in again");
            }
            persistQuietly(user, "failed to persist two-factor attempt count for {}: {}");
            throw new ApiError(401, "two_factor_code_incorrect", "incorrect verification code");
        }

        user.clearTwoFactorChallenge();
        state.users().put(user.id, user);
        return tokenResponse(state, 200, user);
    }

    /** Enable/disable 2FA on the caller's own account - opt-in only, no admin override. */
    @PutMapping("/api/auth/2fa")
    public ResponseEntity<byte[]> updateTwoFactor(HttpServletRequest httpReq, @RequestBody(required = false) byte[] raw) {
        User ctxUser = auth.requireUser(httpReq);
        var req = BodyDecoder.decode(raw, BodyDecoder.schema("enabled", BOOL));

        User user = state.users().get(ctxUser.id);
        if (user == null) {
            throw new ApiError(401, "unauthorized", "account not found");
        }
        user.twoFactorEnabled = req.bool("enabled");
        if (!user.twoFactorEnabled) {
            user.clearTwoFactorChallenge();
        }
        state.users().put(user.id, user);
        return Responses.json(200, user.publicView());
    }

    private void persistQuietly(User user, String logFormat) {
        try {
            state.users().put(user.id, user);
        } catch (RuntimeException e) {
            LOG.error(logFormat, user.id, e.toString());
        }
    }

    /** Go's time.RFC3339 (whole seconds), used in the logged expiry times. */
    static String rfc3339(OffsetDateTime t) {
        return GoJson.formatTime(t.withNano(0));
    }
}
