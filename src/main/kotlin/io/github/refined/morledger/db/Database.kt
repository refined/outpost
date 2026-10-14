package io.github.refined.morledger.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

object Database {
    fun connect(url: String, user: String, password: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 10
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            },
        )

    fun migrate(dataSource: DataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }
}

/**
 * Runs [block] in a single database transaction: commits on success, rolls back on any exception.
 * Every ledger write (business record + journal entry + postings) goes through one of these.
 */
fun <T> DataSource.transaction(block: (Connection) -> T): T =
    connection.use { conn ->
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }
