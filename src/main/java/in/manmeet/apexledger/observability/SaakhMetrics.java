package in.manmeet.apexledger.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SaakhMetrics {

    public static final String TRANSFER_COMPLETED = "saakh.transfer.completed";
    public static final String TRANSFER_REPLAYED = "saakh.transfer.replayed";
    public static final String TRANSFER_REJECTED = "saakh.transfer.rejected";
    public static final String SETTLEMENT_INGESTED = "saakh.settlement.ingested";
    public static final String SETTLEMENT_CONFLICT = "saakh.settlement.conflict";
    public static final String OUTBOX_PUBLISHED = "saakh.outbox.published";
    public static final String OUTBOX_PUBLISH_FAILURES = "saakh.outbox.publish.failures";
    public static final String OUTBOX_PENDING = "saakh.outbox.pending";
    public static final String RECONCILIATION_COMPLETED = "saakh.reconciliation.completed";
    public static final String RECONCILIATION_FAILED = "saakh.reconciliation.failed";
    public static final String AUTH_FAILURES = "saakh.http.auth.failures";

    private final MeterRegistry meters;

    public SaakhMetrics(MeterRegistry meters) {
        this.meters = meters;
        safe(() -> meters.gauge(OUTBOX_PENDING, this, ignored -> lastPending));
    }

    private volatile double lastPending;

    public void transferCompleted() {
        increment(TRANSFER_COMPLETED);
    }

    public void transferReplayed() {
        increment(TRANSFER_REPLAYED);
    }

    public void transferRejected(String code) {
        increment(TRANSFER_REJECTED, "code", bounded(code));
    }

    public void settlementCreated() {
        increment(SETTLEMENT_INGESTED, "result", "created");
    }

    public void settlementReplayed() {
        increment(SETTLEMENT_INGESTED, "result", "replayed");
    }

    public void settlementConflict() {
        increment(SETTLEMENT_CONFLICT);
    }

    public void outboxPublished(int count) {
        if (count <= 0) {
            return;
        }
        safe(() -> meters.counter(OUTBOX_PUBLISHED).increment(count));
    }

    public void outboxPublishFailure() {
        increment(OUTBOX_PUBLISH_FAILURES);
    }

    public void outboxPending(long pending) {
        lastPending = pending;
    }

    public void reconciliationCompleted() {
        increment(RECONCILIATION_COMPLETED);
    }

    public void reconciliationFailed() {
        increment(RECONCILIATION_FAILED);
    }

    public void authFailure(String pathGroup) {
        increment(AUTH_FAILURES, "path_group", boundedPathGroup(pathGroup));
    }

    private void increment(String name) {
        safe(() -> meters.counter(name).increment());
    }

    private void increment(String name, String tag, String value) {
        safe(() -> meters.counter(name, tag, value).increment());
    }

    private static String bounded(String code) {
        if (code == null || code.isBlank() || code.length() > 64) {
            return "UNKNOWN";
        }
        return code;
    }

    private static String boundedPathGroup(String pathGroup) {
        if ("/api".equals(pathGroup) || "/v1".equals(pathGroup) || "/internal".equals(pathGroup)) {
            return pathGroup;
        }
        return "other";
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Observability must not change financial or HTTP outcomes.
        }
    }
}
