package in.manmeet.apexledger.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String transferHash(UUID sourceAccountId, UUID destinationAccountId, long amountMinor, String currency) {
        String canonical = sourceAccountId + "|" + destinationAccountId + "|" + amountMinor + "|" + currency.toUpperCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
