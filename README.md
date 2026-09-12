# SAAKH

**Trust Through Every Transaction.**

SAAKH is a correctness-focused financial transaction and reconciliation backend. The current implementation is a Spring Boot monolith: PostgreSQL is the financial source of truth, and Kafka is used only for transactional outbox publishing after a transfer commits. It is not a microservices system.

It is built to preserve consistency under retries, duplicate requests, partial failures, and asynchronous event publication.

**Phase 4 (current):** detect-only financial integrity and simulated settlement reconciliation. Kafka publishing is unchanged from Phase 3.

It is not a bank, not UPI, not a Razorpay clone, and not an authentication product.

## Phase 1 — Foundation (implemented)

- Spring Boot 3.5 / Java 21 / Maven
- PostgreSQL schema owned by Flyway (`ddl-auto=validate`)
- Docker Compose for Postgres and Kafka
- Deterministic demo wallets
- `GET /v1/accounts/{id}` behind `X-API-Key`

## Phase 2 — Transfer engine

`POST /api/v1/transfers` moves INR paise between OPEN CUSTOMER accounts.

Send `X-API-Key: local-dev-key` and header `Idempotency-Key`. Body: `sourceAccountId`, `destinationAccountId`, `amountMinor`, `currency`.

- **Atomic transactions:** debit, credit, transfer row, two ledger lines, and an unpublished outbox event commit together or roll back together.
- **Idempotency:** `Idempotency-Key` is unique in Postgres. The same key and request returns the original `COMPLETED` result. The same key with different transfer details returns `409`.
- **Concurrency:** both accounts are locked with `SELECT FOR UPDATE` in `id` order (`PESSIMISTIC_WRITE`) so concurrent postings cannot corrupt balances or deadlock A→B vs B→A.
- **Transactional outbox (persist):** a successful transfer inserts `TRANSFER_COMPLETED` with `published_at` null in the same database transaction. Kafka is not called here.

## Phase 3 — Outbox publisher

A scheduled poller copies unpublished outbox rows to Kafka **after** the money transaction has committed.

```
Posting TX (Postgres only) → unpublished outbox row
        ↓
publishBatch()
        ↓
SELECT … FOR UPDATE SKIP LOCKED
        ↓
Kafka produce (acks=all) on topic saakh.transfers
        ↓
set published_at (same publisher TX)
```

- **At-least-once:** if Kafka acks and the process crashes before `published_at` is committed, the same `eventId` may be published again. Future consumers should deduplicate on `eventId`. This is not exactly-once and not an XA transaction.
- **Stable event identity:** `event_id` is assigned when the outbox row is inserted. The publisher never mints a new id.
- **Concurrent publishers:** `SKIP LOCKED` so two `publishBatch()` calls do not process the same unpublished row at the same time. That does not remove crash-window duplicates.
- **Tradeoff:** the publisher transaction stays open while waiting for Kafka acknowledgement. That is intentional at current scale. A higher-throughput system could use lease/claim metadata instead; SAAKH does not.
- **Scheduler:** `scheduledPoll()` only calls `publishBatch()` on the Spring bean (not `this`) so the publishing transaction actually commits `published_at` after Kafka acknowledgement.

Local run needs Kafka as well as Postgres:

```bash
docker compose up -d postgres kafka
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
docker compose up -d postgres kafka
.\mvnw.cmd spring-boot:run
```

## Phase 4 — Integrity and reconciliation

Detect-only. These APIs never move money, never write ledger/transfer/outbox rows, and never publish Kafka.

`GET /internal/v1/integrity` (key `local-internal-key`) checks double-entry completeness, amounts, transfer/account currency, global debit==credit, and CUSTOMER cached vs ledger-derived balances. SYSTEM_FLOAT I5 is `INCONCLUSIVE` because the seed opening float has no opening CREDIT. Integrity is not persisted.

`POST /internal/v1/settlements` records a simulated external settlement. `externalReference` is unique. Same fingerprint returns `200`; a different payload returns `409 SETTLEMENT_CONFLICT`. `transferId` may be null or unknown (no FK). Only `SETTLED` is accepted.

`POST /internal/v1/reconciliation/runs` with `{ "from", "to" }` compares COMPLETED transfers (`completed_at`) to settlements (`settled_at`) on `[from, to)`. Match key is `transferId`. Statuses: `MATCHED`, `MISSING_EXTERNAL`, `MISSING_INTERNAL`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `DUPLICATE_EXTERNAL`. The run is committed `RUNNING` first, then `COMPLETED` or durable `FAILED`. `GET /internal/v1/reconciliation/runs/{runId}` returns the audit.

This is not a real PSP integration, not automatic repair, and not a Kafka consumer.

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "X-API-Key: local-dev-key" \
  -H "Idempotency-Key: demo-alice-bob-1" \
  -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"destinationAccountId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\",\"amountMinor\":1000,\"currency\":\"INR\"}"
```

Windows PowerShell 5.1 strips quotes from inline JSON passed to `curl.exe`. Write the body to a file without a UTF-8 BOM and send it with `--data-binary`:

```powershell
$json = '{"sourceAccountId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","destinationAccountId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","amountMinor":1000,"currency":"INR"}'
[System.IO.File]::WriteAllText("$env:TEMP\saakh-transfer.json", $json)
curl.exe -i -X POST "http://localhost:8080/api/v1/transfers" -H "Content-Type: application/json" -H "X-API-Key: local-dev-key" -H "Idempotency-Key: demo-alice-bob-1" --data-binary "@$env:TEMP\saakh-transfer.json"
```

## Demo accounts

Flyway V2 seed/demo state only. These balances are the values immediately after seed. They are not production data and are not a permanent runtime snapshot; later transfers change them.

`SYSTEM_FLOAT` is **not** a real money-issuance system. It exists only so demo customer wallets have balanced double-entry history.

| Name | ID | Kind | Seed balance |
|---|---|---|---|
| SYSTEM_FLOAT | `00000000-0000-0000-0000-000000000001` | SYSTEM | 999,700,000 paise |
| Alice Wallet | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` | CUSTOMER | 100,000 paise (₹1,000.00) |
| Bob Wallet | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` | CUSTOMER | 100,000 paise (₹1,000.00) |
| Charlie Wallet | `cccccccc-cccc-cccc-cccc-cccccccccccc` | CUSTOMER | 100,000 paise (₹1,000.00) |

`SUM(available_balance_minor)` after seed = `1,000,000,000` paise.

V2 also inserts already-published seed outbox rows. Those historical rows use event type `TransferPosted`. Live `POST /api/v1/transfers` inserts `TRANSFER_COMPLETED`. The publisher does not emit the seed rows because they already have `published_at`.

## Run locally

Requires Java 21, Docker, and the Maven wrapper.

```bash
docker compose up -d postgres kafka
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
docker compose up -d postgres kafka
.\mvnw.cmd spring-boot:run
```

Postgres is published on **host port 5433** (container 5432) so it does not collide with a local Windows PostgreSQL on 5432.

The Compose database name and user remain `apexledger` (internal identifier). The Postgres container is named `saakh-postgres`. Kafka is `saakh-kafka` on host port **9092**.

Verify seed data:

```bash
curl -H "X-API-Key: local-dev-key" http://localhost:8080/v1/accounts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
```

Local keys (not secrets): `local-dev-key` for `/v1/**` and `/api/**`, `local-internal-key` for `/internal/**`.

## Tests

```bash
./mvnw test
```

`MoneyTest`, `RequestFingerprintTest`, and `SettlementFingerprintTest` always run. `FoundationSchemaTest`, `TransferApiTest`, and Phase 4 tests use Testcontainers Postgres and disable the outbox poller (no Kafka). `OutboxPublisherTest` uses Testcontainers Postgres and Kafka.

## Implemented guarantees

These are implemented in the current Phase 1–4 codebase. They are not future targets.

| Guarantee | Mechanism |
|---|---|
| Duplicate request does not move money twice | Durable Postgres idempotency (Phase 2) |
| Balance does not go negative | Transaction + `SELECT FOR UPDATE` (Phase 2) |
| Every posted transfer is balanced | Double-entry ledger lines (Phase 2) |
| Ledger history is append-only at the application path | `ledger_lines` inserts only; no update/delete in posting |
| DB commit does not silently lose events | Outbox row in the posting transaction (Phase 2) |
| Unpublished events reach Kafka | Poller + `SELECT FOR UPDATE SKIP LOCKED` (Phase 3, at-least-once) |
| Internal books can be checked | Read-only integrity (Phase 4) |
| Settlement mismatches are detectable | Detect-only reconciliation (Phase 4) |

Not implemented: consumer-side deduplication of a republished `eventId`. The V1 `notifications.event_id` UNIQUE column exists for a later phase and is unused.

## What this project does not claim

- Not a banking system, UPI implementation, or PCI/RBI-compliant product
- Not Kafka exactly-once semantics
- Not globally distributed
- Not production-ready for unlimited scale
