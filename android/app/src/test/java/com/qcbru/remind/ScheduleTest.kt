package com.qcbru.remind

import com.qcbru.remind.data.LocalActions
import com.qcbru.remind.data.Reminder
import com.qcbru.remind.data.Schedule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ScheduleTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private fun rule(json: String) = JSONObject(json)
    private fun dates(json: String, after: String, count: Int = 3) =
        Schedule.preview(rule(json), ms(after), count).map { Instant.ofEpochMilli(it).toString() }

    @Test fun onceAndInterval() {
        assertEquals(ms("2026-10-11T19:00:00Z"), Schedule.next(rule("""{"kind":"once","at":"2026-10-11T14:00:00-05:00"}"""), ms("2026-10-11T18:59:00Z")))
        assertNull(Schedule.next(rule("""{"kind":"once","at":"2026-10-11T14:00:00-05:00"}"""), ms("2026-10-11T19:00:00Z")))
        assertEquals(listOf("2026-10-11T14:00:00Z", "2026-10-11T14:05:00Z", "2026-10-11T14:10:00Z"),
            dates("""{"kind":"interval","startAt":"2026-10-11T09:00:00-05:00","everyMinutes":5}""",
                "2026-10-11T13:59:00Z"))
    }

    @Test fun daylightSavingTime() {
        assertEquals(listOf("2026-10-31T14:00:00Z", "2026-11-01T15:00:00Z", "2026-11-02T15:00:00Z"),
            dates("""{"kind":"daily","startAt":"2026-10-31T09:00:00-05:00","timeZone":"America/Chicago","hour":9,"minute":0}""",
                "2026-10-31T13:00:00Z"))
        assertEquals("2026-03-08T08:30:00Z", dates("""{"kind":"daily","startAt":"2026-03-07T02:30:00-06:00","timeZone":"America/Chicago","hour":2,"minute":30}""",
            "2026-03-08T06:00:00Z", 1).single())
        assertEquals("2026-11-01T06:30:00Z", dates("""{"kind":"daily","startAt":"2026-10-31T01:30:00-05:00","timeZone":"America/Chicago","hour":1,"minute":30}""",
            "2026-11-01T05:00:00Z", 1).single())
    }

    @Test fun monthlyYearlyAndWeekdays() {
        assertEquals(listOf("2026-01-31T15:00:00Z", "2026-02-28T15:00:00Z", "2026-03-31T14:00:00Z"),
            dates("""{"kind":"monthly","startAt":"2026-01-01T00:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"dayOfMonth":31,"endAt":"2026-03-31T23:59:00-05:00"}""",
                "2026-01-01T05:59:00Z"))
        assertEquals("2026-01-18T18:00:00Z", dates("""{"kind":"monthly_weekday","startAt":"2026-01-01T00:00:00-06:00","timeZone":"America/Chicago","hour":12,"minute":0,"daysOfWeek":[7],"weekOfMonth":3}""",
            "2026-01-01T05:59:00Z", 1).single())
        assertEquals("2026-01-16T18:00:00Z", dates("""{"kind":"monthly_weekly","startAt":"2026-01-01T00:00:00-06:00","timeZone":"America/Chicago","hour":12,"minute":0,"daysOfWeek":[5],"startDayOfMonth":10}""",
            "2026-01-01T05:59:00Z", 1).single())
        assertEquals("2026-02-27T15:00:00Z", dates("""{"kind":"weekly","startAt":"2026-02-26T09:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"daysOfWeek":[5]}""",
            "2026-02-26T15:00:00Z", 1).single())
        assertEquals("2028-02-29T15:00:00Z", dates("""{"kind":"yearly","startAt":"2028-01-01T00:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"month":2,"dayOfMonth":29}""",
            "2028-01-01T05:59:00Z", 1).single())
    }

    @Test fun lastAndFifthWeekdaysAndEndDate() {
        assertEquals("2026-02-23T15:00:00Z", dates("""{"kind":"monthly_weekday","startAt":"2026-02-01T00:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"daysOfWeek":[1],"weekOfMonth":-1}""",
            "2026-02-01T05:59:00Z", 1).single())
        assertEquals("2026-03-30T14:00:00Z", dates("""{"kind":"monthly_weekday","startAt":"2026-02-01T00:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"daysOfWeek":[1],"weekOfMonth":5}""",
            "2026-02-01T05:59:00Z", 1).single())
        assertEquals(listOf("2026-01-01T15:00:00Z"), dates("""{"kind":"daily","startAt":"2026-01-01T09:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0,"endAt":"2026-01-01T23:59:00-06:00"}""",
            "2026-01-01T14:59:00Z"))
    }

    @Test fun zoneStaysPinnedWhileDeviceZoneChanges() {
        val json = """{"kind":"daily","startAt":"2026-11-01T09:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0}"""
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("2026-11-02T15:00:00Z", dates(json, "2026-11-01T16:00:00Z", 1).single())
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    @Test fun localDoneAndSnoozePreserveOccurrence() {
        val due = ms("2026-10-11T19:00:00Z")
        val reminder = Reminder("r1", "Vitamins", "", rule("""{"kind":"once","at":"2026-10-11T14:00:00-05:00"}"""),
            "active", null, null, due, due, null, null, 1, due)
        val done = LocalActions.apply(reminder, "r1_$due", "done", due + 1_000)
        assertEquals("completed", done.status)
        assertNull(done.nextDueAt)
        assertEquals(due, done.lastAcknowledgedScheduledAt)
        val snoozed = LocalActions.apply(reminder, "r1_$due", "snooze", due)
        assertEquals(due + 600_000, snoozed.nextDueAt)
        assertEquals(due, snoozed.currentScheduledAt)
        assertEquals(reminder, LocalActions.apply(reminder, "wrong_$due", "done", due))
    }

    @Test fun doneStopsNudgesWithoutSkippingTheNextRegularOccurrence() {
        val due = ms("2026-11-02T15:00:00Z")
        val rule = rule("""{"kind":"daily","startAt":"2026-11-01T09:00:00-06:00","timeZone":"America/Chicago","hour":9,"minute":0}""")
        val reminder = Reminder("r2", "Vitamins", "", rule, "active", due + 300_000,
            due, due, due, null, 5, 1, due)
        val done = LocalActions.apply(reminder, "r2_$due", "done", due + 1000)
        assertNull(done.currentScheduledAt)
        assertEquals(ms("2026-11-03T15:00:00Z"), done.nextDueAt)
        assertEquals(due, done.lastAcknowledgedScheduledAt)
    }

    @Test fun invalidOccurrenceDoesNotChangeLocalState() {
        val due = ms("2026-11-02T15:00:00Z")
        val reminder = Reminder("r3", "Vitamins", "", rule("""{"kind":"once","at":"2026-11-02T15:00:00Z"}"""),
            "active", due, null, null, null, null, null, 1, due)
        assertEquals(reminder, LocalActions.apply(reminder, "r3_invalid", "snooze", due))
        assertEquals(reminder, LocalActions.apply(reminder, "other_$due", "done", due))
    }
}
