package in.manmeet.apexledger.api;

import java.util.UUID;

public record ReconciliationResultResponse(
        UUID id,
        String status,
        UUID transferId,
        UUID settlementId,
        Long internalAmountMinor,
        Long externalAmountMinor,
        String internalCurrency,
        String externalCurrency
) {}
