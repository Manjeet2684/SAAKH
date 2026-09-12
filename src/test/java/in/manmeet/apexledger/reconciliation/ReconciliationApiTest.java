package in.manmeet.apexledger.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.ReconciliationRunRequest;
import in.manmeet.apexledger.api.SettlementRequest;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
class ReconciliationApiTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant SETTLED = Instant.parse("2026-09-12T10:00:00Z");

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

    @Autowired
    ReconciliationResultRepository results;

    @Test
    void sixStatusesAndRepeatableWindow() throws Exception {
        UUID matched = postTransfer(50L);
        ingest("s-match-" + matched, matched, 50L, "INR", SETTLED);
        UUID missingExternal = postTransfer(51L);
        ingest("s-missing-internal-null", null, 52L, "INR", SETTLED);
        ingest("s-missing-internal-unknown", UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), 53L, "INR", SETTLED);
        UUID amount = postTransfer(54L);
        ingest("s-amount-" + amount, amount, 40L, "INR", SETTLED);
        UUID currency = postTransfer(55L);
        ingest("s-currency-" + currency, currency, 55L, "USD", SETTLED);
        UUID duplicate = postTransfer(56L);
        ingest("s-dup-a-" + duplicate, duplicate, 56L, "INR", SETTLED);
        ingest("s-dup-b-" + duplicate, duplicate, 56L, "INR", SETTLED);
        Snapshot before = snapshot();

        JsonNode first = run(FROM, TO);
        assertEquals("COMPLETED", first.get("status").asText());
        assertEquals(first.get("exceptionCount").asInt(), first.get("mismatchCount").asInt());
        assertStatus(first, matched, "MATCHED");
        assertStatus(first, missingExternal, "MISSING_EXTERNAL");
        assertHasStatus(first, "MISSING_INTERNAL");
        assertStatus(first, amount, "AMOUNT_MISMATCH");
        assertStatus(first, currency, "CURRENCY_MISMATCH");
        assertEquals(2, countStatus(first, "DUPLICATE_EXTERNAL"));

        JsonNode second = run(FROM, TO);
        assertNotEquals(first.get("runId").asText(), second.get("runId").asText());
        assertEquals(first.get("matchedCount").asInt(), second.get("matchedCount").asInt());
        assertEquals(first.get("exceptionCount").asInt(), second.get("exceptionCount").asInt());
        assertEquals(first.get("totalInternal").asInt(), second.get("totalInternal").asInt());
        assertEquals(first.get("totalExternal").asInt(), second.get("totalExternal").asInt());

        mvc.perform(get("/internal/v1/reconciliation/runs/" + first.get("runId").asText())
                        .header("X-API-Key", "local-internal-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(before, snapshot());
    }

    @Test
    void outsideWindowAndFailedTransferAreMissingInternal() throws Exception {
        UUID live = postTransfer(60L);
        ingest("s-outside-" + live, live, 60L, "INR", Instant.parse("2026-08-15T10:00:00Z"));
        JsonNode outside = run(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
        assertStatus(outside, live, "MISSING_INTERNAL");

        UUID failedId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transfers (id, source_account_id, destination_account_id, amount_minor, currency, created_at, idempotency_key, status, completed_at)
                VALUES (?, ?, ?, 70, 'INR', TIMESTAMPTZ '2026-09-12T10:00:00Z', ?, 'FAILED', TIMESTAMPTZ '2026-09-12T10:00:00Z')
                """, failedId, DemoAccounts.ALICE, DemoAccounts.BOB, "failed-" + failedId);
        ingest("s-failed-" + failedId, failedId, 70L, "INR", SETTLED);
        JsonNode failed = run(FROM, TO);
        assertStatus(failed, failedId, "MISSING_INTERNAL");
    }

    @Test
    void invalidWindowAndAuth() throws Exception {
        mvc.perform(post("/internal/v1/reconciliation/runs")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReconciliationRunRequest(TO, FROM))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
        mvc.perform(post("/internal/v1/reconciliation/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReconciliationRunRequest(FROM, TO))))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode run(Instant from, Instant to) throws Exception {
        String json = mvc.perform(post("/internal/v1/reconciliation/runs")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReconciliationRunRequest(from, to))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(json);
    }

    private void ingest(String ref, UUID transferId, long amount, String currency, Instant settledAt) throws Exception {
        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest(ref, transferId, amount, currency, "SETTLED", settledAt))))
                .andExpect(status().isCreated());
    }

    private UUID postTransfer(long amount) throws Exception {
        String json = mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", "local-dev-key")
                        .header("Idempotency-Key", "p4-rec-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, amount, "INR"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("transferId").asText());
    }

    private static void assertStatus(JsonNode run, UUID transferId, String status) {
        for (JsonNode result : run.get("results")) {
            if (transferId.toString().equals(result.path("transferId").asText())) {
                assertEquals(status, result.get("status").asText());
                return;
            }
        }
        throw new AssertionError("No result for " + transferId);
    }

    private static void assertHasStatus(JsonNode run, String status) {
        for (JsonNode result : run.get("results")) {
            if (status.equals(result.get("status").asText())) {
                return;
            }
        }
        throw new AssertionError("Missing status " + status);
    }

    private static int countStatus(JsonNode run, String status) {
        int count = 0;
        for (JsonNode result : run.get("results")) {
            if (status.equals(result.get("status").asText())) {
                count++;
            }
        }
        return count;
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
