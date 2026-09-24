package io.todo.api.controllers;

import io.todo.api.AppState;
import io.todo.api.auth.GoogleApi;
import io.todo.api.auth.Secrets;
import io.todo.api.config.AppConfig;
import io.todo.api.json.GoJson;
import io.todo.api.model.User;
import io.todo.api.web.ApiError;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static io.todo.api.auth.GoogleApi.queryEscape;

/** Sign in with Google - OAuth 2.0 Authorization Code flow (Go: auth_google_handler.go, decisions/0010). */
@RestController
public class GoogleAuthController {

    static final String STATE_COOKIE = "google_oauth_state";
    static final String STATE_PATH = "/api/auth/google";
    static final Duration STATE_TTL = Duration.ofMinutes(5);

    private final AppState state;
    private final AppConfig config;
    private final GoogleApi google;

    public GoogleAuthController(AppState state, AppConfig config, GoogleApi google) {
        this.state = state;
        this.config = config;
        this.google = google;
    }

    /** Set a short-lived CSRF state cookie and redirect to Google's consent screen. */
    @GetMapping("/api/auth/google/login")
    public ResponseEntity<byte[]> login() {
        if (!config.googleConfigured()) {
            throw new ApiError(503, "google_oauth_not_configured", "Google sign-in is not configured on this server");
        }
        String oauthState = Secrets.urlSafe(32);
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, oauthState)
                .path(STATE_PATH).maxAge(STATE_TTL).httpOnly(true).sameSite("Lax").build();
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, GoogleApi.authUrl(config.googleClientId(), config.googleRedirectUri(), oauthState))
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    /**
     * Exchange the code for an access token, fetch the Google profile, resolve
     * it to a local account (by Google ID, then auto-link by verified email,
     * then create), and redirect back to the frontend with the app token in
     * the URL fragment (never a query param, so it never reaches access logs).
     */
    @GetMapping("/api/auth/google/callback")
    public ResponseEntity<byte[]> callback(HttpServletRequest req,
                                           @RequestParam(name = "error", required = false, defaultValue = "") String error,
                                           @RequestParam(name = "state", required = false, defaultValue = "") String oauthState,
                                           @RequestParam(name = "code", required = false, defaultValue = "") String code) {
        if (!config.googleConfigured()) {
            return fail("google_oauth_not_configured", false);
        }
        if (!error.isEmpty()) {
            return fail("google_denied", false);
        }

        // One-time use: the state cookie is cleared on every response from here on.
        String cookie = cookieValue(req);
        if (cookie.isEmpty()) {
            return fail("google_state_missing", true);
        }
        if (oauthState.isEmpty() || !oauthState.equals(cookie)) {
            return fail("google_state_mismatch", true);
        }
        if (code.isEmpty()) {
            return fail("google_missing_code", true);
        }

        String accessToken;
        try {
            accessToken = google.exchangeCode(config.googleClientId(), config.googleClientSecret(), config.googleRedirectUri(), code);
        } catch (GoogleApi.GoogleException e) {
            AuthController.LOG.error("google token exchange failed: {}", e.getMessage());
            return fail("google_exchange_failed", true);
        }

        GoogleApi.UserInfo profile;
        try {
            profile = google.fetchUserInfo(accessToken);
        } catch (GoogleApi.GoogleException e) {
            AuthController.LOG.error("google userinfo fetch failed: {}", e.getMessage());
            return fail("google_userinfo_failed", true);
        }
        if (profile.email().isEmpty() || !profile.emailVerified()) {
            return fail("google_email_unverified", true);
        }

        String email = profile.email().strip().toLowerCase();
        User user = state.users().findByGoogleId(profile.sub());
        if (user == null) {
            try {
                User existing = state.users().findByEmail(email);
                if (existing != null) {
                    // Auto-link: Google has already verified this email, so
                    // treat it as the same account rather than a duplicate.
                    existing.googleId = profile.sub();
                    state.users().put(existing.id, existing);
                    user = existing;
                } else {
                    user = new User();
                    user.id = Secrets.newId();
                    user.email = email;
                    user.googleId = profile.sub();
                    user.createdAt = GoJson.now();
                    state.users().put(user.id, user);
                }
            } catch (RuntimeException e) {
                return fail("google_internal_error", true);
            }
        }

        if (user.isLocked()) {
            return fail("google_account_locked", true);
        }

        if (user.twoFactorEnabled) {
            User updated;
            try {
                updated = AuthController.issueTwoFactorChallenge(state, user);
            } catch (RuntimeException e) {
                return fail("google_internal_error", true);
            }
            return redirect(config.frontendBaseUrl() + "/#google_2fa_required=" + queryEscape(updated.id), true);
        }

        return redirect(config.frontendBaseUrl() + "/#google_token=" + queryEscape(AuthController.mint(state, user)), true);
    }

    private ResponseEntity<byte[]> fail(String code, boolean clearCookie) {
        return redirect(config.frontendBaseUrl() + "/#google_error=" + queryEscape(code), clearCookie);
    }

    private static ResponseEntity<byte[]> redirect(String location, boolean clearCookie) {
        var b = ResponseEntity.status(302).header(HttpHeaders.LOCATION, location);
        if (clearCookie) {
            b.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(STATE_COOKIE, "").path(STATE_PATH).maxAge(0).build().toString());
        }
        return b.build();
    }

    private static String cookieValue(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return "";
        }
        for (Cookie c : req.getCookies()) {
            if (c.getName().equals(STATE_COOKIE)) {
                return c.getValue() == null ? "" : c.getValue();
            }
        }
        return "";
    }
}
