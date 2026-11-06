package com.andrey.cashflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {

    @Test
    fun parsesPlainNumbers() {
        assertEquals(50000L, Money.parse("500", 2))
        assertEquals(123L, Money.parse("1.23", 2))
    }

    @Test
    fun parsesSuffixes() {
        assertEquals(250000L, Money.parse("2.5k", 2))
        assertEquals(250000L, Money.parse("2.5K", 2))
        assertEquals(100_000_000L, Money.parse("1m", 2))
        assertEquals(100_000_000_000L, Money.parse("1b", 2))
        assertEquals(100_000_000_000_000L, Money.parse("1t", 2))
    }

    @Test
    fun parsesCommas() {
        assertEquals(150000L, Money.parse("1,500", 2))
    }

    @Test
    fun cutsExtraDecimalsDown() {
        assertEquals(123L, Money.parse("1.239", 2))
        assertEquals(100L, Money.parse("1.99", 0))
        assertEquals(150L, Money.parse("1.59", 1))
    }

    @Test
    fun rejectsBadAmounts() {
        assertNull(Money.parse("0", 2))
        assertNull(Money.parse("-5", 2))
        assertNull(Money.parse("abc", 2))
        assertNull(Money.parse("1e5", 2))
        assertNull(Money.parse("", 2))
        assertNull(Money.parse("1kk", 2))
        assertNull(Money.parse("0.001", 2))
        assertNull(Money.parse("0.5", 0))
        assertNull(Money.parse("999999999999999999t", 2))
        assertNull(Money.parse("NaN", 2))
    }

    @Test
    fun allowsZeroWhenAsked() {
        assertEquals(0L, Money.parse("0", 2, allowZero = true))
        assertEquals(0L, Money.parse("0.001", 2, allowZero = true))
        assertEquals(50000L, Money.parse("500", 2, allowZero = true))
        assertNull(Money.parse("-1", 2, allowZero = true))
    }

    @Test
    fun shortFormatBelowThousand() {
        assertEquals("0", Money.short(0, 2))
        assertEquals("1", Money.short(100, 2))
        assertEquals("1.5", Money.short(150, 2))
        assertEquals("999.99", Money.short(99999, 2))
        assertEquals("1", Money.short(150, 0))
    }

    @Test
    fun shortFormatWithSuffixCutsDown() {
        assertEquals("1K", Money.short(100000, 2))
        assertEquals("1.5K", Money.short(150000, 2))
        assertEquals("999.9K", Money.short(99_999_999, 2))
        assertEquals("123.4K", Money.short(12_345_600, 2))
        assertEquals("2.5M", Money.short(250_000_000, 2))
        assertEquals("1B", Money.short(100_000_000_000, 2))
        assertEquals("1T", Money.short(100_000_000_000_000, 2))
        assertEquals("1Q", Money.short(100_000_000_000_000_000, 2))
        assertEquals("-1.5K", Money.short(-150000, 2))
    }

    @Test
    fun fullFormat() {
        assertEquals("1,500", Money.full(150000, 2))
        assertEquals("1,500.50", Money.full(150050, 2))
        assertEquals("1", Money.full(100, 2))
        assertEquals("0.05", Money.full(5, 2))
        assertEquals("1,500", Money.full(150050, 0))
        assertEquals("1,500.5", Money.full(150050, 1))
        assertEquals("-1,500", Money.full(-150000, 2))
    }

    @Test
    fun rawFormat() {
        assertEquals("1500.5", Money.raw(150050))
        assertEquals("1500", Money.raw(150000))
        assertEquals("0.05", Money.raw(5))
    }

    @Test
    fun convertsDoublesToCents() {
        assertEquals(101L, Money.fromDouble(1.005))
        assertEquals(30L, Money.fromDouble(0.1 + 0.2))
        assertEquals(150000L, Money.fromDouble(1500.0))
        assertNull(Money.fromDouble(Double.NaN))
        assertNull(Money.fromDouble(Double.POSITIVE_INFINITY))
        assertNull(Money.fromDouble(1e30))
    }

    @Test
    fun convertsCentsToDoubles() {
        assertEquals(1500.5, Money.toDouble(150050))
    }
}
