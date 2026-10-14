package io.github.refined.morledger

import com.zaxxer.hikari.HikariDataSource
import io.github.refined.morledger.db.Database
import io.github.refined.morledger.db.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One migrated Postgres per test JVM, shared by all test classes.
 *
 * - `TEST_DB_URL` set: use that database. CI uses this on macOS/Windows runners, which have no Docker
 *   for Linux containers.
 * - Otherwise: start a throwaway Postgres via Testcontainers; Ryuk removes it when the JVM exits.
 */
object TestDatabase {
    val dataSource: HikariDataSource by lazy {
        val env = System.getenv()
        val external = env["TEST_DB_URL"]
        val ds = if (external != null) {
            Database.connect(external, env["TEST_DB_USER"] ?: "mor", env["TEST_DB_PASSWORD"] ?: "mor")
        } else {
            val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
            Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        }
        Database.migrate(ds)
        ds
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
