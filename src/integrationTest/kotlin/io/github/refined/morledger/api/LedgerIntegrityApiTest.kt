package io.github.refined.morledger.api

import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Data goes in through the API; the tampering goes around it, straight to the database, which must refuse or detect it. */
class LedgerIntegrityApiTest {
    @BeforeEach
    fun reset() = TestDatabase.reset()

    private fun sql(statement: String) =
        TestDatabase.dataSource.transaction { conn -> conn.createStatement().use { it.executeUpdate(statement) } }

    @Test
    fun `verify reports a stored balance that drifted from its postings`() = api {
        client.capture("p_1", amount = 12_100)
        sql("UPDATE ledger.accounts SET balance = balance + 1 WHERE code = 'psp_clearing'")

        val verify = client.getJson("/v1/ledger/verify")
        assertEquals("false", verify.str("ok"))
        val mismatch = verify.objects("mismatches").single()
        assertEquals("psp_clearing", mismatch.str("code"))
        assertEquals(12_101, mismatch.long("stored"))
        assertEquals(12_100, mismatch.long("fromPostings"))
    }

    @Test
    fun `database rejects an unbalanced posting and rolls the transaction back`() = api {
        client.capture("p_1")

        val error = assertFailsWith<SQLException> {
            sql(
                """
                INSERT INTO ledger.postings (entry_id, account_id, amount)
                SELECT (SELECT max(id) FROM ledger.journal_entries), id, 1 FROM ledger.accounts LIMIT 1
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("unbalanced"), error.message)
        assertEquals(4, client.getJson("/v1/journal").objects("entries").single().objects("postings").size)
    }

    @Test
    fun `history is append-only and accounts only change their balance`() = api {
        client.capture("p_1")
        client.refund("p_1", "r_1", amount = 100)

        for ((statement, message) in listOf(
            "UPDATE ledger.postings SET amount = amount * 2" to "append-only",
            "DELETE FROM ledger.postings" to "append-only",
            "UPDATE ledger.journal_entries SET effective_at = now()" to "append-only",
            "DELETE FROM ledger.journal_entries" to "append-only",
            "DELETE FROM ledger.accounts" to "append-only",
            "UPDATE ledger.accounts SET code = 'renamed'" to "only balance may change",
            "UPDATE ledger.accounts SET currency = 'USD'" to "only balance may change",
            "UPDATE payments SET amount = 1" to "append-only",
            "DELETE FROM refunds" to "append-only",
        )) {
            val error = assertFailsWith<SQLException>(statement) { sql(statement) }
            assertTrue(error.message!!.contains(message), "$statement: ${error.message}")
        }
        client.assertBooksBalance()
    }
}
