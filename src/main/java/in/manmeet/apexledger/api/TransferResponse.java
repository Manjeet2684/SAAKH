package in.manmeet.apexledger.api;

import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        String currency,
        String status
) {}
