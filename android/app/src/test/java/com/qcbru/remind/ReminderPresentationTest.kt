package com.qcbru.remind

import com.qcbru.remind.data.Proposal
import com.qcbru.remind.data.dateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReminderPresentationTest {
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
