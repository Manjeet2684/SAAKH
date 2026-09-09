package in.manmeet.apexledger.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.api.TransferResponse;
import in.manmeet.apexledger.ledger.LedgerDirection;
import in.manmeet.apexledger.ledger.LedgerLine;
import in.manmeet.apexledger.ledger.LedgerLineRepository;
import in.manmeet.apexledger.outbox.OutboxEvent;
import in.manmeet.apexledger.outbox.OutboxEventRepository;
import in.manmeet.apexledger.support.DemoAccounts;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TransferApiTest {

    private static final String API_KEY = "local-dev-key";
    private static final long TRANSFER_MINOR = 1_000L;

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
    LedgerLineRepository ledgerLines;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Test
    void authenticationStillProtectsAccountAndTransferApis() throws Exception {
        mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE).header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Wallet"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", newKey("auth"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, TRANSFER_MINOR)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successfulTransferMovesBalancesWritesLedgerAndOutbox() throws Exception {
        long aliceBefore = balance(DemoAccounts.ALICE);
        long bobBefore = balance(DemoAccounts.BOB);
        long totalBefore = totalMoney();

        TransferResponse body = postTransfer(newKey("ok"), DemoAccounts.ALICE, DemoAccounts.BOB, TRANSFER_MINOR);

        assertNotNull(body.transferId());
        assertEquals(DemoAccounts.ALICE, body.sourceAccountId());
        assertEquals(DemoAccounts.BOB, body.destinationAccountId());
        assertEquals(TRANSFER_MINOR, body.amountMinor());
        assertEquals("INR", body.currency());
        assertEquals("COMPLETED", body.status());

        assertEquals(aliceBefore - TRANSFER_MINOR, balance(DemoAccounts.ALICE));
        assertEquals(bobBefore + TRANSFER_MINOR, balance(DemoAccounts.BOB));
        assertEquals(totalBefore, totalMoney());

        List<LedgerLine> lines = ledgerLines.findByTransferId(body.transferId());
        assertEquals(2, lines.size());
        assertEquals(TRANSFER_MINOR, lines.stream().filter(l -> l.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow().getAmountMinor());
        assertEquals(TRANSFER_MINOR, lines.stream().filter(l -> l.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow().getAmountMinor());

        List<OutboxEvent> events = outboxEvents.findByAggregateIdAndEventType(body.transferId(), OutboxEvent.TRANSFER_COMPLETED);
        assertEquals(1, events.size());
        assertNull(events.getFirst().getPublishedAt());
        assertEquals(OutboxEvent.TRANSFER_COMPLETED, events.getFirst().getPayload().path("eventType").asText());
        assertEquals("TRANSFER", events.getFirst().getPayload().path("aggregateType").asText());
        assertEquals(body.transferId().toString(), events.getFirst().getPayload().path("aggregateId").asText());
    }

    @Test
    void duplicateIdempotencyKeyDoesNotTransferTwice() throws Exception {
        String key = newKey("dup");
        long aliceBefore = balance(DemoAccounts.ALICE);
        long bobBefore = balance(DemoAccounts.BOB);
        long transferCountBefore = transfers.countByIdempotencyKey(key);

        TransferResponse first = postTransfer(key, DemoAccounts.ALICE, DemoAccounts.BOB, 250L);
        TransferResponse second = postTransfer(key, DemoAccounts.ALICE, DemoAccounts.BOB, 250L);

        assertEquals(first.transferId(), second.transferId());
        assertEquals(first.status(), second.status());
        assertEquals(aliceBefore - 250L, balance(DemoAccounts.ALICE));
        assertEquals(bobBefore + 250L, balance(DemoAccounts.BOB));
        assertEquals(transferCountBefore + 1, transfers.countByIdempotencyKey(key));
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(first.transferId(), OutboxEvent.TRANSFER_COMPLETED));
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestIsRejected() throws Exception {
        String key = newKey("reuse");
        TransferResponse first = postTransfer(key, DemoAccounts.ALICE, DemoAccounts.BOB, 100L);
        long aliceAfterFirst = balance(DemoAccounts.ALICE);

        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.CHARLIE, 100L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(aliceAfterFirst, balance(DemoAccounts.ALICE));
        assertEquals(1, transfers.countByIdempotencyKey(key));
        assertEquals(first.transferId(), transfers.findAll().stream()
                .filter(t -> key.equals(t.getIdempotencyKey()))
                .findFirst()
                .orElseThrow()
                .getId());
    }

    @Test
    void insufficientBalanceRejectsWithoutPartialUpdates() throws Exception {
        long aliceBefore = balance(DemoAccounts.ALICE);
        long bobBefore = balance(DemoAccounts.BOB);
        long totalBefore = totalMoney();
        long transferCountBefore = transfers.count();
        long outboxBefore = outboxEvents.count();
        long tooMuch = aliceBefore + 1;

        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", newKey("nsf"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, tooMuch)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertEquals(aliceBefore, balance(DemoAccounts.ALICE));
        assertEquals(bobBefore, balance(DemoAccounts.BOB));
        assertEquals(totalBefore, totalMoney());
        assertEquals(transferCountBefore, transfers.count());
        assertEquals(outboxBefore, outboxEvents.count());
    }

    @Test
    void rejectsSelfTransferMissingAccountAndMissingIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, TRANSFER_MINOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, TRANSFER_MINOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", newKey("self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.ALICE, TRANSFER_MINOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_TRANSFER"));

        UUID missing = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", newKey("missing"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, missing, TRANSFER_MINOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void rejectsClosedSystemAndNonPositiveAmounts() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", newKey("system"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.SYSTEM_FLOAT, DemoAccounts.BOB, TRANSFER_MINOR)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ELIGIBLE"));

        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", newKey("zero"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, 0L)))
                .andExpect(status().isBadRequest());

        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", DemoAccounts.CHARLIE);
        try {
            mvc.perform(post("/api/v1/transfers")
                            .header("X-API-Key", API_KEY)
                            .header("Idempotency-Key", newKey("frozen"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferJson(DemoAccounts.CHARLIE, DemoAccounts.BOB, 10L)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OPEN"));
        } finally {
            jdbc.update("UPDATE accounts SET status = 'OPEN' WHERE id = ?", DemoAccounts.CHARLIE);
        }
    }

    @Test
    void concurrentDuplicateIdempotencyKeyPostsOnlyOnce() throws Exception {
        String key = newKey("conc");
        long amount = 400L;
        long aliceBefore = balance(DemoAccounts.ALICE);
        long bobBefore = balance(DemoAccounts.BOB);
        long totalBefore = totalMoney();
        String json = transferJson(DemoAccounts.ALICE, DemoAccounts.BOB, amount);

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(callers);
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start");
                    }
                    MvcResult result = mvc.perform(post("/api/v1/transfers")
                                    .header("X-API-Key", API_KEY)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                            .andReturn();
                    statuses.add(result.getResponse().getStatus());
                    bodies.add(result.getResponse().getContentAsString());
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

        assertTrue(failures.isEmpty(), () -> failures.toString());
        assertEquals(callers, statuses.size());
        assertTrue(statuses.stream().allMatch(status -> status == 201), () -> statuses + " " + bodies);

        Set<UUID> transferIds = bodies.stream()
                .map(body -> {
                    try {
                        return objectMapper.readValue(body, TransferResponse.class).transferId();
                    } catch (Exception e) {
                        throw new IllegalStateException(body, e);
                    }
                })
                .collect(Collectors.toSet());
        assertEquals(1, transferIds.size());
        UUID transferId = transferIds.iterator().next();

        assertEquals(aliceBefore - amount, balance(DemoAccounts.ALICE));
        assertEquals(bobBefore + amount, balance(DemoAccounts.BOB));
        assertEquals(totalBefore, totalMoney());
        assertEquals(1, transfers.countByIdempotencyKey(key));

        List<LedgerLine> lines = ledgerLines.findByTransferId(transferId);
        assertEquals(2, lines.size());
        assertEquals(1, lines.stream().filter(line -> line.getDirection() == LedgerDirection.DEBIT).count());
        assertEquals(1, lines.stream().filter(line -> line.getDirection() == LedgerDirection.CREDIT).count());
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(transferId, OutboxEvent.TRANSFER_COMPLETED));
    }

    private TransferResponse postTransfer(String key, UUID source, UUID dest, long amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, dest, amount)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TransferResponse.class);
    }

    private String transferJson(UUID source, UUID dest, long amount) throws Exception {
        return objectMapper.writeValueAsString(new TransferRequest(source, dest, amount, "INR"));
    }

    private long balance(UUID accountId) {
        Long value = jdbc.queryForObject(
                "SELECT available_balance_minor FROM accounts WHERE id = ?",
                Long.class,
                accountId
        );
        assertNotNull(value);
        return value;
    }

    private long totalMoney() {
        Long value = jdbc.queryForObject("SELECT SUM(available_balance_minor) FROM accounts", Long.class);
        assertNotNull(value);
        return value;
    }

    private static String newKey(String label) {
        return "p2-" + label + "-" + UUID.randomUUID();
    }
}
