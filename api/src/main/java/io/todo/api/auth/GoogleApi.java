package io.todo.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.todo.api.json.GoJson;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Google OAuth 2.0 Authorization Code flow calls (decisions/0010): the token
 * exchange and userinfo fetch are plain HTTPS requests - no OAuth or JWT
 * library, since Google's servers do the token verification. A Spring bean
 * so tests can replace it with a stub.
 */
@Component
public class GoogleApi {

    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static class GoogleException extends Exception {
        public GoogleException(String message) {
            super(message);
        }
    }

    public record UserInfo(String sub, String email, boolean emailVerified) {
    }

    /** Build the consent-screen URL; query keys sorted, as Go's url.Values.Encode() does. */
    public static String authUrl(String clientId, String redirectUri, String state) {
        Map<String, String> q = new TreeMap<>();
        q.put("client_id", clientId);
        q.put("redirect_uri", redirectUri);
        q.put("response_type", "code");
        q.put("scope", "openid email profile");
        q.put("state", state);
        return AUTH_URL + "?" + form(q);
    }

    /** Exchange an authorization code for an access token. */
    public String exchangeCode(String clientId, String clientSecret, String redirectUri, String code) throws GoogleException {
        Map<String, String> body = new TreeMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("grant_type", "authorization_code");
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode tr = GoJson.MAPPER.readTree(resp.body());
            String accessToken = GoJson.text(tr, "access_token");
            if (resp.statusCode() != 200 || accessToken.isEmpty()) {
                String err = GoJson.text(tr, "error");
                throw new GoogleException(err.isEmpty()
                        ? "google token exchange failed: status " + resp.statusCode()
                        : "google token exchange failed: " + err);
            }
            return accessToken;
        } catch (IOException e) {
            throw new GoogleException(e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException("interrupted");
        }
    }

    /** Fetch the authenticated user's profile using an access token. */
    public UserInfo fetchUserInfo(String accessToken) throws GoogleException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(USERINFO_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new GoogleException("google userinfo request failed");
            }
            JsonNode b = GoJson.MAPPER.readTree(resp.body());
            UserInfo info = new UserInfo(GoJson.text(b, "sub"), GoJson.text(b, "email"),
                    b.path("email_verified").isBoolean() && b.get("email_verified").booleanValue());
            if (info.sub().isEmpty()) {
                throw new GoogleException("google userinfo response missing sub");
            }
            return info;
        } catch (IOException e) {
            throw new GoogleException(e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleException("interrupted");
        }
    }

    private static String form(Map<String, String> q) {
        return q.entrySet().stream()
                .map(e -> queryEscape(e.getKey()) + "=" + queryEscape(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** Go's url.QueryEscape (spaces as '+'). */
    public static String queryEscape(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
