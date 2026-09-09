package in.manmeet.apexledger.api;

import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        String name,
        String kind,
        String status,
        String currency,
        long availableBalanceMinor
) {}
