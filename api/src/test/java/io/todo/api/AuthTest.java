package io.todo.api;

import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports of todo-app handlers/auth_handler_test.go and auth_twofactor_test.go,
 * plus the auth parity checks from todo-app-py's test_parity.py.
 */
class AuthTest extends ApiTestBase {

    private Res login(String email, String password) {
        return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private Res verify(String userId, String code) {
        return postJson("/api/auth/2fa/verify", "{\"user_id\":\"" + userId + "\",\"code\":\"" + code + "\"}");
    }

    private User issueChallenge(User user) {
        return io.todo.api.controllers.TestAccess.issueTwoFactorChallenge(state, user);
    }

    private static String wrongCode(User user) {
        return user.twoFactorCode.equals("000000") ? "111111" : "000000";
    }

    @Test
    void loginRejectsGoogleOnlyAccountPasswordAttempt() {
        seedUser("google-only@example.com", null, u -> u.googleId = "google-sub-123");
        Res r = login("google-only@example.com", "whatever123");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("invalid_credentials");
    }

    @Test
    void loginTwoFactorEnabledReturnsChallengeNotToken() {
        User user = seedUser("2fa@example.com", "password123", u -> u.twoFactorEnabled = true);
        Res r = login("2fa@example.com", "password123");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text()).isEqualTo("{\"two_factor_required\":true,\"user_id\":\"" + user.id + "\"}\n");
        User stored = state.users().get(user.id);
        assertThat(stored.twoFactorCode).matches("\\d{6}");
        assertThat(stored.twoFactorCodeExpires).isAfter(GoJson.now());
    }

    @Test
    void loginTwoFactorDisabledReturnsTokenDirectly() {
        seedUser("plain@example.com", "password123", null);
        Res r = login("plain@example.com", "password123");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("token").asText()).isNotEmpty();
    }

    @Test
    void verifyTwoFactorCorrectCode() {
        User user = issueChallenge(seedUser("a@example.com", "password123", u -> u.twoFactorEnabled = true));
        Res r = verify(user.id, user.twoFactorCode);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("token").asText()).isNotEmpty();
        User fin = state.users().get(user.id);
        assertThat(fin.twoFactorCode).isEmpty();
        assertThat(fin.twoFactorAttempts).isZero();
    }

    @Test
    void verifyTwoFactorWrongCodeIncrementsAttempts() {
        User user = issueChallenge(seedUser("a@example.com", "password123", u -> u.twoFactorEnabled = true));
        Res r = verify(user.id, wrongCode(user));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("two_factor_code_incorrect");
        assertThat(state.users().get(user.id).twoFactorAttempts).isEqualTo(1);
    }

    @Test
    void verifyTwoFactorTooManyAttemptsInvalidatesCode() {
        User user = issueChallenge(seedUser("a@example.com", "password123", u -> u.twoFactorEnabled = true));
        for (int i = 0; i < 4; i++) {
            assertThat(verify(user.id, wrongCode(user)).errorCode()).isEqualTo("two_factor_code_incorrect");
        }
        Res r = verify(user.id, wrongCode(user));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("two_factor_too_many_attempts");
        User fin = state.users().get(user.id);
        assertThat(fin.twoFactorCode).isEmpty();
        assertThat(fin.twoFactorAttempts).isZero();
        assertThat(verify(user.id, user.twoFactorCode).errorCode()).isEqualTo("two_factor_not_pending");
    }

    @Test
    void verifyTwoFactorExpiredCode() {
        User user = seedUser("a@example.com", "password123", u -> {
            u.twoFactorEnabled = true;
            u.twoFactorCode = "123456";
            u.twoFactorCodeExpires = GoJson.now().minusMinutes(1);
        });
        Res r = verify(user.id, "123456");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("two_factor_code_expired");
        assertThat(state.users().get(user.id).twoFactorCode).isEmpty();
    }

    @Test
    void verifyTwoFactorNoPendingChallenge() {
        User user = seedUser("a@example.com", "password123", u -> u.twoFactorEnabled = true);
        for (String id : new String[]{user.id, "does-not-exist"}) {
            Res r = verify(id, "123456");
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.errorCode()).isEqualTo("two_factor_not_pending");
        }
        assertThat(verify("", "").errorCode()).isEqualTo("invalid_body");
    }

    @Test
    void updateTwoFactorRequiresBearerAuth() {
        assertThat(putJson("/api/auth/2fa", "{\"enabled\":true}").status()).isEqualTo(401);
    }

    @Test
    void updateTwoFactorTogglesFlag() {
        User user = seedUser("a@example.com", "password123", null);
        Res r = putJson("/api/auth/2fa", "{\"enabled\":true}", auth(user));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("two_factor_enabled").asBoolean()).isTrue();
        assertThat(state.users().get(user.id).twoFactorEnabled).isTrue();

        issueChallenge(state.users().get(user.id));
        r = putJson("/api/auth/2fa", "{\"enabled\":false}", auth(user));
        assertThat(r.json().path("two_factor_enabled").asBoolean()).isFalse();
        assertThat(state.users().get(user.id).twoFactorCode).isEmpty();
    }

    // --- parity: registration, lockout, reset, admin, bootstrap -------------------

    @Test
    void registerShapeAndValidation() {
        Res r = postJson("/api/auth/register", "{\"email\":\" New@Example.com \",\"password\":\"password123\"}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.header("content-type")).isEqualTo("application/json");
        assertThat(r.keys()).containsExactly("token", "user");
        var user = r.json().get("user");
        assertThat(user.fieldNames()).toIterable().containsExactly(
                "id", "email", "is_admin", "failed_login_count", "locked_until", "two_factor_enabled", "created_at");
        assertThat(user.get("email").asText()).isEqualTo("new@example.com");
        assertThat(user.get("locked_until").asText()).isEqualTo("0001-01-01T00:00:00Z");

        assertThat(postJson("/api/auth/register", "{\"email\":\"nope\",\"password\":\"password123\"}").errorCode()).isEqualTo("invalid_email");
        assertThat(postJson("/api/auth/register", "{\"email\":\"a@b.c\",\"password\":\"short\"}").errorCode()).isEqualTo("invalid_password");
        Res dup = postJson("/api/auth/register", "{\"email\":\"NEW@example.com\",\"password\":\"password123\"}");
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.errorCode()).isEqualTo("email_taken");
    }

    @Test
    void loginLockoutAndAdminUnlock() {
        User user = seedUser("a@example.com", "password123", null);
        User admin = seedUser("root@example.com", "adminpass1", u -> u.isAdmin = true);

        assertThat(login("a@example.com", "wrong-1").status()).isEqualTo(401);
        assertThat(login("a@example.com", "wrong-2").status()).isEqualTo(401);
        assertThat(login("a@example.com", "wrong-3").errorCode()).isEqualTo("invalid_credentials");
        User stored = state.users().get(user.id);
        assertThat(stored.failedLoginCount).isEqualTo(3);
        assertThat(stored.lockedUntil).isAfter(GoJson.now().plusMinutes(29));

        Res locked = login("a@example.com", "password123");
        assertThat(locked.status()).isEqualTo(403);
        assertThat(locked.errorCode()).isEqualTo("account_locked");

        Res users = get("/api/admin/users", auth(admin));
        assertThat(users.status()).isEqualTo(200);
        assertThat(users.text()).contains("\"failed_login_count\":3");

        Res unlocked = send("POST", "/api/admin/users/" + user.id + "/unlock", null, null, auth(admin));
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(unlocked.json().get("locked_until").asText()).isEqualTo("0001-01-01T00:00:00Z");
        assertThat(send("POST", "/api/admin/users/missing/unlock", null, null, auth(admin)).status()).isEqualTo(404);
        assertThat(login("a@example.com", "password123").status()).isEqualTo(200);
    }

    @Test
    void unknownEmailLoginIs401() {
        Res r = login("ghost@example.com", "password123");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.errorCode()).isEqualTo("invalid_credentials");
    }

    @Test
    void forgotAndResetPassword() {
        User user = seedUser("a@example.com", "oldpassword", null);
        String msg = "{\"message\":\"if that email exists, a reset link has been sent\"}\n";
        assertThat(postJson("/api/auth/forgot-password", "{\"email\":\"ghost@example.com\"}").text()).isEqualTo(msg);
        assertThat(postJson("/api/auth/forgot-password", "{\"email\":\"A@example.com\"}").text()).isEqualTo(msg);

        String token = state.users().get(user.id).resetToken;
        assertThat(token).hasSize(32);
        assertThat(postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"new_password\":\"short\"}").errorCode()).isEqualTo("invalid_password");
        assertThat(postJson("/api/auth/reset-password", "{\"token\":\"bogus\",\"new_password\":\"newpassword\"}").errorCode()).isEqualTo("invalid_token");
        Res ok = postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"new_password\":\"newpassword\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.text()).isEqualTo("{\"message\":\"password has been reset\"}\n");
        assertThat(postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"new_password\":\"newpassword\"}").errorCode()).isEqualTo("invalid_token");
        assertThat(login("a@example.com", "newpassword").status()).isEqualTo(200);
    }

    @Test
    void authFailuresMatchGo() {
        String unauthorized = "{\"error\":{\"code\":\"unauthorized\",\"message\":\"missing or invalid token\"}}\n";
        assertThat(get("/api/auth/me").text()).isEqualTo(unauthorized);
        assertThat(get("/api/boards").text()).isEqualTo(unauthorized);
        assertThat(send("POST", "/api/auth/logout", null, null).status()).isEqualTo(401);
        assertThat(get("/api/admin/users").status()).isEqualTo(401);
        for (String h : new String[]{"Bearer nope", "Basic abc", "bearer x"}) {
            assertThat(get("/api/auth/me", "Authorization", h).status()).isEqualTo(401);
        }
        User user = seedUser("a@example.com");
        Res forbidden = get("/api/admin/users", auth(user));
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.text()).isEqualTo("{\"error\":{\"code\":\"forbidden\",\"message\":\"admin access required\"}}\n");
        Res logout = send("POST", "/api/auth/logout", null, null, auth(user));
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
    }

    @Test
    void bootstrapAdminCreatedOnce() {
        var users = state.users().list();
        assertThat(users).hasSize(1);
        assertThat(users.get(0).email).isEqualTo("admin@todo.io");
        assertThat(users.get(0).isAdmin).isTrue();
        state.load(dataDir); // "restart" against the same data dir
        assertThat(state.users().list()).extracting(u -> u.id).containsExactly(users.get(0).id);
    }

    @Test
    void passwordLengthCountsUtf8BytesLikeGo() {
        // 4 x "é" = 8 UTF-8 bytes: Go's len() accepts it, so must we.
        Res r = postJson("/api/auth/register", "{\"email\":\"u@example.com\",\"password\":\"éééé\"}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(Map.of("ok", login("u@example.com", "éééé").status())).containsEntry("ok", 200);
    }
}
