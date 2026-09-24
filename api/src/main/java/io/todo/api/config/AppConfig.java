package io.todo.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Environment configuration - the same variable names and defaults as the Go
 * API (application.properties maps DATA_DIR, GOOGLE_*, FRONTEND_BASE_URL and
 * ADDR onto these). As with Go's getEnv, an empty value means "unset".
 */
@Component
public class AppConfig {

    private final String dataDir;
    private final String googleClientId;
    private final String googleClientSecret;
    private final String googleRedirectUri;
    private final String frontendBaseUrl;
    private final String addr;

    public AppConfig(
            @Value("${todo.data-dir}") String dataDir,
            @Value("${todo.google.client-id}") String googleClientId,
            @Value("${todo.google.client-secret}") String googleClientSecret,
            @Value("${todo.google.redirect-uri}") String googleRedirectUri,
            @Value("${todo.frontend-base-url}") String frontendBaseUrl,
            @Value("${todo.addr}") String addr) {
        this.dataDir = orDefault(dataDir, "./data");
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.googleRedirectUri = orDefault(googleRedirectUri, "http://localhost:5173/api/auth/google/callback");
        this.frontendBaseUrl = orDefault(frontendBaseUrl, "http://localhost:5173");
        this.addr = orDefault(addr, ":8080");
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    public String dataDir() {
        return dataDir;
    }

    public String googleClientId() {
        return googleClientId;
    }

    public String googleClientSecret() {
        return googleClientSecret;
    }

    public String googleRedirectUri() {
        return googleRedirectUri;
    }

    public String frontendBaseUrl() {
        return frontendBaseUrl;
    }

    public String addr() {
        return addr;
    }

    public boolean googleConfigured() {
        return !googleClientId.isEmpty() && !googleClientSecret.isEmpty();
    }
}
