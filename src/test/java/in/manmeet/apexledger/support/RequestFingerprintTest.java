package in.manmeet.apexledger.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestFingerprintTest {

    @Test
    void hashIsDeterministicAndCurrencyNormalized() {
        String a = RequestFingerprint.transferHash(DemoAccounts.ALICE, DemoAccounts.BOB, 1000L, "inr");
        String b = RequestFingerprint.transferHash(DemoAccounts.ALICE, DemoAccounts.BOB, 1000L, "INR");
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void differentRequestsProduceDifferentHashes() {
        String sameRoute = RequestFingerprint.transferHash(DemoAccounts.ALICE, DemoAccounts.BOB, 1000L, "INR");
        String otherDest = RequestFingerprint.transferHash(DemoAccounts.ALICE, DemoAccounts.CHARLIE, 1000L, "INR");
        String otherAmount = RequestFingerprint.transferHash(DemoAccounts.ALICE, DemoAccounts.BOB, 1001L, "INR");
        assertNotEquals(sameRoute, otherDest);
        assertNotEquals(sameRoute, otherAmount);
    }
}
