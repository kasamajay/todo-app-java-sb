package io.todo.api.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A small subset of Go's http.DetectContentType, used only when an upload's
 * multipart part has no Content-Type (browsers always send one).
 */
public final class ContentSniffer {

    private static final Object[][] SIGNATURES = {
            {"%PDF-".getBytes(StandardCharsets.ISO_8859_1), "application/pdf"},
            {new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, "image/png"},
            {new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/jpeg"},
            {"GIF87a".getBytes(StandardCharsets.ISO_8859_1), "image/gif"},
            {"GIF89a".getBytes(StandardCharsets.ISO_8859_1), "image/gif"},
            {"BM".getBytes(StandardCharsets.ISO_8859_1), "image/bmp"},
            {new byte[]{'P', 'K', 3, 4}, "application/zip"},
            {new byte[]{0x1f, (byte) 0x8b, 8}, "application/x-gzip"},
            {new byte[]{0, 0, 1, 0}, "image/x-icon"},
    };

    private ContentSniffer() {
    }

    public static String detect(byte[] data) {
        byte[] head = Arrays.copyOf(data, Math.min(data.length, 512));
        for (Object[] sig : SIGNATURES) {
            if (startsWith(head, (byte[]) sig[0])) {
                return (String) sig[1];
            }
        }
        if (head.length >= 12 && startsWith(head, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
                && new String(head, 8, 4, StandardCharsets.ISO_8859_1).equals("WEBP")) {
            return "image/webp";
        }
        String text = new String(head, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase();
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head") || text.startsWith("<body")) {
            return "text/html; charset=utf-8";
        }
        if (text.startsWith("<?xml")) {
            return "text/xml; charset=utf-8";
        }
        for (byte b : head) {
            if ((b & 0xff) < 0x20 && b != 0x09 && b != 0x0A && b != 0x0C && b != 0x0D && b != 0x1B) {
                return "application/octet-stream";
            }
        }
        return "text/plain; charset=utf-8";
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
