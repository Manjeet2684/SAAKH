# SAAKH

**Trust Through Every Transaction.**

SAAKH is a distributed financial transaction and reconciliation platform built to preserve consistency and trust under retries, duplicate requests, partial failures, and asynchronous service communication.

**Phase 1 (current):** foundation only — schema, seed data, application boot, API-key gate, read-only account lookup.

It is not a bank, not UPI, not a Razorpay clone, and not an authentication product.

## What exists in Phase 1

- Spring Boot 3.5 / Java 21 / Maven
- PostgreSQL schema owned by Flyway (`ddl-auto=validate`)
- Docker Compose for Postgres and Kafka (the app does **not** talk to Kafka yet)
- Deterministic demo wallets
- `GET /v1/accounts/{id}` behind `X-API-Key`

## What is not implemented yet

Transfers, locking, idempotency logic, outbox publisher, Kafka producer/consumer, reconciliation, and fault injection. Those are later phases.

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
docker compose up -d postgres
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Postgres is published on **host port 5433** (container 5432) so it does not collide with a local Windows PostgreSQL on 5432.

The Compose database name and user remain `apexledger` (internal identifier). The Postgres container is named `saakh-postgres`.

Kafka is in Compose for later phases. Phase 1 does not need it:

```bash
docker compose up -d postgres kafka
```

Verify seed data:

```bash
curl -H "X-API-Key: local-dev-key" http://localhost:8080/v1/accounts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
```

Local keys (not secrets): `local-dev-key` for `/v1/**`, `local-internal-key` for `/internal/**`.

## Tests

```bash
./mvnw test
```

`MoneyTest` always runs. `FoundationSchemaTest` uses Testcontainers Postgres and is skipped if Docker is not available (`disabledWithoutDocker`).

## SYSTEM GUARANTEES (target; not all enforced in Phase 1)

| Guarantee | Mechanism |
|---|---|
| Duplicate request does not move money twice | Durable Postgres idempotency (Phase 3) |
| Balance does not go negative | Transaction + row locking (Phase 2) |
| Every posted transfer is balanced | Double-entry ledger lines (Phase 2) |
| Ledger history is immutable | Append-only `ledger_lines` |
| DB commit does not silently lose events | Transactional outbox (Phase 4) |
| Duplicate event does not duplicate side effect | `notifications.event_id` UNIQUE (Phase 4) |
| Balance drift is detectable | Detect-only reconciliation (Phase 5) |

## What this project does not claim

- Not a banking system, UPI implementation, or PCI/RBI-compliant product
- Not Kafka exactly-once semantics
- Not globally distributed
- Not production-ready for unlimited scale
