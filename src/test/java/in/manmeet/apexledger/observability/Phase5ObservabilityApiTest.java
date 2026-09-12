package in.manmeet.apexledger.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.ReconciliationRunRequest;
import in.manmeet.apexledger.api.SettlementRequest;
import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.support.DemoAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class Phase5ObservabilityApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRegistry meters;

    @Test
    void requestIdGeneratedPreservedAndReturnedOnAuthFailure() throws Exception {
        MvcResult generated = mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-API-Key", "local-dev-key"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andReturn();
        String generatedId = generated.getResponse().getHeader("X-Request-ID");
        assertTrue(generatedId != null && !generatedId.isBlank());

        mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-API-Key", "local-dev-key")
                        .header("X-Request-ID", "keep-this-id.1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", "keep-this-id.1"));

        MvcResult replaced = mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-API-Key", "local-dev-key")
                        .header("X-Request-ID", "not valid"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andReturn();
        assertNotEquals("not valid", replaced.getResponse().getHeader("X-Request-ID"));

        mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-Request-ID", "auth-fail-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", "auth-fail-1"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").value("auth-fail-1"));
        MvcResult oversized = mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-API-Key", "local-dev-key")
                        .header("X-Request-ID", "a".repeat(129)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"))
                .andReturn();
        assertNotEquals("a".repeat(129), oversized.getResponse().getHeader("X-Request-ID"));

        double authBefore = counter(SaakhMetrics.AUTH_FAILURES);
        String authBody = mvc.perform(get("/v1/accounts/" + DemoAccounts.ALICE)
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertFalse(authBody.contains("wrong-key"));
        assertFalse(authBody.contains("local-dev-key"));
        assertTrue(counter(SaakhMetrics.AUTH_FAILURES) >= authBefore + 1);
    }

    @Test
    void audienceKeysAreSeparatedAndHealthIsSafe() throws Exception {
        mvc.perform(get("/internal/v1/integrity").header("X-API-Key", "local-dev-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", "local-internal-key")
                        .header("Idempotency-Key", "p5-wrong-aud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, 10L, "INR"))))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
    }

    @Test
    void transferReplayAndSettlementConflictUpdateDistinctMeters() throws Exception {
        double completedBefore = counter(SaakhMetrics.TRANSFER_COMPLETED);
        double replayedBefore = counter(SaakhMetrics.TRANSFER_REPLAYED);
        String key = "p5-replay-" + UUID.randomUUID();
        postTransfer(key, 12L);
        postTransfer(key, 12L);
        assertTrue(counter(SaakhMetrics.TRANSFER_COMPLETED) >= completedBefore + 1);
        assertTrue(counter(SaakhMetrics.TRANSFER_REPLAYED) >= replayedBefore + 1);

        String ref = "p5-set-" + UUID.randomUUID();
        Instant at = Instant.parse("2026-09-12T15:00:00Z");
        double createdBefore = tagged(SaakhMetrics.SETTLEMENT_INGESTED, "result", "created");
        double setReplayBefore = tagged(SaakhMetrics.SETTLEMENT_INGESTED, "result", "replayed");
        double conflictBefore = counter(SaakhMetrics.SETTLEMENT_CONFLICT);
        postSettlement(ref, 100L, at);
        postSettlement(ref, 100L, at);
        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest(ref, null, 200L, "INR", "SETTLED", at))))
                .andExpect(status().isConflict());
        assertTrue(tagged(SaakhMetrics.SETTLEMENT_INGESTED, "result", "created") >= createdBefore + 1);
        assertTrue(tagged(SaakhMetrics.SETTLEMENT_INGESTED, "result", "replayed") >= setReplayBefore + 1);
        assertTrue(counter(SaakhMetrics.SETTLEMENT_CONFLICT) >= conflictBefore + 1);

        double reconBefore = counter(SaakhMetrics.RECONCILIATION_COMPLETED);
        mvc.perform(post("/internal/v1/reconciliation/runs")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReconciliationRunRequest(
                                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")))))
                .andExpect(status().isCreated());
        assertTrue(counter(SaakhMetrics.RECONCILIATION_COMPLETED) >= reconBefore + 1);
    }

    @Test
    void unexpectedExceptionIsSafeAndIncludesRequestId() throws Exception {
        mvc.perform(get("/internal/v1/__phase5-boom")
                        .header("X-API-Key", "local-internal-key")
                        .header("X-Request-ID", "boom-req-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-ID", "boom-req-1"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.requestId").value("boom-req-1"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQLException"))));
    }

    private void postTransfer(String key, long amount) throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", "local-dev-key")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, amount, "INR"))))
                .andExpect(status().isCreated());
    }

    private void postSettlement(String ref, long amount, Instant at) throws Exception {
        mvc.perform(post("/internal/v1/settlements")
                        .header("X-API-Key", "local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SettlementRequest(ref, null, amount, "INR", "SETTLED", at))))
                .andReturn();
    }

    private double counter(String name) {
        double tagged = meters.find(name).counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        if (tagged > 0) {
            return tagged;
        }
        var counter = meters.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double tagged(String name, String tag, String value) {
        var counter = meters.find(name).tag(tag, value).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @TestConfiguration
    static class BoomConfig {
        @Bean
        BoomController boomController() {
            return new BoomController();
        }
    }

    @RestController
    @RequestMapping("/internal/v1/__phase5-boom")
    static class BoomController {
        @GetMapping
        void boom() {
            throw new IllegalStateException("SQLException: relation secret leaked");
        }
    }
}
