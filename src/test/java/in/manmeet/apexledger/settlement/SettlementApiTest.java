package in.manmeet.apexledger.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.SettlementRequest;
import in.manmeet.apexledger.outbox.OutboxEventRepository;
import in.manmeet.apexledger.transfer.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SettlementApiTest {

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
    SettlementRecordRepository settlements;

    @Test
    void createReplayConflictAndUnknownTransferId() throws Exception {
        Snapshot before = snapshot();
        String ref = "ext-" + UUID.randomUUID();
        Instant settledAt = Instant.parse("2026-09-12T10:00:00Z");
        UUID unknown = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        MvcResult created = postSettlement(ref, unknown, 1000L, "inr", "SETTLED", settledAt.toString());
        assertEquals(201, created.getResponse().getStatus());
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult replay = postSettlement(ref, unknown, 1000L, "INR", "SETTLED", settledAt.toString());
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(id, objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText());
        assertEquals(1, countByReference(ref));

        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest(ref, unknown, 900L, "INR", "SETTLED", settledAt))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_CONFLICT"));

        SettlementRecord stored = settlements.findByExternalReference(ref).orElseThrow();
        assertEquals(1000L, stored.getAmountMinor());
        assertEquals("INR", stored.getCurrency());
        assertEquals(unknown, stored.getTransferId());
        assertEquals(before, snapshot());
    }

    @Test
    void nullTransferIdIsAcceptedAndInvalidInputRejected() throws Exception {
        String ref = "ext-null-" + UUID.randomUUID();
        postSettlement(ref, null, 500L, "INR", "SETTLED", "2026-09-12T11:00:00Z")
                .getResponse();
        assertEquals(201, postSettlement(ref + "-2", null, 500L, "INR", "SETTLED", "2026-09-12T11:00:00Z").getResponse().getStatus());

        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest("bad-status", null, 500L, "INR", "PENDING", Instant.parse("2026-09-12T11:00:00Z")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SETTLEMENT_STATUS"));

        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest("bad-amt", null, 0L, "INR", "SETTLED", Instant.parse("2026-09-12T11:00:00Z")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentIdenticalRequestsCreateOnceAndReplay() throws Exception {
        Snapshot before = snapshot();
        String ref = "ext-conc-" + UUID.randomUUID();
        Instant settledAt = Instant.parse("2026-09-12T12:00:00Z");
        UUID transferId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        int callers = 8;
        ConcurrentLinkedQueue<HttpCall> calls = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = launchConcurrent(
                callers,
                index -> postSettlement(ref, transferId, 1000L, "INR", "SETTLED", settledAt.toString()),
                calls
        );

        assertTrue(failures.isEmpty(), () -> failures.toString());
        assertEquals(callers, calls.size());
        assertTrue(calls.stream().noneMatch(call -> call.status() >= 500), () -> calls.toString());
        assertTrue(calls.stream().allMatch(call -> call.status() == 200 || call.status() == 201), () -> calls.toString());
        assertTrue(calls.stream().anyMatch(call -> call.status() == 201), () -> calls.toString());

        Set<String> ids = calls.stream()
                .map(call -> {
                    try {
                        return objectMapper.readTree(call.body()).get("id").asText();
                    } catch (Exception e) {
                        throw new IllegalStateException(call.body(), e);
                    }
                })
                .collect(Collectors.toSet());
        assertEquals(1, ids.size(), () -> calls.toString());
        assertEquals(1, countByReference(ref));
        SettlementRecord stored = settlements.findByExternalReference(ref).orElseThrow();
        assertEquals(ids.iterator().next(), stored.getId().toString());
        assertEquals(1000L, stored.getAmountMinor());
        assertEquals(before, snapshot());
    }

    @Test
    void concurrentConflictingPayloadsKeepOneRow() throws Exception {
        Snapshot before = snapshot();
        String ref = "ext-conflict-" + UUID.randomUUID();
        Instant settledAt = Instant.parse("2026-09-12T12:30:00Z");
        ConcurrentLinkedQueue<HttpCall> calls = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = launchConcurrent(
                8,
                index -> postSettlement(ref, null, 1000L + index, "INR", "SETTLED", settledAt.toString()),
                calls
        );

        assertTrue(failures.isEmpty(), () -> failures.toString());
        assertEquals(8, calls.size());
        assertTrue(calls.stream().noneMatch(call -> call.status() >= 500), () -> calls.toString());
        assertEquals(1, calls.stream().filter(call -> call.status() == 201).count(), () -> calls.toString());
        assertEquals(7, calls.stream().filter(call -> call.status() == 409).count(), () -> calls.toString());

        String createdBody = null;
        for (HttpCall call : calls) {
            if (call.status() == 201) {
                createdBody = call.body();
            } else {
                assertEquals("SETTLEMENT_CONFLICT", objectMapper.readTree(call.body()).get("code").asText(), call.body());
            }
        }

        assertTrue(createdBody != null);
        assertEquals(1, countByReference(ref));
        SettlementRecord stored = settlements.findByExternalReference(ref).orElseThrow();
        assertEquals(objectMapper.readTree(createdBody).get("id").asText(), stored.getId().toString());
        assertEquals(objectMapper.readTree(createdBody).get("amountMinor").asLong(), stored.getAmountMinor());
        assertEquals("INR", stored.getCurrency());
        assertEquals(before, snapshot());
    }

    @Test
    void missingInternalKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/internal/v1/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private ConcurrentLinkedQueue<Throwable> launchConcurrent(
            int callers,
            SettlementCall call,
            ConcurrentLinkedQueue<HttpCall> results
    ) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(callers);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < callers; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start");
                    }
                    MvcResult result = call.perform(index);
                    results.add(new HttpCall(result.getResponse().getStatus(), result.getResponse().getContentAsString()));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    finished.countDown();
                }
            });
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(finished.await(45, TimeUnit.SECONDS));
        pool.shutdownNow();
        return failures;
    }

    private long countByReference(String ref) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_records WHERE external_reference = ?", Long.class, ref);
        return count == null ? 0 : count;
    }

    @FunctionalInterface
    private interface SettlementCall {
        MvcResult perform(int index) throws Exception;
    }

    private MvcResult postSettlement(
            String ref,
            UUID transferId,
            long amount,
            String currency,
            String status,
            String settledAt
    ) throws Exception {
        return mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest(ref, transferId, amount, currency, status, Instant.parse(settledAt)))))
                .andReturn();
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

    private record HttpCall(int status, String body) {}
}
