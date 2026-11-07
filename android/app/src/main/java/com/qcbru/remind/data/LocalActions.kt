package com.qcbru.remind.data

object LocalActions {
    fun apply(reminder: Reminder, occurrenceId: String, action: String, now: Long,
        snoozeMinutes: Int = 10, snoozeAt: Long? = null): Reminder {
        val scheduledAt = occurrenceId.substringAfterLast('_').toLongOrNull() ?: return reminder
        if (occurrenceId != "${reminder.id}_$scheduledAt") return reminder
        return when (action) {
            "done" -> {
                val next = if (reminder.currentScheduledAt == scheduledAt)
                    Schedule.next(reminder.rule, maxOf(scheduledAt, now)) else reminder.nextDueAt
                reminder.copy(nextDueAt = next, currentScheduledAt = null,
                    lastAcknowledgedScheduledAt = scheduledAt,
                    status = if (next == null) "completed" else reminder.status)
            }
            "snooze" -> {
                val firstRegular = Schedule.next(reminder.rule, scheduledAt)
                val regular = if (firstRegular != null && firstRegular <= now)
                    Schedule.next(reminder.rule, now) else firstRegular
                val snoozedAt = snoozeAt?.coerceAtLeast(now + 1_000L) ?: now + snoozeMinutes * 60_000L
                if (regular != null && regular <= snoozedAt)
                    reminder.copy(nextDueAt = regular, currentScheduledAt = null)
                else reminder.copy(nextDueAt = snoozedAt, currentScheduledAt = scheduledAt)
            }
            else -> reminder
        }
    }
}
