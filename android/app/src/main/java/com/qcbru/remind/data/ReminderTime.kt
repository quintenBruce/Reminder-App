package com.qcbru.remind.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val central = ZoneId.of("America/Chicago")
private val centralTimestamp = DateTimeFormatter.ofPattern("MMM d, uuuu · h:mm a z", Locale.US).withZone(central)
private val snoozeClock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

internal fun dateLabel(value: Long?): String =
    value?.let { centralTimestamp.format(Instant.ofEpochMilli(it)) } ?: "—"

internal data class CalendarSnoozeOption(val label: String, val at: Long)

internal fun calendarSnoozeOptions(now: ZonedDateTime): List<CalendarSnoozeOption> {
    val today = now.toLocalDate()
    return listOf(
        "This afternoon" to today.atTime(13, 0),
        "This evening" to today.atTime(18, 0),
        "Tomorrow" to today.plusDays(1).atTime(9, 0),
    ).mapNotNull { (label, time) ->
        val target = time.atZone(now.zone)
        if (target.isAfter(now)) {
            CalendarSnoozeOption("$label (${snoozeClock.format(target)})", target.toInstant().toEpochMilli())
        } else null
    }
}
