package in.manmeet.apexledger.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.account.Account;
import in.manmeet.apexledger.account.AccountRepository;
import in.manmeet.apexledger.api.ApiException;
import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.api.TransferResponse;
import in.manmeet.apexledger.idempotency.IdempotencyKey;
import in.manmeet.apexledger.idempotency.IdempotencyKeyRepository;
import in.manmeet.apexledger.ledger.LedgerDirection;
import in.manmeet.apexledger.ledger.LedgerLine;
import in.manmeet.apexledger.ledger.LedgerLineRepository;
import in.manmeet.apexledger.outbox.OutboxEvent;
import in.manmeet.apexledger.outbox.OutboxEventRepository;
import in.manmeet.apexledger.support.Money;
import in.manmeet.apexledger.support.RequestFingerprint;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Posts a customer-to-customer transfer in one PostgreSQL transaction:
 * SELECT FOR UPDATE on both accounts (id order), debit/credit, transfer row,
 * two ledger lines, unpublished outbox event, and the completed idempotency row.
 * Concurrent duplicates hit UNIQUE(idempotency_key) and roll back; the loser replays.
 */
@Service
public class TransferService {

    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerLineRepository ledgerLines;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;

    public TransferService(
            TransactionTemplate transactions,
            EntityManager entityManager,
            AccountRepository accounts,
            TransferRepository transfers,
            LedgerLineRepository ledgerLines,
            IdempotencyKeyRepository idempotencyKeys,
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper
    ) {
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledgerLines = ledgerLines;
        this.idempotencyKeys = idempotencyKeys;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
    }

    public TransferResponse post(String idempotencyKey, TransferRequest request) {
        validateHeader(idempotencyKey);
        String currency = request.currency().toUpperCase();
        if (!Money.CURRENCY_INR.equals(currency)) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
        if (request.amountMinor() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amountMinor must be positive");
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw ApiException.badRequest("SELF_TRANSFER", "Source and destination must differ");
        }

        String requestHash = RequestFingerprint.transferHash(
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amountMinor(),
                currency
        );

        try {
            return transactions.execute(status -> executeNew(idempotencyKey, requestHash, request, currency));
        } catch (RuntimeException ex) {
            if (!isUniqueViolation(ex)) {
                throw ex;
            }
            return replay(idempotencyKey, requestHash);
        }
    }

    private TransferResponse executeNew(String idempotencyKey, String requestHash, TransferRequest request, String currency) {
        IdempotencyKey existing = idempotencyKeys.findById(idempotencyKey).orElse(null);
        if (existing != null) {
            return replayRow(existing, requestHash);
        }

        Instant now = Instant.now();
        List<Account> locked = accounts.lockByIdsOrdered(List.of(request.sourceAccountId(), request.destinationAccountId()));
        Account source = findLocked(locked, request.sourceAccountId());
        Account destination = findLocked(locked, request.destinationAccountId());
        source.requireOpenCustomer();
        destination.requireOpenCustomer();
        if (!currency.equals(source.getCurrency()) || !currency.equals(destination.getCurrency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Transfer currency must match both accounts");
        }

        source.debit(request.amountMinor());
        destination.credit(request.amountMinor());

        UUID transferId = UUID.randomUUID();
        Transfer transfer = Transfer.completed(
                transferId,
                idempotencyKey,
                source.getId(),
                destination.getId(),
                request.amountMinor(),
                currency,
                now
        );
        transfers.save(transfer);
        ledgerLines.save(LedgerLine.of(transferId, source.getId(), LedgerDirection.DEBIT, request.amountMinor(), now));
        ledgerLines.save(LedgerLine.of(transferId, destination.getId(), LedgerDirection.CREDIT, request.amountMinor(), now));

        TransferResponse body = toResponse(transfer);
        UUID eventId = UUID.randomUUID();
        outboxEvents.save(OutboxEvent.unpublished(
                eventId,
                OutboxEvent.TRANSFER_COMPLETED,
                transferId,
                objectMapper.valueToTree(outboxPayload(eventId, body, now)),
                now
        ));

        // Insert the completed idempotency row last so Hibernate persists JSON on INSERT.
        // Unique (idempotency_key) plus this TX rollback prevents a duplicate debit.
        idempotencyKeys.save(IdempotencyKey.completed(
                idempotencyKey,
                requestHash,
                HttpStatus.CREATED.value(),
                objectMapper.valueToTree(body),
                transferId,
                now
        ));
        entityManager.flush();
        return body;
    }

    private TransferResponse replay(String idempotencyKey, String requestHash) {
        IdempotencyKey row = idempotencyKeys.findById(idempotencyKey)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "Idempotency key conflicted but was not found"));
        return replayRow(row, requestHash);
    }

    private TransferResponse replayRow(IdempotencyKey row, String requestHash) {
        if (!row.getRequestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request");
        }
        if (row.getResponseBody() == null || row.getResponseBody().isNull()) {
            throw ApiException.conflict("IDEMPOTENCY_IN_FLIGHT", "Idempotency-Key is already being processed");
        }
        try {
            return objectMapper.treeToValue(row.getResponseBody(), TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not readable", e);
        }
    }

    private static Account findLocked(List<Account> locked, UUID id) {
        return locked.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "No account with id " + id));
    }

    private static void validateHeader(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("MISSING_IDEMPOTENCY_KEY", "Header Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 128) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be at most 128 characters");
        }
    }

    private static TransferResponse toResponse(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmountMinor(),
                transfer.getCurrency(),
                transfer.getStatus().name()
        );
    }

    private static Map<String, Object> outboxPayload(UUID eventId, TransferResponse body, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", OutboxEvent.TRANSFER_COMPLETED);
        payload.put("aggregateType", "TRANSFER");
        payload.put("aggregateId", body.transferId().toString());
        payload.put("transferId", body.transferId().toString());
        payload.put("sourceAccountId", body.sourceAccountId().toString());
        payload.put("destinationAccountId", body.destinationAccountId().toString());
        payload.put("amountMinor", body.amountMinor());
        payload.put("currency", body.currency());
        payload.put("status", body.status());
        payload.put("occurredAt", at.toString());
        payload.put("schemaVersion", 1);
        return payload;
    }

    private static boolean isUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.toLowerCase().contains("idempotency")) {
                    return true;
                }
            }
            if (current instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                String message = sql.getMessage();
                if (message != null && message.toLowerCase().contains("idempotency")) {
                    return true;
                }
            }
            if (current instanceof DataIntegrityViolationException dive) {
                String message = dive.getMessage();
                if (message != null && message.toLowerCase().contains("idempotency")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
