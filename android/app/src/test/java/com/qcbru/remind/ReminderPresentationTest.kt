package com.qcbru.remind

import com.qcbru.remind.data.Proposal
import com.qcbru.remind.data.calendarSnoozeOptions
import com.qcbru.remind.data.dateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class ReminderPresentationTest {
    @Test fun snoozeOptionsExpireAtTheirLocalTimes() {
        fun labels(time: String) = calendarSnoozeOptions(ZonedDateTime.parse(time)).map { it.label }

        assertEquals(listOf("This afternoon (1:00 PM)", "This evening (6:00 PM)", "Tomorrow (9:00 AM)"),
            labels("2026-11-22T12:59:59-06:00[America/Chicago]"))
        assertEquals(listOf("This evening (6:00 PM)", "Tomorrow (9:00 AM)"),
            labels("2026-11-22T13:00:00-06:00[America/Chicago]"))
        assertEquals(listOf("Tomorrow (9:00 AM)"),
            labels("2026-11-22T18:00:00-06:00[America/Chicago]"))
    }

    @Test fun snoozeOptionsUseWallClockTimesAcrossDaylightSaving() {
        val beforeChange = ZonedDateTime.parse("2026-03-07T18:00:00-06:00[America/Chicago]")
        assertEquals(Instant.parse("2026-03-08T14:00:00Z").toEpochMilli(),
            calendarSnoozeOptions(beforeChange).single().at)

        val afterMidnight = ZonedDateTime.parse("2026-03-08T00:05:00-06:00[America/Chicago]")
        assertEquals(Instant.parse("2026-03-08T18:00:00Z").toEpochMilli(),
            calendarSnoozeOptions(afterMidnight).first().at)
    }

    @Test fun timestampsUseCentralDaylightAndStandardTime() {
        assertEquals("Jul 2, 2026 · 1:00 PM CDT", dateLabel(Instant.parse("2026-07-02T18:00:00Z").toEpochMilli()))
        assertEquals("Jan 1, 2026 · 7:30 PM CST", dateLabel(Instant.parse("2026-01-02T01:30:00Z").toEpochMilli()))
    }

    @Test fun proposalReadsStructuredReviewAndStillAcceptsOlderResponses() {
        val base = mapOf("id" to "p", "action" to "create", "readback" to "Legacy reply", "title" to "Team check-in")
        val legacy = Proposal.fromMap(base + ("rule" to mapOf("kind" to "daily")))
        assertEquals("Daily", legacy.review?.recurrence)
        assertNull(legacy.review?.actionDetail)
        val proposal = Proposal.fromMap(base + ("review" to mapOf(
            "title" to "Team check-in", "recipients" to listOf("Sam", "Lee"), "endsAt" to 42,
        )))
        assertEquals(listOf("Sam", "Lee"), proposal.review?.recipients)
        assertEquals(42L, proposal.review?.endsAt)
    }
}
