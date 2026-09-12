package in.manmeet.apexledger.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    private UUID id;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReconciliationRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "total_internal", nullable = false)
    private int totalInternal;

    @Column(name = "total_external", nullable = false)
    private int totalExternal;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "exception_count", nullable = false)
    private int exceptionCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    protected ReconciliationRun() {}

    public static ReconciliationRun running(UUID id, Instant windowStart, Instant windowEnd, Instant startedAt) {
        ReconciliationRun run = new ReconciliationRun();
        run.id = id;
        run.windowStart = windowStart;
        run.windowEnd = windowEnd;
        run.status = ReconciliationRunStatus.RUNNING;
        run.startedAt = startedAt;
        return run;
    }

    public void markCompleted(
            Instant finishedAt,
            int totalInternal,
            int totalExternal,
            int matchedCount,
            int exceptionCount
    ) {
        this.status = ReconciliationRunStatus.COMPLETED;
        this.finishedAt = finishedAt;
        this.totalInternal = totalInternal;
        this.totalExternal = totalExternal;
        this.matchedCount = matchedCount;
        this.exceptionCount = exceptionCount;
        this.mismatchCount = exceptionCount;
    }

    public void markFailed(Instant finishedAt) {
        this.status = ReconciliationRunStatus.FAILED;
        this.finishedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public ReconciliationRunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getTotalInternal() {
        return totalInternal;
    }

    public int getTotalExternal() {
        return totalExternal;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getExceptionCount() {
        return exceptionCount;
    }

    public int getMismatchCount() {
        return mismatchCount;
    }
}
