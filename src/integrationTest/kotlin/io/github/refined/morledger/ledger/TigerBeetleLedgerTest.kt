package io.github.refined.morledger.ledger

import com.tigerbeetle.Client
import com.tigerbeetle.UInt128
import io.github.refined.morledger.TestDatabase
import io.github.refined.morledger.db.transaction
import io.github.refined.morledger.money.Split
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Runs against a real TigerBeetle when `TB_ADDRESS` is set (the compose `test` profile starts one); skipped otherwise.
 * Every test uses a fresh id namespace, so tests stay isolated inside the shared TigerBeetle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TigerBeetleLedgerTest {
    private val address: String? = System.getenv("TB_ADDRESS")
    private val client: Client? by lazy {
        address?.let {
            val ip = InetAddress.getByName(it.substringBeforeLast(':')).hostAddress
            Client(UInt128.asBytes(0), arrayOf("$ip:${it.substringAfterLast(':')}"))
        }
    }

    private fun engine() = TigerBeetleLedger(client!!, namespace = UUID.randomUUID().toString())

    @BeforeEach
    fun requireTigerBeetle() = assumeTrue(address != null, "TB_ADDRESS not set: no TigerBeetle to test against")

    @AfterAll
    fun close() {
        if (address != null) client?.close()
    }

    @Test
    fun `tigerbeetle reports the same balances as the postgres ledger`() {
        val expected = SampleFlow.run(PostgresLedger())
        val actual = SampleFlow.run(engine())
        assertEquals(expected, actual)
    }

    @Test
    fun `a retried entry is posted once`() {
        TestDatabase.reset()
        val engine = engine()
        val entry = NewJournalEntry(
            EntryKind.CAPTURE, "p_retry", null, SampleFlow.t0,
            ChartOfAccounts.capture("EUR", "NL", "m_1", Split(tax = 2100, fee = 605, merchant = 9395)),
        )
        repeat(3) { TestDatabase.dataSource.transaction { conn -> engine.post(conn, entry) } }

        val balances = TestDatabase.dataSource.transaction { engine.balances(it) }.associate { it.account.code to it.balance }
        assertEquals(12_100, balances["psp_clearing"])
        assertEquals(9_395, balances["merchant_payable:m_1"])
    }

    @Test
    fun `historical balances and journal browsing are not supported`() {
        val engine = engine()
        assertFailsWith<UnsupportedLedgerOperation> { TestDatabase.dataSource.transaction { engine.balances(it, asOf = SampleFlow.t0) } }
        assertFailsWith<UnsupportedLedgerOperation> { TestDatabase.dataSource.transaction { engine.entries(it) } }
    }
}
