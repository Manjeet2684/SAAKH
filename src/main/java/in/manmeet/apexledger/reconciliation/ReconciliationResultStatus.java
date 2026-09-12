package in.manmeet.apexledger.reconciliation;

public enum ReconciliationResultStatus {
    MATCHED,
    MISSING_EXTERNAL,
    MISSING_INTERNAL,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_EXTERNAL
}
