package com.andrey.cashflow

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Money {

    private val amountPattern = Regex("^(\\d+(?:\\.\\d+)?)([kmbt]?)$", RegexOption.IGNORE_CASE)
    private val multipliers = mapOf("" to 1L, "k" to 1_000L, "m" to 1_000_000L, "b" to 1_000_000_000L, "t" to 1_000_000_000_000L)
    private val suffixes = listOf("K", "M", "B", "T", "Q")
    private val thousand = BigDecimal.valueOf(1000)

    fun parse(input: String, decimals: Int, allowZero: Boolean = false): Long? {
        val match = amountPattern.matchEntire(input.replace(",", "").trim()) ?: return null
        val value = BigDecimal(match.groupValues[1])
            .multiply(BigDecimal.valueOf(multipliers.getValue(match.groupValues[2].lowercase())))
            .setScale(decimals, RoundingMode.DOWN)
        val cents = runCatching { value.movePointRight(2).longValueExact() }.getOrNull() ?: return null
        return cents.takeIf { it > 0 || allowZero }
    }

    fun short(cents: Long, decimals: Int): String {
        val value = BigDecimal.valueOf(cents, 2)
        val sign = if (value.signum() < 0) "-" else ""
        var scaled = value.abs()
        if (scaled < thousand) return sign + plain(scaled.setScale(decimals, RoundingMode.DOWN))
        var index = -1
        while (scaled >= thousand && index < suffixes.lastIndex) {
            scaled = scaled.movePointLeft(3)
            index++
        }
        return sign + plain(scaled.setScale(1, RoundingMode.DOWN)) + suffixes[index]
    }

    fun full(cents: Long, decimals: Int): String {
        val value = BigDecimal.valueOf(cents, 2).setScale(decimals, RoundingMode.DOWN)
        val whole = value.stripTrailingZeros().scale() <= 0
        val pattern = if (whole || decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        val format = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        format.roundingMode = RoundingMode.DOWN
        return format.format(value)
    }

    fun raw(cents: Long): String = plain(BigDecimal.valueOf(cents, 2))

    fun fromDouble(amount: Double): Long? {
        if (!amount.isFinite()) return null
        return runCatching { BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull()
    }

    fun toDouble(cents: Long): Double = cents / 100.0

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
