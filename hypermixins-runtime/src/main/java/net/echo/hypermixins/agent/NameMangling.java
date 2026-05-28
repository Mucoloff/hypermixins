package net.echo.hypermixins.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Mangled-name algorithm shared with the KSP processor. Must stay byte-for-byte identical with
 * {@code net.echo.hypermixins.processor.NameMangling} or compile-time-precomputed names will
 * not line up with runtime-produced synthetic helpers.
 */
public final class NameMangling {

    private NameMangling() {}

    public static String mangledOriginalName(String methodName, String descriptor) {
        return "__original$" + methodName + "$" + sha1Hex16(descriptor);
    }

    public static String dispatchName(String methodName, String descriptor) {
        return "__dispatch$" + methodName + "$" + sha1Hex16(descriptor);
    }

    public static String sha1Hex16(String input) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
