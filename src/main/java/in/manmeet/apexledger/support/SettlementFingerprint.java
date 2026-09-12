package in.manmeet.apexledger.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class SettlementFingerprint {

    private SettlementFingerprint() {}

    public static String hash(
            String externalReference,
            UUID transferId,
            long amountMinor,
            String currency,
            String settlementStatus,
            Instant settledAt
    ) {
        String transfer = transferId == null ? "" : transferId.toString().toLowerCase();
        String canonical = externalReference
                + "|" + transfer
                + "|" + amountMinor
                + "|" + currency.toUpperCase()
                + "|" + settlementStatus
                + "|" + settledAt.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
