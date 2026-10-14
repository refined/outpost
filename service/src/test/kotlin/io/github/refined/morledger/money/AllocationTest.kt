package io.github.refined.morledger.money

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AllocationTest {
    private val vat21 = BigDecimal("0.21")
    private val fee5 = BigDecimal("0.05")

    @Test
    fun `capture splits a tax-inclusive gross`() {
        // €121.00 incl. 21% VAT: net 100.00, VAT 21.00; fee 5% of gross = 6.05
        assertEquals(Split(tax = 2100, fee = 605, merchant = 9395), Allocation.splitCapture(12100, vat21, fee5))
    }

    @Test
    fun `capture rounds half up and gives the merchant the exact remainder`() {
        // €9.99 incl. 19%: tax = 999 * 0.19 / 1.19 = 159.50.. -> 160; fee = 49.95 -> 50
        val split = Allocation.splitCapture(999, BigDecimal("0.19"), fee5)
        assertEquals(Split(tax = 160, fee = 50, merchant = 789), split)
        assertEquals(999, split.total)
    }

    @Test
    fun `zero tax rate and zero fee are allowed`() {
        assertEquals(Split(0, 0, 500), Allocation.splitCapture(500, BigDecimal.ZERO, BigDecimal.ZERO))
    }

    @Test
    fun `capture rejects invalid input`() {
        assertFailsWith<IllegalArgumentException> { Allocation.splitCapture(0, vat21, fee5) }
        assertFailsWith<IllegalArgumentException> { Allocation.splitCapture(100, BigDecimal.ONE, fee5) }
        assertFailsWith<IllegalArgumentException> { Allocation.splitCapture(100, BigDecimal("-0.1"), fee5) }
        assertFailsWith<IllegalArgumentException> { Allocation.splitCapture(100, BigDecimal("0.9"), BigDecimal("0.9")) }
    }

    @Test
    fun `refundable part depends on the fee policy`() {
        val captured = Split(tax = 2100, fee = 605, merchant = 9395)
        assertEquals(Split(2100, 0, 10000), Allocation.refundable(captured, RefundFeePolicy.MOR_KEEPS_FEE))
        assertEquals(captured, Allocation.refundable(captured, RefundFeePolicy.MOR_RETURNS_FEE))
    }

    @Test
    fun `half refund then the rest returns exactly what was captured`() {
        val refundable = Allocation.refundable(Split(2100, 605, 9395), RefundFeePolicy.MOR_RETURNS_FEE)

        val first = Allocation.allocateRefund(refundable, 6050)
        assertEquals(Split(tax = 1050, fee = 302, merchant = 4698), first)

        val second = Allocation.allocateRefund(refundable - first, 6050)
        assertEquals(refundable, first + second)
    }

    @Test
    fun `refund cannot exceed what is refundable`() {
        assertFailsWith<IllegalArgumentException> { Allocation.allocateRefund(Split(1, 0, 9), 11) }
        assertFailsWith<IllegalArgumentException> { Allocation.allocateRefund(Split(1, 0, 9), 0) }
    }

    @Test
    fun `apportion uses largest remainders and breaks ties towards earlier buckets`() {
        assertContentEquals(longArrayOf(1, 0, 0), Allocation.apportion(1, longArrayOf(1, 1, 1)))
        assertContentEquals(longArrayOf(1, 1, 0), Allocation.apportion(2, longArrayOf(1, 1, 1)))
        assertContentEquals(longArrayOf(4, 1), Allocation.apportion(5, longArrayOf(7, 3)))
    }

    /**
     * Property check over random payments and random refund sequences:
     * every refund is split exactly, no part is ever negative or over-refunded,
     * and once the payment is fully refunded each part is back to exactly what was refundable.
     */
    @Test
    fun `random refund sequences never drift`() {
        val random = Random(20261019)
        repeat(10_000) {
            val gross = random.nextLong(1, 1_000_000)
            val taxRate = BigDecimal.valueOf(random.nextInt(0, 2800).toLong(), 4)
            val feeRate = BigDecimal.valueOf(random.nextInt(0, 1500).toLong(), 4)
            val policy = RefundFeePolicy.entries.random(random)
            val refundable = Allocation.refundable(Allocation.splitCapture(gross, taxRate, feeRate), policy)

            var refunded = Split.ZERO
            while (refunded.total < gross) {
                val remaining = refundable - refunded
                val amount = if (random.nextInt(4) == 0) remaining.total else random.nextLong(1, remaining.total + 1)
                val refund = Allocation.allocateRefund(remaining, amount)

                assertEquals(amount, refund.total)
                assertTrue(refund.tax >= 0 && refund.fee >= 0 && refund.merchant >= 0, "negative part in $refund")
                refunded += refund
                val left = refundable - refunded
                assertTrue(left.tax >= 0 && left.fee >= 0 && left.merchant >= 0, "over-refunded: $left")
            }
            assertEquals(refundable, refunded)
        }
    }
}
