# SAAKH

**Trust Through Every Transaction.**

SAAKH is a distributed financial transaction and reconciliation platform built to preserve consistency and trust under retries, duplicate requests, partial failures, and asynchronous service communication.

**Phase 3 (current):** transactional outbox publisher — unpublished events are copied to Kafka with at-least-once delivery.

It is not a bank, not UPI, not a Razorpay clone, and not an authentication product.

## What exists in Phase 1

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

## What is not implemented yet

Kafka consumers, notification side effects, reconciliation, reversals, and fault injection. Those are later phases.

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "X-API-Key: local-dev-key" \
  -H "Idempotency-Key: demo-alice-bob-1" \
  -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"destinationAccountId\":\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\",\"amountMinor\":1000,\"currency\":\"INR\"}"
```

## Demo accounts

Seeded by Flyway. `SYSTEM_FLOAT` is **not** a real money-issuance system. It exists only so demo customer wallets have balanced double-entry history.

| Name | ID | Kind | Balance |
|---|---|---|---|
| SYSTEM_FLOAT | `00000000-0000-0000-0000-000000000001` | SYSTEM | 999,700,000 paise |
| Alice Wallet | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` | CUSTOMER | 100,000 paise (₹1,000.00) |
| Bob Wallet | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` | CUSTOMER | 100,000 paise (₹1,000.00) |
| Charlie Wallet | `cccccccc-cccc-cccc-cccc-cccccccccccc` | CUSTOMER | 100,000 paise (₹1,000.00) |

`SUM(available_balance_minor)` after seed = `1,000,000,000` paise.

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

`MoneyTest` and `RequestFingerprintTest` always run. `FoundationSchemaTest` and `TransferApiTest` use Testcontainers Postgres and disable the outbox poller (no Kafka). `OutboxPublisherTest` uses Testcontainers Postgres and Kafka.

## SYSTEM GUARANTEES (target; not all enforced in Phase 1)

| Guarantee | Mechanism |
|---|---|
| Duplicate request does not move money twice | Durable Postgres idempotency (Phase 2) |
| Balance does not go negative | Transaction + `SELECT FOR UPDATE` (Phase 2) |
| Every posted transfer is balanced | Double-entry ledger lines (Phase 2) |
| Ledger history is immutable | Append-only `ledger_lines` |
| DB commit does not silently lose events | Outbox row in the posting transaction (Phase 2) |
| Unpublished events reach Kafka | Poller + `SELECT FOR UPDATE SKIP LOCKED` (Phase 3, at-least-once) |
| Duplicate event does not duplicate side effect | `notifications.event_id` UNIQUE (Phase 4) |
| Balance drift is detectable | Detect-only reconciliation (Phase 5) |

## What this project does not claim

- Not a banking system, UPI implementation, or PCI/RBI-compliant product
- Not Kafka exactly-once semantics
- Not globally distributed
- Not production-ready for unlimited scale
