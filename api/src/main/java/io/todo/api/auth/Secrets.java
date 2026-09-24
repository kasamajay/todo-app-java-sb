package io.todo.api.auth;

import io.todo.api.store.JsonStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random values: the signing secret, IDs, 2FA codes, OAuth state, bootstrap passwords. */
public final class Secrets {

    public static final int SECRET_LEN = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {
    }

    /**
     * Read the HMAC signing secret from dataDir/secret.key, generating and
     * persisting a new random one on first run (or if the file is the wrong
     * length) so tokens remain valid across restarts.
     */
    public static byte[] loadOrCreateSecret(Path dataDir) {
        Path path = dataDir.resolve("secret.key");
        try {
            byte[] existing = Files.readAllBytes(path);
            if (existing.length == SECRET_LEN) {
                return existing;
            }
        } catch (NoSuchFileException e) {
            // first run
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] secret = bytes(SECRET_LEN);
        JsonStore.writeFileAtomic(path, secret);
        return secret;
    }

    public static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** A random 32-character hex identifier (16 random bytes), like Go's idgen.New. */
    public static String newId() {
        return HexFormat.of().formatHex(bytes(16));
    }

    /** A zero-padded 6-digit code for a two-factor login challenge (CSPRNG). */
    public static String sixDigitCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** URL-safe base64 (no padding) of n random bytes. */
    public static String urlSafe(int n) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(n));
    }
}
