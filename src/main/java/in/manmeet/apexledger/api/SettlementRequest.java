package in.manmeet.apexledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record SettlementRequest(
        @NotBlank @Size(max = 128) String externalReference,
        UUID transferId,
        @Positive long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank String settlementStatus,
        @NotNull Instant settledAt
) {}
