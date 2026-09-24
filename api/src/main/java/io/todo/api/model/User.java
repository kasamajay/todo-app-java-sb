package io.todo.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import java.time.OffsetDateTime;

/**
 * A user account. Field names, order, and omitempty behaviour mirror the Go
 * struct in todo-app's api/internal/models/models.go exactly, so API
 * responses are identical and data files interchangeable across backends.
 * Mutable, but the stores hand out copies (Go value semantics).
 */
public class User {
    public String id = "";
    public String email = "";
    /** null mirrors a nil []byte in Go (JSON null) - e.g. a Google-only account. */
    public byte[] passwordHash;
    public byte[] salt;
    public String googleId = "";
    public boolean isAdmin;
    public int failedLoginCount;
    public OffsetDateTime lockedUntil = GoJson.ZERO_TIME;
    public String resetToken = "";
    public OffsetDateTime resetTokenExpires = GoJson.ZERO_TIME;
    // Two-factor auth: opt-in per account (decisions/0011). twoFactorCode et
    // al. hold a pending login challenge, cleared once verified, expired, or
    // invalidated after too many wrong attempts.
    public boolean twoFactorEnabled;
    public String twoFactorCode = "";
    public OffsetDateTime twoFactorCodeExpires = GoJson.ZERO_TIME;
    public int twoFactorAttempts;
    public OffsetDateTime createdAt = GoJson.ZERO_TIME;

    public boolean isLocked() {
        return GoJson.now().isBefore(lockedUntil);
    }

    public boolean hasPassword() {
        return passwordHash != null && passwordHash.length > 0;
    }

    public void clearTwoFactorChallenge() {
        twoFactorCode = "";
        twoFactorCodeExpires = GoJson.ZERO_TIME;
        twoFactorAttempts = 0;
    }

    /** The representation safe to send to clients (no secrets) - Go's PublicUser. */
    public ObjectNode publicView() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("email", email);
        o.put("is_admin", isAdmin);
        o.put("failed_login_count", failedLoginCount);
        o.put("locked_until", GoJson.formatTime(lockedUntil));
        o.put("two_factor_enabled", twoFactorEnabled);
        o.put("created_at", GoJson.formatTime(createdAt));
        return o;
    }

    public ObjectNode toJson() {
        ObjectNode o = GoJson.object();
        o.put("id", id);
        o.put("email", email);
        o.put("password_hash", GoJson.b64(passwordHash));
        o.put("salt", GoJson.b64(salt));
        if (!googleId.isEmpty()) {
            o.put("google_id", googleId);
        }
        o.put("is_admin", isAdmin);
        o.put("failed_login_count", failedLoginCount);
        o.put("locked_until", GoJson.formatTime(lockedUntil));
        if (!resetToken.isEmpty()) {
            o.put("reset_token", resetToken);
        }
        o.put("reset_token_expires", GoJson.formatTime(resetTokenExpires));
        o.put("two_factor_enabled", twoFactorEnabled);
        if (!twoFactorCode.isEmpty()) {
            o.put("two_factor_code", twoFactorCode);
        }
        o.put("two_factor_code_expires", GoJson.formatTime(twoFactorCodeExpires));
        if (twoFactorAttempts != 0) {
            o.put("two_factor_attempts", twoFactorAttempts);
        }
        o.put("created_at", GoJson.formatTime(createdAt));
        return o;
    }

    public static User fromJson(JsonNode n) {
        User u = new User();
        u.id = GoJson.text(n, "id");
        u.email = GoJson.text(n, "email");
        u.passwordHash = GoJson.unb64(n.get("password_hash"));
        u.salt = GoJson.unb64(n.get("salt"));
        u.googleId = GoJson.text(n, "google_id");
        u.isAdmin = n.path("is_admin").asBoolean(false);
        u.failedLoginCount = n.path("failed_login_count").asInt(0);
        u.lockedUntil = GoJson.timeField(n, "locked_until");
        u.resetToken = GoJson.text(n, "reset_token");
        u.resetTokenExpires = GoJson.timeField(n, "reset_token_expires");
        u.twoFactorEnabled = n.path("two_factor_enabled").asBoolean(false);
        u.twoFactorCode = GoJson.text(n, "two_factor_code");
        u.twoFactorCodeExpires = GoJson.timeField(n, "two_factor_code_expires");
        u.twoFactorAttempts = n.path("two_factor_attempts").asInt(0);
        u.createdAt = GoJson.timeField(n, "created_at");
        return u;
    }

    public User copy() {
        return fromJson(toJson());
    }
}
