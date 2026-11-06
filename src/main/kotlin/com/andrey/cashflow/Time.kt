package com.andrey.cashflow

object Time {

    private val whole = Regex("^(\\d+[smhdw])+$", RegexOption.IGNORE_CASE)
    private val part = Regex("(\\d+)([smhdw])", RegexOption.IGNORE_CASE)
    private val units = mapOf('s' to 1_000L, 'm' to 60_000L, 'h' to 3_600_000L, 'd' to 86_400_000L, 'w' to 604_800_000L)

    fun parse(input: String): Long? {
        if (!whole.matches(input)) return null
        var total = 0L
        for (match in part.findAll(input)) {
            val count = match.groupValues[1].toLongOrNull() ?: return null
            val unit = units.getValue(match.groupValues[2].lowercase()[0])
            total = runCatching { Math.addExact(total, Math.multiplyExact(count, unit)) }.getOrNull() ?: return null
        }
        return total.takeIf { it > 0 }
    }

    fun ago(millis: Long): String = when {
        millis < 60_000 -> "${millis / 1_000}s"
        millis < 3_600_000 -> "${millis / 60_000}m"
        millis < 86_400_000 -> "${millis / 3_600_000}h"
        else -> "${millis / 86_400_000}d"
    }
}
