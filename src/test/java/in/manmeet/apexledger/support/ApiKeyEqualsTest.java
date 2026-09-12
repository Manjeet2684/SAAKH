package in.manmeet.apexledger.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyEqualsTest {

    @Test
    void equalKeysMatch() {
        assertTrue(ApiKeyEquals.matches("local-dev-key", "local-dev-key"));
    }

    @Test
    void unequalSameLengthKeysDoNotMatch() {
        assertFalse(ApiKeyEquals.matches("local-dev-key", "local-dev-kez"));
    }

    @Test
    void unequalDifferentLengthKeysDoNotMatch() {
        assertFalse(ApiKeyEquals.matches("short", "much-longer-key"));
    }

    @Test
    void missingProvidedKeyFails() {
        assertFalse(ApiKeyEquals.matches(null, "local-dev-key"));
    }

    @Test
    void blankExpectedKeyFailsClosed() {
        assertFalse(ApiKeyEquals.matches("local-dev-key", ""));
        assertFalse(ApiKeyEquals.matches("local-dev-key", "   "));
        assertFalse(ApiKeyEquals.matches("local-dev-key", null));
    }
}
