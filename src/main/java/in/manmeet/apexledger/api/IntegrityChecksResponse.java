package in.manmeet.apexledger.api;

public record IntegrityChecksResponse(
        IntegrityCheckStatus doubleEntry,
        IntegrityCheckStatus amountConsistency,
        IntegrityCheckStatus currencyConsistency,
        IntegrityCheckStatus globalDoubleEntry,
        IntegrityCheckStatus customerBalance,
        IntegrityCheckStatus systemBalance
) {}
