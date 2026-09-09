-- Phase 2: posting metadata for transfers. Do not rewrite V1/V2.
-- Existing seed transfers are already COMPLETED money movements.

ALTER TABLE transfers
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN status VARCHAR(16),
    ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE transfers
SET
    idempotency_key = 'seed-' || id::text,
    status = 'COMPLETED',
    completed_at = created_at
WHERE idempotency_key IS NULL;

ALTER TABLE transfers
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN completed_at SET NOT NULL;

-- Unique idempotency_key is the database-level duplicate-request guard.
ALTER TABLE transfers
    ADD CONSTRAINT transfers_status_chk CHECK (status IN ('COMPLETED', 'FAILED')),
    ADD CONSTRAINT transfers_idempotency_key_uq UNIQUE (idempotency_key);
