package in.manmeet.apexledger;

import in.manmeet.apexledger.account.AccountRepository;
import in.manmeet.apexledger.support.DemoAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FoundationSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    AccountRepository accounts;

    @Test
    void flywaySeedCreatesDeterministicDemoAccounts() {
        var alice = accounts.findById(DemoAccounts.ALICE);
        assertTrue(alice.isPresent());
        assertEquals("Alice Wallet", alice.get().getName());
        assertEquals(DemoAccounts.CUSTOMER_OPENING_MINOR, alice.get().getAvailableBalanceMinor());
        assertEquals("INR", alice.get().getCurrency());

        long total = accounts.findAll().stream()
                .mapToLong(account -> account.getAvailableBalanceMinor())
                .sum();
        assertEquals(DemoAccounts.SEEDED_TOTAL_MINOR, total);
    }
}
