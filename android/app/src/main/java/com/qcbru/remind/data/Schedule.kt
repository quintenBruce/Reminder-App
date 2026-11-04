package com.qcbru.remind.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject

/** Kotlin mirror of shared/src/schedule.ts. The server remains authoritative. */
object Schedule {
    fun next(rule: JSONObject, afterMs: Long): Long? {
        val kind = rule.getString("kind")
        if (kind == "once") return Instant.parse(rule.getString("at")).toEpochMilli().takeIf { it > afterMs }
        val start = Instant.parse(rule.getString("startAt")).toEpochMilli()
        val end = rule.optString("endAt").takeIf { !rule.isNull("endAt") && it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() }
            ?: Long.MAX_VALUE
        if (kind == "interval") {
            val step = rule.getLong("everyMinutes") * 60_000L
            require(step >= 5 * 60_000L)
            val count = maxOf(0, Math.floorDiv(afterMs - start, step) + 1)
            return (start + count * step).takeIf { it <= end }
        }
        val zone = ZoneId.of(rule.getString("timeZone"))
        val from = maxOf(start, afterMs + 1)
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        for (day in 0 until 366 * 20) {
            val date = first.plusDays(day.toLong())
            if (!matches(rule, kind, date)) continue
            val local = date.atTime(LocalTime.of(rule.getInt("hour"), rule.getInt("minute")))
            // Java's gap/overlap choice matches Temporal's compatible disambiguation.
            val candidate = ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
            if (candidate > afterMs && candidate >= start && candidate <= end) return candidate
            if (candidate > end) return null
        }
        return null
    }

    fun preview(rule: JSONObject, afterMs: Long, count: Int = 5): List<Long> {
        val dates = mutableListOf<Long>()
        var cursor = afterMs
        repeat(count) {
            val next = next(rule, cursor) ?: return dates
            dates += next
            cursor = next
        }
        return dates
    }

    private fun matches(rule: JSONObject, kind: String, date: LocalDate): Boolean {
        val weekday = date.dayOfWeek.value
        val days = rule.optJSONArray("daysOfWeek")
        val selected = days?.let { array -> (0 until array.length()).any { array.getInt(it) == weekday } } ?: false
        return when (kind) {
            "daily" -> true
            "weekly" -> selected
            "monthly" -> date.dayOfMonth == minOf(rule.getInt("dayOfMonth"), date.lengthOfMonth())
            "monthly_weekday" -> {
                val week = (date.dayOfMonth - 1) / 7 + 1
                selected && if (rule.getInt("weekOfMonth") == -1)
                    date.dayOfMonth + 7 > date.lengthOfMonth() else week == rule.getInt("weekOfMonth")
            }
            "monthly_weekly" -> selected && date.dayOfMonth >= rule.getInt("startDayOfMonth")
            "yearly" -> date.monthValue == rule.getInt("month") &&
                date.dayOfMonth == minOf(rule.getInt("dayOfMonth"), date.lengthOfMonth())
            else -> error("Unknown schedule kind: $kind")
        }
    }
}
