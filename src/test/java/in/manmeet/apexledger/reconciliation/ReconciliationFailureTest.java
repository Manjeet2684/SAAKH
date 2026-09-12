package in.manmeet.apexledger.reconciliation;

import in.manmeet.apexledger.observability.SaakhMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationFailureTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationRunRepository runs;

    @Autowired
    MeterRegistry meters;

    @MockitoBean
    ReconciliationResultRepository results;

    @Test
    void classificationFailureLeavesDurableFailedRunWithoutResults() {
        when(results.saveAll(anyList())).thenThrow(new IllegalStateException("classify boom"));
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2027-01-01T00:00:00Z");
        double failedBefore = meter(SaakhMetrics.RECONCILIATION_FAILED);
        assertThrows(IllegalStateException.class, () -> reconciliation.create(from, to));
        assertTrue(meter(SaakhMetrics.RECONCILIATION_FAILED) >= failedBefore + 1);

        List<ReconciliationRun> persisted = runs.findAll();
        assertEquals(1, persisted.size());
        assertEquals(ReconciliationRunStatus.FAILED, persisted.getFirst().getStatus());
        assertEquals(0, persisted.getFirst().getMatchedCount());
        assertEquals(0, persisted.getFirst().getExceptionCount());
        assertEquals(0, persisted.getFirst().getMismatchCount());
    }

    private double meter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
