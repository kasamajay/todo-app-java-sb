package io.todo.api;

import io.todo.api.auth.AuthPrimitivesTest;
import io.todo.api.auth.GoogleApi;
import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Ports of todo-app handlers/auth_google_handler_test.go, plus the callback
 * success paths with Google itself stubbed out.
 */
@TestPropertySource(properties = {"todo.google.client-id=test-client-id", "todo.google.client-secret=test-client-secret"})
class GoogleOAuthTest extends ApiTestBase {

    @MockBean
    GoogleApi google;

    @BeforeEach
    void stubGoogle() throws Exception {
        when(google.exchangeCode(anyString(), anyString(), anyString(), anyString())).thenReturn("access-token");
        when(google.fetchUserInfo(any())).thenReturn(new GoogleApi.UserInfo("g-123", "Person@Gmail.com", true));
    }

    private static Map<String, String> fragment(Res r) {
        assertThat(r.status()).isEqualTo(302);
        return AuthPrimitivesTest.query(URI.create(r.header("location")).getRawFragment());
    }

    private Res callback() {
        return get("/api/auth/google/callback?code=abc&state=st", "Cookie", "google_oauth_state=st");
    }

    @Test
    void googleLoginRedirectsToGoogleWithStateCookie() {
        Res r = get("/api/auth/google/login");
        assertThat(r.status()).isEqualTo(302);
        URI loc = URI.create(r.header("location"));
        assertThat(loc.getHost()).isEqualTo("accounts.google.com");
        Map<String, String> q = AuthPrimitivesTest.query(loc.getRawQuery());
        assertThat(q).containsEntry("client_id", "test-client-id")
                .containsEntry("redirect_uri", "http://localhost:5173/api/auth/google/callback")
                .containsEntry("response_type", "code");
        assertThat(q.get("state")).isNotEmpty();

        String setCookie = r.header("set-cookie");
        HttpCookie cookie = HttpCookie.parse(setCookie).get(0);
        assertThat(cookie.getName()).isEqualTo("google_oauth_state");
        assertThat(cookie.getValue()).isEqualTo(q.get("state"));
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth/google");
        assertThat(setCookie).contains("Max-Age=300").contains("SameSite=Lax");
    }

    @Test
    void googleCallbackMissingStateCookie() {
        assertThat(fragment(get("/api/auth/google/callback?code=abc&state=xyz"))).containsEntry("google_error", "google_state_missing");
    }

    @Test
    void googleCallbackStateMismatch() {
        Res r = get("/api/auth/google/callback?code=abc&state=wrong-value", "Cookie", "google_oauth_state=correct-value");
        assertThat(fragment(r)).containsEntry("google_error", "google_state_mismatch");
        // One-time use: the state cookie is cleared even on failure.
        assertThat(r.header("set-cookie")).startsWith("google_oauth_state=").contains("Max-Age=0");
    }

    @Test
    void googleCallbackUserDenied() {
        Res r = get("/api/auth/google/callback?error=access_denied");
        assertThat(fragment(r)).containsEntry("google_error", "google_denied");
        assertThat(r.header("location")).startsWith("http://localhost:5173/#google_error=");
    }

    @Test
    void googleCallbackCreatesAccountAndReturnsToken() {
        Map<String, String> frag = fragment(callback());
        Res me = get("/api/auth/me", "Authorization", "Bearer " + frag.get("google_token"));
        assertThat(me.json().get("email").asText()).isEqualTo("person@gmail.com");
        User stored = state.users().findByEmail("person@gmail.com");
        assertThat(stored.googleId).isEqualTo("g-123");
        assertThat(stored.passwordHash).isNull();
    }

    @Test
    void googleCallbackAutoLinksExistingEmail() {
        User existing = seedUser("person@gmail.com", "password123", null);
        assertThat(fragment(callback())).containsKey("google_token");
        assertThat(state.users().get(existing.id).googleId).isEqualTo("g-123");
        assertThat(state.users().list()).hasSize(2); // bootstrap admin + the linked account
    }

    @Test
    void googleCallbackTwoFactorAndLock() {
        User user = seedUser("person@gmail.com", null, u -> {
            u.googleId = "g-123";
            u.twoFactorEnabled = true;
        });
        assertThat(fragment(callback())).isEqualTo(Map.of("google_2fa_required", user.id));
        User locked = state.users().get(user.id);
        locked.lockedUntil = GoJson.now().plusMinutes(5);
        state.users().put(locked.id, locked);
        assertThat(fragment(callback())).isEqualTo(Map.of("google_error", "google_account_locked"));
    }

    @Test
    void googleCallbackFailures() throws Exception {
        when(google.fetchUserInfo(any())).thenReturn(new GoogleApi.UserInfo("g", "x@y.z", false));
        assertThat(fragment(callback())).isEqualTo(Map.of("google_error", "google_email_unverified"));

        when(google.fetchUserInfo(any())).thenThrow(new GoogleApi.GoogleException("down"));
        assertThat(fragment(callback())).isEqualTo(Map.of("google_error", "google_userinfo_failed"));

        when(google.exchangeCode(anyString(), anyString(), anyString(), anyString())).thenThrow(new GoogleApi.GoogleException("down"));
        assertThat(fragment(callback())).isEqualTo(Map.of("google_error", "google_exchange_failed"));

        Res noCode = get("/api/auth/google/callback?state=st", "Cookie", "google_oauth_state=st");
        assertThat(fragment(noCode)).isEqualTo(Map.of("google_error", "google_missing_code"));
        assertThat(List.of(noCode.status())).containsExactly(302);
    }
}
