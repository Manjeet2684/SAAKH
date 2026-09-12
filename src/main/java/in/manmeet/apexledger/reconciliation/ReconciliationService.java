package in.manmeet.apexledger.reconciliation;

import in.manmeet.apexledger.api.ApiException;
import in.manmeet.apexledger.api.ReconciliationResultResponse;
import in.manmeet.apexledger.api.ReconciliationRunResponse;
import in.manmeet.apexledger.settlement.SettlementRecord;
import in.manmeet.apexledger.settlement.SettlementRecordRepository;
import in.manmeet.apexledger.observability.SaakhMetrics;
import in.manmeet.apexledger.transfer.Transfer;
import in.manmeet.apexledger.transfer.TransferRepository;
import in.manmeet.apexledger.transfer.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransactionTemplate transactions;
    private final ReconciliationRunRepository runs;
    private final ReconciliationResultRepository results;
    private final TransferRepository transfers;
    private final SettlementRecordRepository settlements;
    private final SaakhMetrics metrics;

    public ReconciliationService(
            TransactionTemplate transactions,
            ReconciliationRunRepository runs,
            ReconciliationResultRepository results,
            TransferRepository transfers,
            SettlementRecordRepository settlements,
            SaakhMetrics metrics
    ) {
        this.transactions = transactions;
        this.runs = runs;
        this.results = results;
        this.transfers = transfers;
        this.settlements = settlements;
        this.metrics = metrics;
    }

    public ReconciliationRunResponse create(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_WINDOW", "from must be before to");
        }
        UUID runId = UUID.randomUUID();
        transactions.executeWithoutResult(status ->
                runs.save(ReconciliationRun.running(runId, from, to, Instant.now())));
        log.info("Reconciliation run started runId={} status=RUNNING", runId);
        try {
            ReconciliationRunResponse completed = transactions.execute(status -> classify(runId, from, to));
            metrics.reconciliationCompleted();
            log.info("Reconciliation run completed runId={} status={} exceptionCount={}",
                    runId, completed.status(), completed.exceptionCount());
            return completed;
        } catch (RuntimeException ex) {
            transactions.executeWithoutResult(status -> {
                ReconciliationRun run = runs.findById(runId).orElseThrow();
                run.markFailed(Instant.now());
                runs.save(run);
            });
            metrics.reconciliationFailed();
            log.info("Reconciliation run failed runId={} status=FAILED", runId);
            throw ex;
        }
    }

    public ReconciliationRunResponse get(UUID runId) {
        ReconciliationRun run = runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("RECONCILIATION_RUN_NOT_FOUND", "No reconciliation run " + runId));
        return toResponse(run, results.findByRunIdOrderByCreatedAtAsc(runId));
    }

    private ReconciliationRunResponse classify(UUID runId, Instant from, Instant to) {
        ReconciliationRun run = runs.findById(runId).orElseThrow();
        List<Transfer> internals = transfers.findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                TransferStatus.COMPLETED, from, to);
        List<SettlementRecord> externals = settlements.findBySettledAtGreaterThanEqualAndSettledAtLessThan(from, to);
        Map<UUID, Transfer> internalById = internals.stream().collect(Collectors.toMap(Transfer::getId, transfer -> transfer));
        Map<UUID, List<SettlementRecord>> byTransferId = externals.stream()
                .filter(settlement -> settlement.getTransferId() != null)
                .collect(Collectors.groupingBy(SettlementRecord::getTransferId));

        Instant now = Instant.now();
        List<ReconciliationResult> classified = new ArrayList<>();
        Set<UUID> internalsWithResults = new HashSet<>();

        for (SettlementRecord settlement : externals) {
            if (settlement.getTransferId() == null) {
                classified.add(result(runId, null, settlement, ReconciliationResultStatus.MISSING_INTERNAL, null, now));
                continue;
            }
            List<SettlementRecord> group = byTransferId.getOrDefault(settlement.getTransferId(), List.of());
            if (group.size() > 1) {
                Transfer internal = internalById.get(settlement.getTransferId());
                classified.add(result(runId, settlement.getTransferId(), settlement, ReconciliationResultStatus.DUPLICATE_EXTERNAL, internal, now));
                if (internal != null) {
                    internalsWithResults.add(internal.getId());
                }
                continue;
            }
            Transfer inWindow = internalById.get(settlement.getTransferId());
            if (inWindow == null) {
                classified.add(result(runId, settlement.getTransferId(), settlement, ReconciliationResultStatus.MISSING_INTERNAL, null, now));
                continue;
            }
            ReconciliationResultStatus status = compare(inWindow, settlement);
            classified.add(result(runId, inWindow.getId(), settlement, status, inWindow, now));
            internalsWithResults.add(inWindow.getId());
        }

        for (Transfer transfer : internals) {
            if (!internalsWithResults.contains(transfer.getId())) {
                classified.add(result(runId, transfer.getId(), null, ReconciliationResultStatus.MISSING_EXTERNAL, transfer, now));
            }
        }

        results.saveAll(classified);
        int matched = (int) classified.stream().filter(row -> row.getStatus() == ReconciliationResultStatus.MATCHED).count();
        int exceptions = classified.size() - matched;
        run.markCompleted(now, internals.size(), externals.size(), matched, exceptions);
        runs.save(run);
        return toResponse(run, classified);
    }

    private static ReconciliationResultStatus compare(Transfer transfer, SettlementRecord settlement) {
        if (transfer.getAmountMinor() != settlement.getAmountMinor()) {
            return ReconciliationResultStatus.AMOUNT_MISMATCH;
        }
        if (!transfer.getCurrency().equals(settlement.getCurrency())) {
            return ReconciliationResultStatus.CURRENCY_MISMATCH;
        }
        return ReconciliationResultStatus.MATCHED;
    }

    private static ReconciliationResult result(
            UUID runId,
            UUID transferId,
            SettlementRecord settlement,
            ReconciliationResultStatus status,
            Transfer transfer,
            Instant at
    ) {
        return ReconciliationResult.of(
                runId,
                transferId,
                settlement == null ? null : settlement.getId(),
                status,
                transfer == null ? null : transfer.getAmountMinor(),
                settlement == null ? null : settlement.getAmountMinor(),
                transfer == null ? null : transfer.getCurrency(),
                settlement == null ? null : settlement.getCurrency(),
                at
        );
    }

    private static ReconciliationRunResponse toResponse(ReconciliationRun run, List<ReconciliationResult> rows) {
        return new ReconciliationRunResponse(
                run.getId(),
                run.getStatus().name(),
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getTotalInternal(),
                run.getTotalExternal(),
                run.getMatchedCount(),
                run.getExceptionCount(),
                run.getMismatchCount(),
                rows.stream().map(ReconciliationService::toResult).toList()
        );
    }

    private static ReconciliationResultResponse toResult(ReconciliationResult row) {
        return new ReconciliationResultResponse(
                row.getId(),
                row.getStatus().name(),
                row.getTransferId(),
                row.getSettlementId(),
                row.getInternalAmountMinor(),
                row.getExternalAmountMinor(),
                row.getInternalCurrency(),
                row.getExternalCurrency()
        );
    }
}
