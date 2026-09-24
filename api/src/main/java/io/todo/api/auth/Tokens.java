package io.todo.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.todo.api.json.GoJson;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Custom HMAC-SHA256 API tokens (not JWT), format-compatible with the Go API:
 * {@code base64url_nopad(json {"uid","adm","exp"}) + "." + hex(HMAC-SHA256(secret, first part))}.
 * Stateless - there is no server-side session store, so logout is a
 * client-side discard (decisions/0003). A token minted by any of the three
 * backends verifies in the others given the same secret.key.
 */
public final class Tokens {

    public static final Duration TTL = Duration.ofHours(24);

    private static final Pattern B64URL = Pattern.compile("^[A-Za-z0-9_-]*$");

    private Tokens() {
    }

    public record Claims(String userId, boolean isAdmin, OffsetDateTime expiresAt) {
    }

    public static class TokenException extends Exception {
        public TokenException(String message) {
            super(message, null, false, false);
        }
    }

    public static final class MalformedToken extends TokenException {
        public MalformedToken() {
            super("malformed token");
        }
    }

    public static final class InvalidToken extends TokenException {
        public InvalidToken() {
            super("invalid token signature");
        }
    }

    public static final class ExpiredToken extends TokenException {
        public ExpiredToken() {
            super("token expired");
        }
    }

    public static String mint(byte[] secret, Claims c) {
        ObjectNode payload = GoJson.object();
        payload.put("uid", c.userId());
        payload.put("adm", c.isAdmin());
        payload.put("exp", GoJson.formatTime(c.expiresAt()));
        String json = new String(GoJson.compact(payload), StandardCharsets.UTF_8).strip();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(secret, encoded);
    }

    public static Claims verify(byte[] secret, String token) throws TokenException {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new MalformedToken();
        }
        String encoded = token.substring(0, dot);
        String providedSig = token.substring(dot + 1);

        if (!MessageDigest.isEqual(sign(secret, encoded).getBytes(StandardCharsets.UTF_8),
                providedSig.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidToken();
        }

        Claims claims;
        try {
            // Mirror Go's base64.RawURLEncoding: URL alphabet, no padding.
            if (!B64URL.matcher(encoded).matches()) {
                throw new MalformedToken();
            }
            JsonNode c = GoJson.MAPPER.readTree(Base64.getUrlDecoder().decode(encoded));
            claims = new Claims(GoJson.text(c, "uid"), c.path("adm").asBoolean(false),
                    GoJson.parseTime(GoJson.text(c, "exp")));
        } catch (MalformedToken e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedToken();
        }

        if (GoJson.now().isAfter(claims.expiresAt())) {
            throw new ExpiredToken();
        }
        return claims;
    }

    private static String sign(byte[] secret, String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
