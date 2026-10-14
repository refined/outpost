# Mini MoR Ledger Service

A small Merchant of Record (MoR) ledger service. It records captured payments and refunds and reports:
- how much the MoR owes tax authorities;
- each merchant's balance with the MoR.

> **Status: work in progress.** This commit is the project skeleton. The service starts, connects to Postgres and runs migrations; the ledger endpoints are stubs that return `501 Not Implemented`.

## Stack

- Kotlin 2.4 on JVM 17
- Ktor 3: Netty, kotlinx.serialization
- PostgreSQL 17, accessed through plain JDBC (HikariCP); schema migrations with Flyway
- Tests: JUnit 6, Ktor test host, Testcontainers

There is deliberately no DI framework and no ORM. The interesting part of a ledger is its SQL and its transaction boundaries, so those stay explicit.

## Running locally

Prerequisites: JDK 17+ and Docker.

```bash
docker compose up -d --wait       # Postgres on localhost:5442
./gradlew run                     # service on http://localhost:8090
curl localhost:8090/health        # {"status":"ok"}
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Configuration is read from environment variables:

| Variable | Default |
|---|---|
| `PORT` | `8090` |
| `DB_URL` | `jdbc:postgresql://localhost:5442/mor_ledger` |
| `DB_USER` | `mor` |
| `DB_PASSWORD` | `mor` |

The non-default ports (5442, 8090) are there to avoid clashing with a local Postgres or another service on 5432/8080.

## Tests

```bash
./gradlew test
```

Integration tests start their own Postgres through Testcontainers, so Docker must be running.

## API

The shapes below are provisional.

| Method | Path | Purpose | Status |
|---|---|---|---|
| `GET` | `/health` | Liveness + database check | ✅ |
| `POST` | `/v1/payments` | Capture a payment for a merchant | 🚧 stub |
| `POST` | `/v1/payments/{paymentId}/refunds` | Refund a captured payment | 🚧 stub |
| `GET` | `/v1/reports/balances` | Tax owed to authorities and merchant balances | 🚧 stub |

## Ledger design

The service uses its **own double-entry ledger on PostgreSQL** rather than an off-the-shelf ledger:
- every business event is one balanced journal entry (Σ debits = Σ credits), stored append-only;
- balances are derived from postings, never kept in a mutable column;
- the business record (payment, refund) and its postings commit in the same database transaction, so there is no dual write between an application database and an external ledger.

The trade-offs section will cover TigerBeetle and Formance as alternatives.

## Assumptions

_To be documented together with the implementation._

## Trade-offs

_To be documented together with the implementation._

## Domain background

[docs/domain/](docs/domain/) has background notes on the MoR model, EU VAT and ledger postings.
