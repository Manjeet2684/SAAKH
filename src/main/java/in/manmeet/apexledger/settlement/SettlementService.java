package in.manmeet.apexledger.settlement;

import in.manmeet.apexledger.api.ApiException;
import in.manmeet.apexledger.api.SettlementRequest;
import in.manmeet.apexledger.api.SettlementResponse;
import in.manmeet.apexledger.observability.SaakhMetrics;
import in.manmeet.apexledger.support.SettlementFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final TransactionTemplate transactions;
    private final SettlementRecordRepository settlements;
    private final SaakhMetrics metrics;

    public SettlementService(
            TransactionTemplate transactions,
            SettlementRecordRepository settlements,
            SaakhMetrics metrics
    ) {
        this.transactions = transactions;
        this.settlements = settlements;
        this.metrics = metrics;
    }

    public SettlementIngestResult ingest(SettlementRequest request) {
        try {
            SettlementIngestResult result = ingestOutcome(request);
            if (result.created()) {
                metrics.settlementCreated();
                log.info("Settlement created settlementId={}", result.body().id());
            } else {
                metrics.settlementReplayed();
                log.info("Settlement replayed settlementId={}", result.body().id());
            }
            return result;
        } catch (ApiException ex) {
            if ("SETTLEMENT_CONFLICT".equals(ex.getCode())) {
                metrics.settlementConflict();
                log.info("Settlement conflict outcome=conflict");
            }
            throw ex;
        }
    }

    private SettlementIngestResult ingestOutcome(SettlementRequest request) {
        if (!SettlementStatus.SETTLED.name().equalsIgnoreCase(request.settlementStatus().trim())) {
            throw ApiException.unprocessable("UNSUPPORTED_SETTLEMENT_STATUS", "Only SETTLED is supported");
        }
        String currency = request.currency().toUpperCase();
        Instant settledAt = request.settledAt();
        String hash = SettlementFingerprint.hash(
                request.externalReference(),
                request.transferId(),
                request.amountMinor(),
                currency,
                SettlementStatus.SETTLED.name(),
                settledAt
        );

        SettlementRecord existing = findByExternalReference(request.externalReference());
        if (existing != null) {
            return replay(existing, hash);
        }

        try {
            return transactions.execute(status -> insertOrReplay(request, currency, hash, settledAt));
        } catch (RuntimeException ex) {
            if (!isUniqueViolation(ex)) {
                throw ex;
            }
            SettlementRecord raced = findByExternalReference(request.externalReference());
            if (raced == null) {
                throw ApiException.conflict("SETTLEMENT_CONFLICT", "Settlement reference conflicted but was not found");
            }
            return replay(raced, hash);
        }
    }

    private SettlementIngestResult insertOrReplay(
            SettlementRequest request,
            String currency,
            String hash,
            Instant settledAt
    ) {
        SettlementRecord existing = settlements.findByExternalReference(request.externalReference()).orElse(null);
        if (existing != null) {
            return replay(existing, hash);
        }
        SettlementRecord created = settlements.save(SettlementRecord.settled(
                UUID.randomUUID(),
                request.externalReference(),
                request.transferId(),
                request.amountMinor(),
                currency,
                hash,
                settledAt,
                Instant.now()
        ));
        settlements.flush();
        return new SettlementIngestResult(toResponse(created), true);
    }

    private SettlementRecord findByExternalReference(String externalReference) {
        return transactions.execute(status -> settlements.findByExternalReference(externalReference).orElse(null));
    }

    private static SettlementIngestResult replay(SettlementRecord existing, String hash) {
        if (!existing.getPayloadHash().equals(hash)) {
            throw ApiException.conflict("SETTLEMENT_CONFLICT", "externalReference was already used with a different settlement payload");
        }
        return new SettlementIngestResult(toResponse(existing), false);
    }

    private static SettlementResponse toResponse(SettlementRecord row) {
        return new SettlementResponse(
                row.getId(),
                row.getExternalReference(),
                row.getTransferId(),
                row.getAmountMinor(),
                row.getCurrency(),
                row.getSettlementStatus().name(),
                row.getSettledAt()
        );
    }

    private static boolean isUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.toLowerCase().contains("external_reference")) {
                    return true;
                }
            }
            if (current instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                String message = sql.getMessage();
                if (message != null && message.toLowerCase().contains("external_reference")) {
                    return true;
                }
            }
            if (current instanceof DataIntegrityViolationException dive) {
                String message = dive.getMessage();
                if (message != null && message.toLowerCase().contains("external_reference")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public record SettlementIngestResult(SettlementResponse body, boolean created) {}
}
