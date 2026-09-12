package in.manmeet.apexledger.integrity;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.outbox.OutboxEventRepository;
import in.manmeet.apexledger.support.DemoAccounts;
import in.manmeet.apexledger.transfer.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FinancialIntegrityApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferRepository transfers;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Test
    void missingOrInvalidInternalKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/internal/v1/integrity")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cleanSeedPassesAndSystemBalanceIsInconclusive() throws Exception {
        Snapshot before = snapshot();
        mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "local-internal-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.checks.doubleEntry").value("PASS"))
                .andExpect(jsonPath("$.checks.amountConsistency").value("PASS"))
                .andExpect(jsonPath("$.checks.currencyConsistency").value("PASS"))
                .andExpect(jsonPath("$.checks.globalDoubleEntry").value("PASS"))
                .andExpect(jsonPath("$.checks.customerBalance").value("PASS"))
                .andExpect(jsonPath("$.checks.systemBalance").value("INCONCLUSIVE"));
        assertEquals(before, snapshot());
    }

    @Test
    void missingLedgerLineIsDetectedAndRestored() throws Exception {
        UUID transferId = postTransfer(40L);
        Map<String, Object> debit = jdbc.queryForMap(
                "SELECT id, transfer_id, account_id, direction, amount_minor, created_at FROM ledger_lines WHERE transfer_id = ? AND direction = 'DEBIT'",
                transferId);
        jdbc.update("DELETE FROM ledger_lines WHERE id = ?", debit.get("id"));
        try {
            mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "local-internal-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAIL"))
                    .andExpect(jsonPath("$.checks.doubleEntry").value("FAIL"));
        } finally {
            jdbc.update(
                    "INSERT INTO ledger_lines (id, transfer_id, account_id, direction, amount_minor, created_at) VALUES (?,?,?,?,?,?)",
                    debit.get("id"), debit.get("transfer_id"), debit.get("account_id"), debit.get("direction"),
                    debit.get("amount_minor"), debit.get("created_at"));
        }
    }

    @Test
    void incorrectLedgerAmountIsDetectedAndRestored() throws Exception {
        UUID transferId = postTransfer(41L);
        jdbc.update("UPDATE ledger_lines SET amount_minor = amount_minor + 1 WHERE transfer_id = ? AND direction = 'DEBIT'", transferId);
        try {
            mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "local-internal-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAIL"))
                    .andExpect(jsonPath("$.checks.amountConsistency").value("FAIL"));
        } finally {
            jdbc.update("UPDATE ledger_lines SET amount_minor = amount_minor - 1 WHERE transfer_id = ? AND direction = 'DEBIT'", transferId);
        }
    }

    @Test
    void currencyInconsistencyIsDetectedAndRestored() throws Exception {
        jdbc.update("UPDATE accounts SET currency = 'USD' WHERE id = ?", DemoAccounts.ALICE);
        try {
            mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "local-internal-key"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAIL"))
                    .andExpect(jsonPath("$.checks.currencyConsistency").value("FAIL"));
        } finally {
            jdbc.update("UPDATE accounts SET currency = 'INR' WHERE id = ?", DemoAccounts.ALICE);
        }
    }

    private UUID postTransfer(long amount) throws Exception {
        String body = objectMapper.writeValueAsString(
                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, amount, "INR"));
        String json = mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", "local-dev-key")
                        .header("Idempotency-Key", "p4-int-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("transferId").asText());
    }

    private Snapshot snapshot() {
        return new Snapshot(
                jdbc.queryForObject("SELECT COALESCE(SUM(available_balance_minor),0) FROM accounts", Long.class),
                transfers.count(),
                jdbc.queryForObject("SELECT COUNT(*) FROM ledger_lines", Long.class),
                outboxEvents.count()
        );
    }

    private record Snapshot(Long balances, long transfers, Long ledger, long outbox) {}
}
