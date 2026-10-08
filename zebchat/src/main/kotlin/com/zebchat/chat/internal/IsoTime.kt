package com.zebchat.chat.internal

import java.util.Calendar
import java.util.TimeZone

/** ISO 8601 date-times as the API writes them (`2026-10-08T12:00:00.000Z`), without java.time. */
internal object IsoTime {
    private val PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})$""",
    )

    /** Epoch milliseconds, or null when [value] is not a valid date-time. */
    fun parse(value: String): Long? {
        val m = PATTERN.matchEntire(value) ?: return null
        val g = m.groupValues
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val millis = g[7].ifEmpty { "0" }.padEnd(3, '0').substring(0, 3).toInt()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            isLenient = false
            set(g[1].toInt(), month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }
        val utc = try {
            calendar.timeInMillis
        } catch (_: IllegalArgumentException) {
            return null // e.g. February 30
        }
        val zone = g[8]
        if (zone == "Z") return utc
        val digits = zone.substring(1).replace(":", "")
        val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
        return if (zone[0] == '+') utc - offset else utc + offset
    }
}
