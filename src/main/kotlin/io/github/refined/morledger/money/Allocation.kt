package io.github.refined.morledger.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

enum class RefundFeePolicy {
    MOR_KEEPS_FEE,

    MOR_RETURNS_FEE,
}

data class Split(val tax: Long, val fee: Long, val merchant: Long) {
    val total: Long get() = tax + fee + merchant

    operator fun plus(other: Split) = Split(tax + other.tax, fee + other.fee, merchant + other.merchant)

    operator fun minus(other: Split) = Split(tax - other.tax, fee - other.fee, merchant - other.merchant)

    companion object {
        val ZERO = Split(0, 0, 0)
    }
}

object Allocation {
    fun splitCapture(gross: Long, taxRate: BigDecimal, feeRate: BigDecimal): Split {
        require(gross > 0) { "amount must be positive" }
        require(taxRate.signum() >= 0 && taxRate < BigDecimal.ONE) { "taxRate must be in [0, 1)" }
        require(feeRate.signum() >= 0 && feeRate < BigDecimal.ONE) { "feeRate must be in [0, 1)" }

        val g = BigDecimal.valueOf(gross)
        val tax = g.multiply(taxRate).divide(BigDecimal.ONE + taxRate, 0, RoundingMode.HALF_UP).longValueExact()
        val fee = g.multiply(feeRate).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val merchant = gross - tax - fee
        require(merchant >= 0) { "tax ($tax) plus fee ($fee) exceed the amount ($gross)" }
        return Split(tax, fee, merchant)
    }

    fun refundable(captured: Split, policy: RefundFeePolicy): Split = when (policy) {
        RefundFeePolicy.MOR_KEEPS_FEE -> Split(tax = captured.tax, fee = 0, merchant = captured.merchant + captured.fee)
        RefundFeePolicy.MOR_RETURNS_FEE -> captured
    }

    fun allocateRefund(remaining: Split, amount: Long): Split {
        require(amount > 0) { "refund amount must be positive" }
        require(amount <= remaining.total) { "refund amount $amount exceeds the refundable ${remaining.total}" }
        if (amount == remaining.total) return remaining

        // Tie-break order merchant → fee → tax: a cent on a tie never reduces the tax owed (conservative for the authority).
        val buckets = longArrayOf(remaining.merchant, remaining.fee, remaining.tax)
        require(buckets.all { it >= 0 })
        val a = BigInteger.valueOf(amount)
        val t = BigInteger.valueOf(remaining.total)
        val shares = LongArray(buckets.size)
        val remainders = Array(buckets.size) { BigInteger.ZERO }
        for (i in buckets.indices) {
            val (q, r) = BigInteger.valueOf(buckets[i]).multiply(a).divideAndRemainder(t)
            shares[i] = q.longValueExact()
            remainders[i] = r
        }
        val leftover = (amount - shares.sum()).toInt()
        buckets.indices
            .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
            .take(leftover)
            .forEach { shares[it]++ }
        val (merchant, fee, tax) = shares
        return Split(tax = tax, fee = fee, merchant = merchant)
    }
}
