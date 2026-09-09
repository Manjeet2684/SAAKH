-- SAAKH v1 schema.
-- Flyway owns DDL. String widths match Hibernate defaults so ddl-auto=validate succeeds.

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'FROZEN')),
    currency VARCHAR(3) NOT NULL,
    available_balance_minor BIGINT NOT NULL CHECK (available_balance_minor >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL REFERENCES accounts (id),
    destination_account_id UUID NOT NULL REFERENCES accounts (id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT transfers_source_ne_dest CHECK (source_account_id <> destination_account_id)
);

CREATE TABLE ledger_lines (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers (id),
    account_id UUID NOT NULL REFERENCES accounts (id),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_lines_one_direction_per_transfer UNIQUE (transfer_id, direction)
);

CREATE INDEX ledger_lines_account_idx ON ledger_lines (account_id);

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    http_status INT,
    response_body JSONB,
    transfer_id UUID REFERENCES transfers (id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES outbox_events (event_id),
    transfer_id UUID NOT NULL REFERENCES transfers (id),
    body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    mismatch_count INT NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_mismatches (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs (id),
    account_id UUID NOT NULL REFERENCES accounts (id),
    cached_balance_minor BIGINT NOT NULL,
    ledger_balance_minor BIGINT NOT NULL,
    delta_minor BIGINT NOT NULL
);
