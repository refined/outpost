package io.github.refined.morledger.payments

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Currency

private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
private val JURISDICTION = Regex("^[A-Z]{2}(-[A-Z0-9]{1,3})?$")
private val CURRENCY = Regex("^[A-Z]{3}$")

fun validateId(field: String, value: String) {
    if (!ID.matches(value)) {
        throw ValidationException("$field must be 1-128 characters of letters, digits, '_', '.', ':' or '-'")
    }
}

fun validateCurrency(value: String): String {
    val code = value.uppercase()
    val known = CURRENCY.matches(code) && runCatching { Currency.getInstance(code) }.isSuccess
    if (!known) throw ValidationException("currency must be an ISO 4217 code, e.g. EUR")
    return code
}

fun validateJurisdiction(value: String): String {
    if (!JURISDICTION.matches(value)) {
        throw ValidationException("taxJurisdiction must be an ISO 3166 code, e.g. NL or US-CA")
    }
    return value
}

fun validateRate(field: String, value: BigDecimal): BigDecimal {
    val rate = value.stripTrailingZeros()
    if (rate.signum() < 0 || rate >= BigDecimal.ONE) throw ValidationException("$field must be a fraction in [0, 1), e.g. \"0.21\"")
    if (rate.scale() > 6) throw ValidationException("$field supports at most 6 decimal places")
    return if (rate.scale() < 0) rate.setScale(0) else rate
}

internal fun fingerprint(vararg parts: Any?): String {
    val canonical = parts.joinToString("|") { it?.toString() ?: "" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
}
