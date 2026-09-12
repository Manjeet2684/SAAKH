package in.manmeet.apexledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID runId,
        String status,
        Instant windowStart,
        Instant windowEnd,
        Instant startedAt,
        Instant finishedAt,
        int totalInternal,
        int totalExternal,
        int matchedCount,
        int exceptionCount,
        int mismatchCount,
        List<ReconciliationResultResponse> results
) {}
