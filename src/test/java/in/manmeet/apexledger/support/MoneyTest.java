package in.manmeet.apexledger.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void acceptsZeroAndPositivePaise() {
        assertEquals(0L, Money.paise(0).minorUnits());
        assertEquals(50000L, Money.paise(50_000).minorUnits());
    }

    @Test
    void rejectsNegativePaise() {
        assertThrows(IllegalArgumentException.class, () -> Money.paise(-1));
    }
}
