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
@Table(name = "reconciliation_results")
public class ReconciliationResult {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "settlement_id")
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReconciliationResultStatus status;

    @Column(name = "internal_amount_minor")
    private Long internalAmountMinor;

    @Column(name = "external_amount_minor")
    private Long externalAmountMinor;

    @Column(name = "internal_currency", length = 3)
    private String internalCurrency;

    @Column(name = "external_currency", length = 3)
    private String externalCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationResult() {}

    public static ReconciliationResult of(
            UUID runId,
            UUID transferId,
            UUID settlementId,
            ReconciliationResultStatus status,
            Long internalAmountMinor,
            Long externalAmountMinor,
            String internalCurrency,
            String externalCurrency,
            Instant createdAt
    ) {
        ReconciliationResult row = new ReconciliationResult();
        row.id = UUID.randomUUID();
        row.runId = runId;
        row.transferId = transferId;
        row.settlementId = settlementId;
        row.status = status;
        row.internalAmountMinor = internalAmountMinor;
        row.externalAmountMinor = externalAmountMinor;
        row.internalCurrency = internalCurrency;
        row.externalCurrency = externalCurrency;
        row.createdAt = createdAt;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public ReconciliationResultStatus getStatus() {
        return status;
    }

    public Long getInternalAmountMinor() {
        return internalAmountMinor;
    }

    public Long getExternalAmountMinor() {
        return externalAmountMinor;
    }

    public String getInternalCurrency() {
        return internalCurrency;
    }

    public String getExternalCurrency() {
        return externalCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
