package io.todo.api.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * PBKDF2-HMAC-SHA512 (RFC 8018) with exactly the Go API's parameters, so
 * password hashes are interchangeable across the Go, Python and Java
 * backends.
 *
 * <p>Implemented on javax.crypto.Mac over the password's UTF-8 bytes rather
 * than JCE's "PBKDF2WithHmacSHA512" SecretKeyFactory, whose PBEKeySpec takes
 * a char[] and so can't be guaranteed to see the same bytes Go hashes
 * (decisions/0002).
 */
public final class Passwords {

    public static final int ITERATIONS = 100_000;
    public static final int KEY_LEN = 64; // SHA-512 output size
    public static final int SALT_LEN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public record Hashed(byte[] hash, byte[] salt) {
    }

    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int keyLen) {
        try {
            Mac prf = Mac.getInstance("HmacSHA512");
            // HMAC with an empty key is valid; SecretKeySpec rejects empty
            // arrays, so substitute the equivalent all-zero block-size key.
            prf.init(new SecretKeySpec(password.length == 0 ? new byte[128] : password, "HmacSHA512"));
            int hLen = prf.getMacLength();
            int blocks = (keyLen + hLen - 1) / hLen;
            byte[] dk = new byte[blocks * hLen];
            for (int block = 1; block <= blocks; block++) {
                prf.update(salt);
                prf.update(ByteBuffer.allocate(4).putInt(block).array());
                byte[] u = prf.doFinal();
                byte[] t = u.clone();
                for (int i = 1; i < iterations; i++) {
                    u = prf.doFinal(u);
                    for (int j = 0; j < t.length; j++) {
                        t[j] ^= u[j];
                    }
                }
                System.arraycopy(t, 0, dk, (block - 1) * hLen, hLen);
            }
            return Arrays.copyOf(dk, keyLen);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Generate a random salt and derive a PBKDF2-HMAC-SHA512 hash of plaintext. */
    public static Hashed hash(String plaintext) {
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        return new Hashed(pbkdf2(plaintext.getBytes(StandardCharsets.UTF_8), salt, ITERATIONS, KEY_LEN), salt);
    }

    /** Recompute the hash for plaintext with salt and compare in constant time. */
    public static boolean verify(String plaintext, byte[] hash, byte[] salt) {
        byte[] computed = pbkdf2(plaintext.getBytes(StandardCharsets.UTF_8),
                salt == null ? new byte[0] : salt, ITERATIONS, KEY_LEN);
        return MessageDigest.isEqual(computed, hash == null ? new byte[0] : hash);
    }
}
