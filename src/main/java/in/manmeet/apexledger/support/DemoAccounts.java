package in.manmeet.apexledger.support;

import java.util.UUID;

/**
 * Fixed IDs used by Flyway seed. Tests and demo scripts must reuse these.
 */
public final class DemoAccounts {

    public static final UUID SYSTEM_FLOAT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    public static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    public static final UUID CHARLIE = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    /** Seeded customer opening balance: ₹1,000.00 */
    public static final long CUSTOMER_OPENING_MINOR = 100_000L;

    /** Seeded total float across all accounts: ₹10,000,000.00 */
    public static final long SEEDED_TOTAL_MINOR = 1_000_000_000L;

    private DemoAccounts() {}
}
