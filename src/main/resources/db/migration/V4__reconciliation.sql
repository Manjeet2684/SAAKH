-- Phase 4: settlement ingestion and detect-only reconciliation.
-- Do not rewrite V1/V2/V3. reconciliation_mismatches stays unused.

ALTER TABLE reconciliation_runs
    ADD COLUMN window_start TIMESTAMPTZ NOT NULL,
    ADD COLUMN window_end TIMESTAMPTZ NOT NULL,
    ADD COLUMN status VARCHAR(16) NOT NULL,
    ADD COLUMN total_internal INT NOT NULL DEFAULT 0,
    ADD COLUMN total_external INT NOT NULL DEFAULT 0,
    ADD COLUMN matched_count INT NOT NULL DEFAULT 0,
    ADD COLUMN exception_count INT NOT NULL DEFAULT 0;

ALTER TABLE reconciliation_runs
    ADD CONSTRAINT reconciliation_runs_window_chk CHECK (window_start < window_end),
    ADD CONSTRAINT reconciliation_runs_status_chk CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'));

CREATE TABLE settlement_records (
    id UUID PRIMARY KEY,
    external_reference VARCHAR(128) NOT NULL,
    transfer_id UUID,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL,
    settlement_status VARCHAR(16) NOT NULL CHECK (settlement_status = 'SETTLED'),
    payload_hash VARCHAR(64) NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_records_external_reference_uq UNIQUE (external_reference)
);

CREATE INDEX settlement_records_settled_at_idx ON settlement_records (settled_at);
CREATE INDEX settlement_records_transfer_id_idx ON settlement_records (transfer_id) WHERE transfer_id IS NOT NULL;

CREATE TABLE reconciliation_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs (id),
    transfer_id UUID,
    settlement_id UUID REFERENCES settlement_records (id),
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'MATCHED',
        'MISSING_EXTERNAL',
        'MISSING_INTERNAL',
        'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH',
        'DUPLICATE_EXTERNAL'
    )),
    internal_amount_minor BIGINT,
    external_amount_minor BIGINT,
    internal_currency VARCHAR(3),
    external_currency VARCHAR(3),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reconciliation_results_subject_chk CHECK (transfer_id IS NOT NULL OR settlement_id IS NOT NULL)
);

CREATE INDEX reconciliation_results_run_status_idx ON reconciliation_results (run_id, status);
CREATE INDEX reconciliation_results_run_transfer_idx ON reconciliation_results (run_id, transfer_id);
