-- Deterministic demo seed. Repeatable. Not a money-issuance product.
--
-- SYSTEM_FLOAT exists only so demo customer wallets can be funded with
-- balanced double-entry history. SAAKH v1 does not implement
-- real-world money issuance.
--
-- Opening float: 1_000_000_000 paise (₹10,000,000.00)
-- Each demo customer: 100_000 paise (₹1,000.00)
-- SYSTEM_FLOAT after seed: 999_700_000 paise
-- SUM(available_balance_minor) = 1_000_000_000

INSERT INTO accounts (id, name, kind, status, currency, available_balance_minor, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM_FLOAT', 'SYSTEM', 'OPEN', 'INR', 999700000, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Alice Wallet', 'CUSTOMER', 'OPEN', 'INR', 100000, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Bob Wallet', 'CUSTOMER', 'OPEN', 'INR', 100000, TIMESTAMPTZ '2026-01-01 00:00:00+00'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'Charlie Wallet', 'CUSTOMER', 'OPEN', 'INR', 100000, TIMESTAMPTZ '2026-01-01 00:00:00+00');

-- Seed fundings are ordinary posted transfers (SYSTEM -> customer).
INSERT INTO transfers (id, source_account_id, destination_account_id, amount_minor, currency, created_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 100000, 'INR', TIMESTAMPTZ '2026-01-01 00:00:01+00'),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 100000, 'INR', TIMESTAMPTZ '2026-01-01 00:00:02+00'),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 100000, 'INR', TIMESTAMPTZ '2026-01-01 00:00:03+00');

INSERT INTO ledger_lines (id, transfer_id, account_id, direction, amount_minor, created_at)
VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'DEBIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:01+00'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'CREDIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:01+00'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'DEBIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:02+00'),
    ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000002', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'CREDIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:02+00'),
    ('20000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'DEBIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:03+00'),
    ('20000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 'CREDIT', 100000, TIMESTAMPTZ '2026-01-01 00:00:03+00');

-- Seed outbox rows are already marked published so Phase 4's poller will not
-- emit historical funding events as live notifications.
INSERT INTO outbox_events (event_id, event_type, aggregate_id, payload, created_at, published_at)
VALUES
    (
        '30000000-0000-0000-0000-000000000001',
        'TransferPosted',
        '10000000-0000-0000-0000-000000000001',
        '{"eventId":"30000000-0000-0000-0000-000000000001","eventType":"TransferPosted","transferId":"10000000-0000-0000-0000-000000000001","sourceAccountId":"00000000-0000-0000-0000-000000000001","destinationAccountId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","amountMinor":100000,"currency":"INR"}'::jsonb,
        TIMESTAMPTZ '2026-01-01 00:00:01+00',
        TIMESTAMPTZ '2026-01-01 00:00:01+00'
    ),
    (
        '30000000-0000-0000-0000-000000000002',
        'TransferPosted',
        '10000000-0000-0000-0000-000000000002',
        '{"eventId":"30000000-0000-0000-0000-000000000002","eventType":"TransferPosted","transferId":"10000000-0000-0000-0000-000000000002","sourceAccountId":"00000000-0000-0000-0000-000000000001","destinationAccountId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","amountMinor":100000,"currency":"INR"}'::jsonb,
        TIMESTAMPTZ '2026-01-01 00:00:02+00',
        TIMESTAMPTZ '2026-01-01 00:00:02+00'
    ),
    (
        '30000000-0000-0000-0000-000000000003',
        'TransferPosted',
        '10000000-0000-0000-0000-000000000003',
        '{"eventId":"30000000-0000-0000-0000-000000000003","eventType":"TransferPosted","transferId":"10000000-0000-0000-0000-000000000003","sourceAccountId":"00000000-0000-0000-0000-000000000001","destinationAccountId":"cccccccc-cccc-cccc-cccc-cccccccccccc","amountMinor":100000,"currency":"INR"}'::jsonb,
        TIMESTAMPTZ '2026-01-01 00:00:03+00',
        TIMESTAMPTZ '2026-01-01 00:00:03+00'
    );
