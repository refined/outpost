# Mini MoR Ledger Service

[![CI](https://github.com/refined/outpost/actions/workflows/ci.yml/badge.svg)](https://github.com/refined/outpost/actions/workflows/ci.yml)

A small Merchant of Record (MoR) ledger service. It records captured payments and refunds and reports:
- how much the MoR owes tax authorities;
- each merchant's balance with the MoR.

> **Status: work in progress.** This commit is the project skeleton. The service starts, connects to Postgres and runs migrations; the ledger endpoints are stubs that return `501 Not Implemented`.

- What we build and why: [docs/requirements.md](docs/requirements.md).
- Working notes, decisions and open questions: [AGENTS.md](AGENTS.md).

## Stack

- Kotlin 2.4 on JVM 17
- Ktor 3: Netty, kotlinx.serialization
- PostgreSQL 17, accessed through plain JDBC (HikariCP); schema migrations with Flyway
- Tests: JUnit 6, Ktor test host, Testcontainers
- Build: Gradle 9 (Kotlin DSL) via the wrapper; only a JDK is needed, no Gradle install

There is deliberately no DI framework and no ORM. The interesting part of a ledger is its SQL and its transaction boundaries, so those stay explicit.

## Running locally

Prerequisites: JDK 17+ and Docker. Works on macOS, Linux and Windows.

```bash
docker compose up -d --wait       # Postgres on localhost:5442
./gradlew run                     # service on http://localhost:8090
curl localhost:8090/health        # {"status":"ok"}
```

On Windows, use `gradlew.bat` (cmd) or `.\gradlew.bat` (PowerShell) instead of `./gradlew`. Git Bash works with `./gradlew` as is.

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

Integration tests run against a real Postgres:

- **Default:** Testcontainers starts a throwaway `postgres:17-alpine`, so Docker must be running.
- **Without Docker:** point the tests at any Postgres you have:
  ```bash
  TEST_DB_URL=jdbc:postgresql://localhost:5432/mor_ledger_test TEST_DB_USER=... TEST_DB_PASSWORD=... ./gradlew test
  ```
  The tests create tables in that database, so use a dedicated database.

### CI

[GitHub Actions](.github/workflows/ci.yml) runs `build` (compile + all tests) on every push and pull request. Four jobs:

| Runner | JDK | Postgres |
|---|---|---|
| Ubuntu | 17 | Testcontainers |
| Ubuntu | 25 | Testcontainers |
| macOS | 17 | native |
| Windows | 17 | native, via `gradlew.bat` |

GitHub's macOS and Windows runners can't run Linux containers, so those jobs install Postgres natively and use `TEST_DB_URL`.

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
