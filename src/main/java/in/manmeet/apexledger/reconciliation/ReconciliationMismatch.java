package in.manmeet.apexledger.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "reconciliation_mismatches")
public class ReconciliationMismatch {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "cached_balance_minor", nullable = false)
    private long cachedBalanceMinor;

    @Column(name = "ledger_balance_minor", nullable = false)
    private long ledgerBalanceMinor;

    @Column(name = "delta_minor", nullable = false)
    private long deltaMinor;

    protected ReconciliationMismatch() {}

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getCachedBalanceMinor() {
        return cachedBalanceMinor;
    }

    public long getLedgerBalanceMinor() {
        return ledgerBalanceMinor;
    }

    public long getDeltaMinor() {
        return deltaMinor;
    }
}
