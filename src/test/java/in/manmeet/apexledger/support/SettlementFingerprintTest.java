package in.manmeet.apexledger.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SettlementFingerprintTest {

    @Test
    void hashIsCanonicalAndIgnoresCurrencyCase() {
        UUID transferId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Instant at = Instant.parse("2026-09-12T10:00:00Z");
        String a = SettlementFingerprint.hash("ref-1", transferId, 1000L, "inr", "SETTLED", at);
        String b = SettlementFingerprint.hash("ref-1", transferId, 1000L, "INR", "SETTLED", at);
        assertEquals(64, a.length());
        assertEquals(a, b);
    }

    @Test
    void nullTransferIdAndDifferentPayloadsDiffer() {
        Instant at = Instant.parse("2026-09-12T10:00:00Z");
        String missing = SettlementFingerprint.hash("ref-1", null, 1000L, "INR", "SETTLED", at);
        String present = SettlementFingerprint.hash("ref-1", UUID.randomUUID(), 1000L, "INR", "SETTLED", at);
        String otherAmount = SettlementFingerprint.hash("ref-1", null, 2000L, "INR", "SETTLED", at);
        assertNotEquals(missing, present);
        assertNotEquals(missing, otherAmount);
    }
}
