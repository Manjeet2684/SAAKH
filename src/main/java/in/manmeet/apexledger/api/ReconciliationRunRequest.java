package in.manmeet.apexledger.api;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ReconciliationRunRequest(
        @NotNull Instant from,
        @NotNull Instant to
) {}
