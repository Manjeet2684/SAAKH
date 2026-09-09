package in.manmeet.apexledger.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    protected ReconciliationRun() {}

    public UUID getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getMismatchCount() {
        return mismatchCount;
    }
}
