package io.todo.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Google OAuth with no client id/secret configured (the default). */
class GoogleNotConfiguredTest extends ApiTestBase {

    @Test
    void googleLoginNotConfigured() {
        Res r = get("/api/auth/google/login");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.errorCode()).isEqualTo("google_oauth_not_configured");
    }

    @Test
    void googleCallbackNotConfigured() {
        Res r = get("/api/auth/google/callback?code=abc&state=xyz");
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).isEqualTo("http://localhost:5173/#google_error=google_oauth_not_configured");
    }
}
