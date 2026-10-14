package io.github.refined.morledger.db

import io.github.refined.morledger.TestDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The ledger relies on [transaction] for atomicity, so its commit/rollback contract is pinned down here. */
class TransactionTest {
    private val dataSource = TestDatabase.dataSource

    @BeforeEach
    fun resetProbeTable() {
        dataSource.transaction { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS tx_probe (id INT PRIMARY KEY)")
                it.execute("TRUNCATE tx_probe")
            }
        }
    }

    @Test
    fun `commits when the block succeeds and returns its value`() {
        val result = dataSource.transaction { conn ->
            conn.createStatement().use { it.executeUpdate("INSERT INTO tx_probe VALUES (1), (2)") }
        }

        assertEquals(2, result)
        assertEquals(2, probeRows())
    }

    @Test
    fun `rolls back every statement when the block throws`() {
        assertFailsWith<IllegalStateException> {
            dataSource.transaction { conn ->
                conn.createStatement().use { it.executeUpdate("INSERT INTO tx_probe VALUES (1)") }
                error("boom")
            }
        }

        assertEquals(0, probeRows())
    }

    @Test
    fun `rolls back when the database rejects a statement`() {
        assertFailsWith<java.sql.SQLException> {
            dataSource.transaction { conn ->
                conn.createStatement().use { it.executeUpdate("INSERT INTO tx_probe VALUES (1)") }
                conn.createStatement().use { it.executeUpdate("INSERT INTO tx_probe VALUES (1)") } // duplicate key
            }
        }

        assertEquals(0, probeRows())
    }

    private fun probeRows(): Int = dataSource.transaction { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM tx_probe").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
