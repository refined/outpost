package io.github.refined.morledger.ledger

import com.tigerbeetle.AccountBatch
import com.tigerbeetle.AccountFlags
import com.tigerbeetle.Client
import com.tigerbeetle.CreateAccountStatus
import com.tigerbeetle.CreateTransferStatus
import com.tigerbeetle.IdBatch
import com.tigerbeetle.TransferBatch
import com.tigerbeetle.TransferFlags
import com.tigerbeetle.UInt128
import java.math.BigInteger
import java.net.InetAddress
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.Currency
import java.util.concurrent.ConcurrentHashMap

/**
 * The ledger on TigerBeetle (`LEDGER_ENGINE=TIGERBEETLE`), behind the same [LedgerEngine] interface.
 *
 * Mapping:
 * - an account is a TigerBeetle account; its id is a hash of (code, currency), and its `ledger` is the currency's
 *   ISO 4217 numeric code, so TigerBeetle itself refuses transfers between currencies;
 * - a journal entry becomes **linked transfers** (all or nothing): each credit line is paired with a debit line;
 * - transfer ids are derived from the event (kind, payment, refund, position), so a retried event gets `exists`
 *   back instead of posting twice.
 *
 * **Dual write.** Payments and refunds stay in Postgres. [post] sends the transfers to TigerBeetle *inside* the Postgres
 * transaction, before it commits. If the commit then fails, TigerBeetle has the transfers but Postgres has no
 * payment; the PSP retries the event, Postgres inserts the payment again, and TigerBeetle answers `exists`. An event
 * that is never retried leaves orphan transfers, which the merchant breakdown's ledger check surfaces. Avoiding
 * this window is the main reason the default engine is Postgres (see docs/ledger-comparison.md).
 *
 * Not supported: historical (`asOf`) balances (they need TigerBeetle's account history flag) and journal browsing.
 */
class TigerBeetleLedger(
    private val client: Client,
    /** Salt for account and transfer ids. Tests use a fresh one to stay isolated inside a shared TigerBeetle. */
    private val namespace: String = "",
) : LedgerEngine {
    override val description = "tigerbeetle"

    private val createdAccounts = ConcurrentHashMap.newKeySet<BigInteger>()

    override fun post(conn: Connection, entry: NewJournalEntry): Long? {
        val lines = LedgerEngine.validated(entry)
        val ledger = ledgerOf(lines.first().account.currency)
        ensureAccounts(conn, lines.map { it.account }.distinct(), ledger)

        val transfers = pairUp(lines)
        val batch = TransferBatch(transfers.size)
        transfers.forEachIndexed { i, (debit, credit, amount) ->
            batch.add()
            batch.setId(UInt128.asBytes(transferId(entry, i)))
            batch.setDebitAccountId(UInt128.asBytes(accountId(debit)))
            batch.setCreditAccountId(UInt128.asBytes(accountId(credit)))
            batch.setAmount(BigInteger.valueOf(amount))
            batch.setLedger(ledger)
            batch.setCode(entry.kind.ordinal + 1)
            batch.setFlags(if (i < transfers.lastIndex) TransferFlags.LINKED else TransferFlags.NONE)
        }
        val results = client.createTransfers(batch)
        val statuses = buildList { while (results.next()) add(results.status) }
        // A linked chain is atomic. On a retry the first transfer answers `Exists` and fails the chain, so the others
        // answer `LinkedEventFailed`: that combination means "already posted". Anything else is a real rejection.
        val posted = statuses.all { it == CreateTransferStatus.Created }
        val alreadyPosted = CreateTransferStatus.Exists in statuses &&
            statuses.all { it == CreateTransferStatus.Exists || it == CreateTransferStatus.LinkedEventFailed }
        check(posted || alreadyPosted) { "TigerBeetle rejected ${entry.kind} ${entry.paymentId}: $statuses" }
        return null
    }

    override fun balances(conn: Connection, asOf: Instant?, currency: String?): List<AccountBalance> {
        if (asOf != null) {
            throw UnsupportedLedgerOperation("historical balances are not available with the TigerBeetle engine (they need account history)")
        }
        val registry = registeredAccounts(conn, currency)
        return registry.keys.chunked(LOOKUP_BATCH).flatMap { ids ->
            val batch = IdBatch(ids.size)
            ids.forEach { batch.add(UInt128.asBytes(it)) }
            val found = client.lookupAccounts(batch)
            buildList {
                while (found.next()) {
                    val id = UInt128.asBigInteger(found.id)
                    val signed = found.debitsPosted.subtract(found.creditsPosted).longValueExact()
                    add(AccountBalance(registry.getValue(id), signed))
                }
            }
        }.sortedWith(compareBy({ it.account.currency }, { it.account.code }))
    }

    /** TigerBeetle enforces double entry itself; the cross-check against the business records is the merchant ledger check. */
    override fun verify(conn: Connection): List<BalanceMismatch> = emptyList()

    override fun entries(conn: Connection, paymentId: String?, accountCode: String?, limit: Int, beforeId: Long?): List<JournalEntry> =
        throw UnsupportedLedgerOperation("the journal lives in TigerBeetle; browsing it is not implemented in this service")

    private fun ensureAccounts(conn: Connection, accounts: List<Account>, ledger: Int) {
        // The registry row is (re)inserted in the caller's transaction, so it commits together with the business record.
        conn.prepareStatement(
            "INSERT INTO ledger.tb_accounts (id, code, currency, normal_side) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { st ->
            for (account in accounts.sortedBy { accountId(it) }) {
                st.setBigDecimal(1, accountId(account).toBigDecimal())
                st.setString(2, account.code)
                st.setString(3, account.currency)
                st.setString(4, account.normalSide.name)
                st.addBatch()
            }
            st.executeBatch()
        }
        val missing = accounts.filter { accountId(it) !in createdAccounts }
        if (missing.isEmpty()) return
        val batch = AccountBatch(missing.size)
        for (account in missing) {
            batch.add()
            batch.setId(UInt128.asBytes(accountId(account)))
            batch.setLedger(ledger)
            batch.setCode(if (account.normalSide == NormalSide.DEBIT) 1 else 2)
            batch.setFlags(AccountFlags.NONE)
        }
        val results = client.createAccounts(batch)
        while (results.next()) {
            when (val status = results.status) {
                CreateAccountStatus.Created, CreateAccountStatus.Exists -> Unit
                else -> throw IllegalStateException("TigerBeetle rejected account ${missing[results.position].code}: $status")
            }
        }
        missing.forEach { createdAccounts += accountId(it) }
    }

    private fun registeredAccounts(conn: Connection, currency: String?): Map<BigInteger, Account> =
        conn.prepareStatement(
            "SELECT id, code, currency, normal_side FROM ledger.tb_accounts WHERE CAST(? AS TEXT) IS NULL OR currency = CAST(? AS TEXT)",
        ).use { st ->
            st.setString(1, currency)
            st.setString(2, currency)
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val account = Account(rs.getString(2), rs.getString(3), NormalSide.valueOf(rs.getString(4)))
                        // Rows from another namespace (tests) are skipped: their ids don't match this engine's hashing.
                        val id = rs.getBigDecimal(1).toBigIntegerExact()
                        if (id == accountId(account)) put(id, account)
                    }
                }
            }
        }

    private fun accountId(account: Account): BigInteger = id128("account", namespace, account.code, account.currency)

    private fun transferId(entry: NewJournalEntry, index: Int): BigInteger =
        id128("transfer", namespace, entry.kind.name, entry.paymentId, entry.refundId ?: "", index.toString())

    companion object {
        private const val LOOKUP_BATCH = 8000

        /** Resolves `host:port` to an IP first: TigerBeetle clients accept IP addresses only. */
        fun connect(address: String, clusterId: Long = 0): TigerBeetleLedger {
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':')
            val ip = InetAddress.getByName(host).hostAddress
            return TigerBeetleLedger(Client(UInt128.asBytes(clusterId), arrayOf("$ip:$port")))
        }

        /** Pairs every credit line with debit lines until both are used up: the transfers of one entry. */
        internal fun pairUp(lines: List<Posting>): List<Triple<Account, Account, Long>> {
            val debits = lines.filter { it.amount > 0 }.map { it.account to it.amount }.toMutableList()
            val credits = lines.filter { it.amount < 0 }.map { it.account to -it.amount }.toMutableList()
            val transfers = mutableListOf<Triple<Account, Account, Long>>()
            var d = 0
            var c = 0
            while (d < debits.size && c < credits.size) {
                val amount = minOf(debits[d].second, credits[c].second)
                transfers += Triple(debits[d].first, credits[c].first, amount)
                debits[d] = debits[d].first to debits[d].second - amount
                credits[c] = credits[c].first to credits[c].second - amount
                if (debits[d].second == 0L) d++
                if (credits[c].second == 0L) c++
            }
            return transfers
        }

        private fun ledgerOf(currency: String): Int = Currency.getInstance(currency).numericCode

        /** A stable, non-zero 128-bit id: the first 16 bytes of SHA-256 over the parts. */
        private fun id128(vararg parts: String): BigInteger {
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
            val id = BigInteger(1, digest.copyOfRange(0, 16))
            return if (id.signum() == 0) BigInteger.ONE else id
        }
    }
}
