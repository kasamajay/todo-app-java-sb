package io.todo.api.auth;

import io.todo.api.json.GoJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports of todo-app api/internal/auth/{pbkdf2,token,google}_test.go, plus the
 * cross-backend format checks.
 */
public class AuthPrimitivesTest {

    // --- pbkdf2_test.go (same vectors as the Go and Python suites) -------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "iterations=1|password|salt|1|867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            "iterations=2|password|salt|2|e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53cf76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
            "iterations=4096|password|salt|4096|d197b1b33db0143e018b12f3d1d1479e6cdebdcc97c5c0f87f6902e072f457b5143f30602641b3d55cd335988cb36b84376060ecd532e039b742a239434af2d5",
            "long password and salt|passwordPASSWORDpassword|saltSALTsaltSALTsaltSALTsaltSALTsalt|4096|8c0511f4c6e597c6ac6315d8f0362e225f3c501495ba23b868c005174dc4ee71115b59f9e60cd9532fa33e0f75aefe30225c583a186cd82bd4daea9724a3d3b8",
    })
    void pbkdf2KeyKnownVectors(String name, String password, String salt, int iterations, String wantHex) {
        byte[] got = Passwords.pbkdf2(password.getBytes(StandardCharsets.UTF_8), salt.getBytes(StandardCharsets.UTF_8), iterations, 64);
        assertThat(HexFormat.of().formatHex(got)).isEqualTo(wantHex);
    }

    @Test
    void pbkdf2KeyEmbeddedNullBytes() {
        byte[] got = Passwords.pbkdf2("pass\0word".getBytes(StandardCharsets.UTF_8), "sa\0lt".getBytes(StandardCharsets.UTF_8), 4096, 64);
        assertThat(HexFormat.of().formatHex(got)).isEqualTo(
                "9d9e9c4cd21fe4be24d5b8244c759665f39d98fc12a9ca759bb021db3cfadf345844aebe70dd8b2f6966f25f3613e1187bbd24ed2ca43ed13b246e4675be7ab9");
    }

    @Test
    void pbkdf2KeyDeterministicAndDistinct() {
        byte[] a = Passwords.pbkdf2("pw".getBytes(), "salt1".getBytes(), 1000, 64);
        assertThat(a).isEqualTo(Passwords.pbkdf2("pw".getBytes(), "salt1".getBytes(), 1000, 64));
        assertThat(a).isNotEqualTo(Passwords.pbkdf2("pw".getBytes(), "salt2".getBytes(), 1000, 64));
    }

    @Test
    void pbkdf2KeyShortKeyLen() {
        byte[] got = Passwords.pbkdf2("password".getBytes(), "salt".getBytes(), 1, 16);
        assertThat(got).hasSize(16);
        assertThat(got).isEqualTo(Arrays.copyOf(Passwords.pbkdf2("password".getBytes(), "salt".getBytes(), 1, 64), 16));
    }

    @Test
    void hashParametersMatchGoBackend() {
        assertThat(new int[]{Passwords.ITERATIONS, Passwords.KEY_LEN, Passwords.SALT_LEN}).containsExactly(100_000, 64, 16);
        Passwords.Hashed h = Passwords.hash("correct horse");
        assertThat(h.hash()).hasSize(64);
        assertThat(h.salt()).hasSize(16);
        assertThat(Passwords.verify("correct horse", h.hash(), h.salt())).isTrue();
        assertThat(Passwords.verify("wrong horse", h.hash(), h.salt())).isFalse();
    }

    // --- token_test.go ----------------------------------------------------------

    private static Tokens.Claims claims(String uid, long minutes) {
        return new Tokens.Claims(uid, false, GoJson.now().plusMinutes(minutes));
    }

    @Test
    void mintAndVerifyRoundTrip() throws Exception {
        byte[] secret = "test-secret-32-bytes-long-------".getBytes();
        String token = Tokens.mint(secret, claims("u1", 60));
        assertThat(token).contains(".");
        Tokens.Claims got = Tokens.verify(secret, token);
        assertThat(got.userId()).isEqualTo("u1");
        assertThat(got.isAdmin()).isFalse();
    }

    @Test
    void verifyRejectsTamperedSignature() {
        String token = Tokens.mint("secret".getBytes(), claims("u1", 60));
        String tampered = token.substring(0, token.indexOf('.')) + "." + "0".repeat(64);
        assertThatThrownBy(() -> Tokens.verify("secret".getBytes(), tampered)).isInstanceOf(Tokens.InvalidToken.class);
    }

    @Test
    void verifyRejectsWrongSecret() {
        String token = Tokens.mint("secret-a".getBytes(), claims("u1", 60));
        assertThatThrownBy(() -> Tokens.verify("secret-b".getBytes(), token)).isInstanceOf(Tokens.InvalidToken.class);
    }

    @Test
    void verifyRejectsExpiredToken() {
        String token = Tokens.mint("secret".getBytes(), claims("u1", -1));
        assertThatThrownBy(() -> Tokens.verify("secret".getBytes(), token)).isInstanceOf(Tokens.ExpiredToken.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-dot-here", ".", "abc.", ".def"})
    void verifyRejectsMalformedToken(String tok) {
        assertThatThrownBy(() -> Tokens.verify("secret".getBytes(), tok)).isInstanceOf(Tokens.MalformedToken.class);
    }

    @Test
    void tokenFormatMatchesGo() throws Exception {
        String token = Tokens.mint("secret".getBytes(), new Tokens.Claims("abc", true, GoJson.now().plusHours(1)));
        String encoded = token.substring(0, token.indexOf('.'));
        assertThat(encoded).doesNotContain("=");
        String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertThat(payload).startsWith("{\"uid\":\"abc\",\"adm\":true,\"exp\":\"").endsWith("Z\"}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(), "HmacSHA256"));
        assertThat(token.substring(token.indexOf('.') + 1)).isEqualTo(HexFormat.of().formatHex(mac.doFinal(encoded.getBytes())));
    }

    @Test
    void verifiesATokenMintedByTheGoBackend() throws Exception {
        // Built exactly as Go's auth.Mint does, with a nanosecond-precision exp.
        String payload = "{\"uid\":\"go-user\",\"adm\":false,\"exp\":\"2999-01-02T03:04:05.123456789Z\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("k".getBytes(), "HmacSHA256"));
        String token = encoded + "." + HexFormat.of().formatHex(mac.doFinal(encoded.getBytes()));
        Tokens.Claims got = Tokens.verify("k".getBytes(), token);
        assertThat(got.userId()).isEqualTo("go-user");
        assertThat(got.expiresAt().getNano()).isEqualTo(123456789);
    }

    // --- google_test.go -----------------------------------------------------------

    @Test
    void randomStateNonEmptyAndDistinct() {
        String a = Secrets.urlSafe(32);
        String b = Secrets.urlSafe(32);
        assertThat(a).isNotEmpty().isNotEqualTo(b);
    }

    @Test
    void googleAuthUrl() {
        URI u = URI.create(GoogleApi.authUrl("client-123", "http://localhost:5173/api/auth/google/callback", "state-abc"));
        assertThat(u.getScheme() + "://" + u.getHost() + u.getPath()).isEqualTo(GoogleApi.AUTH_URL);
        Map<String, String> q = query(u.getRawQuery());
        assertThat(q).containsEntry("client_id", "client-123")
                .containsEntry("redirect_uri", "http://localhost:5173/api/auth/google/callback")
                .containsEntry("response_type", "code")
                .containsEntry("state", "state-abc");
        assertThat(q.get("scope")).contains("openid").contains("email");
    }

    public static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }
}
