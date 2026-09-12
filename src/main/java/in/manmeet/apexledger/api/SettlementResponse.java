package in.manmeet.apexledger.api;

import java.time.Instant;
import java.util.UUID;

public record SettlementResponse(
        UUID id,
        String externalReference,
        UUID transferId,
        long amountMinor,
        String currency,
        String settlementStatus,
        Instant settledAt
) {}
