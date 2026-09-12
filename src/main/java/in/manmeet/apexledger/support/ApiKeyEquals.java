package in.manmeet.apexledger.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Compares API keys by SHA-256 digest so {@link MessageDigest#isEqual} always
 * sees two 32-byte arrays. Missing or blank configured keys fail closed.
 */
public final class ApiKeyEquals {

    private ApiKeyEquals() {}

    public static boolean matches(String provided, String expected) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(provided), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
