package io.github.refined.morledger

import com.zaxxer.hikari.HikariDataSource
import io.github.refined.morledger.db.Database
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.ledger.Ledger
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One migrated Postgres per test JVM, shared by all test classes.
 *
 * - `TEST_DB_URL` set: use that database (CI on macOS/Windows runners, the compose `test` service). Its schema is
 *   dropped and re-created first, so a database left over from an older schema can't break the run.
 *   Never point it at a database you care about.
 * - Otherwise: start a throwaway Postgres via Testcontainers; Ryuk removes it when the JVM exits.
 */
object TestDatabase {
    val dataSource: HikariDataSource by lazy {
        val env = System.getenv()
        val external = env["TEST_DB_URL"]
        val ds = if (external != null) {
            Database.connect(external, env["TEST_DB_USER"] ?: "mor", env["TEST_DB_PASSWORD"] ?: "mor").also { ds ->
                ds.transaction { conn ->
                    conn.createStatement().use {
                        it.execute("DROP SCHEMA IF EXISTS ledger CASCADE; DROP SCHEMA public CASCADE; CREATE SCHEMA public")
                    }
                }
            }
        } else {
            val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
            Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        }
        Database.migrate(ds)
        ds
    }

    /** Fails if any account's stored running balance differs from the sum of its postings. */
    fun assertLedgerConsistent() {
        val mismatches = dataSource.transaction { Ledger.verify(it) }
        check(mismatches.isEmpty()) { "stored balances differ from postings: $mismatches" }
    }

    /** Empties all business and ledger tables. TRUNCATE is deliberately not blocked by the append-only triggers. */
    fun reset() {
        dataSource.transaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "TRUNCATE refunds, payments, ledger.postings, ledger.journal_entries, ledger.accounts RESTART IDENTITY",
                )
            }
        }
    }
}
