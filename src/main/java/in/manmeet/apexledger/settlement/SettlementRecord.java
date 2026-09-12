package in.manmeet.apexledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_records")
public class SettlementRecord {

    @Id
    private UUID id;

    @Column(name = "external_reference", nullable = false, length = 128)
    private String externalReference;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 16)
    private SettlementStatus settlementStatus;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementRecord() {}

    public static SettlementRecord settled(
            UUID id,
            String externalReference,
            UUID transferId,
            long amountMinor,
            String currency,
            String payloadHash,
            Instant settledAt,
            Instant createdAt
    ) {
        SettlementRecord row = new SettlementRecord();
        row.id = id;
        row.externalReference = externalReference;
        row.transferId = transferId;
        row.amountMinor = amountMinor;
        row.currency = currency;
        row.settlementStatus = SettlementStatus.SETTLED;
        row.payloadHash = payloadHash;
        row.settledAt = settledAt;
        row.createdAt = createdAt;
        return row;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public SettlementStatus getSettlementStatus() {
        return settlementStatus;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
