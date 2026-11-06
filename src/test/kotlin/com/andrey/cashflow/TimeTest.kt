package com.andrey.cashflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeTest {

    @Test
    fun parsesSingleUnits() {
        assertEquals(30_000L, Time.parse("30s"))
        assertEquals(15 * 60_000L, Time.parse("15m"))
        assertEquals(2 * 3_600_000L, Time.parse("2h"))
        assertEquals(3 * 86_400_000L, Time.parse("3d"))
        assertEquals(7 * 86_400_000L, Time.parse("1w"))
    }

    @Test
    fun parsesCombinations() {
        assertEquals(36 * 3_600_000L, Time.parse("1d12h"))
        assertEquals(90 * 60_000L, Time.parse("1H30M"))
    }

    @Test
    fun rejectsBadTimes() {
        assertNull(Time.parse(""))
        assertNull(Time.parse("5"))
        assertNull(Time.parse("5x"))
        assertNull(Time.parse("h"))
        assertNull(Time.parse("0m"))
        assertNull(Time.parse("99999999999999w"))
    }

    @Test
    fun formatsAgo() {
        assertEquals("0s", Time.ago(400))
        assertEquals("45s", Time.ago(45_000))
        assertEquals("5m", Time.ago(5 * 60_000L + 10_000))
        assertEquals("2h", Time.ago(2 * 3_600_000L + 59 * 60_000L))
        assertEquals("3d", Time.ago(3 * 86_400_000L))
    }
}
