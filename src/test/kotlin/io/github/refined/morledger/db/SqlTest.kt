package io.github.refined.morledger.db

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SqlTest {
    @Test
    fun `parameters preserve order and repeated values`() {
        val query = sql {
            """
            SELECT *
            FROM payments
            WHERE payment_id = ${param("p1")} OR merchant_id = ${param("p1")}
            LIMIT ${param(10)}
            """
        }
        assertEquals("SELECT *\nFROM payments\nWHERE payment_id = ? OR merchant_id = ?\nLIMIT ?", query.text)
        assertEquals(listOf("p1", "p1", 10), query.parameters)
    }

    @Test
    fun `null and typed values are retained for JDBC binding`() {
        val time = Instant.parse("2026-10-01T10:00:00Z")
        val rate = BigDecimal("0.21")
        val query = sql { "VALUES (${param(null)}, ${param(12100L)}, ${param(rate)}, ${param(time)})" }
        assertEquals("VALUES (?, ?, ?, ?)", query.text)
        assertEquals(listOf(null, 12100L, rate, time), query.parameters)
    }

    @Test
    fun `optional filters register only parameters present in SQL`() {
        fun query(merchantId: String?, from: Instant?) = sql {
            """
            SELECT *
            FROM payments
            WHERE ${where {
                if (merchantId != null) add("merchant_id = ${param(merchantId)}")
                if (from != null) add("captured_at >= ${param(from)}")
            }}
            LIMIT ${param(5)}
            """
        }
        val time = Instant.parse("2026-10-01T10:00:00Z")
        assertEquals(listOf(5), query(null, null).parameters)
        assertEquals("SELECT *\nFROM payments\nWHERE TRUE\nLIMIT ?", query(null, null).text)
        assertEquals(listOf("m1", 5), query("m1", null).parameters)
        assertEquals("SELECT *\nFROM payments\nWHERE merchant_id = ?\nLIMIT ?", query("m1", null).text)
        assertEquals(listOf(time, 5), query(null, time).parameters)
        assertEquals(listOf("m1", time, 5), query("m1", time).parameters)
        assertEquals("SELECT *\nFROM payments\nWHERE merchant_id = ? AND captured_at >= ?\nLIMIT ?", query("m1", time).text)
    }

    @Test
    fun `quotes and SQL syntax remain parameter data`() {
        val input = "p1' OR '1'='1'; -- ?"
        val query = sql {
            """
            SELECT payment_id
            FROM payments
            WHERE payment_id = ${param(input)}
            """
        }
        assertEquals("SELECT payment_id\nFROM payments\nWHERE payment_id = ?", query.text)
        assertEquals(listOf(input), query.parameters)
        assertFalse(query.text.contains(input))
    }
}
