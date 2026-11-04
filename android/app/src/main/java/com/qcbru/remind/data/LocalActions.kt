package com.qcbru.remind.data

object LocalActions {
    fun apply(reminder: Reminder, occurrenceId: String, action: String, now: Long): Reminder {
        val scheduledAt = occurrenceId.substringAfterLast('_').toLongOrNull() ?: return reminder
        if (occurrenceId != "${reminder.id}_$scheduledAt") return reminder
        return when (action) {
            "done" -> {
                val next = if (reminder.currentScheduledAt == scheduledAt)
                    Schedule.next(reminder.rule, scheduledAt) else reminder.nextDueAt
                reminder.copy(nextDueAt = next, currentScheduledAt = null,
                    lastAcknowledgedScheduledAt = scheduledAt,
                    status = if (next == null) "completed" else reminder.status)
            }
            "snooze" -> reminder.copy(nextDueAt = now + 10 * 60_000L,
                currentScheduledAt = scheduledAt)
            else -> reminder
        }
    }
}
