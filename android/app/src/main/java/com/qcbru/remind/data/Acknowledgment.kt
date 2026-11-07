package com.qcbru.remind.data

object Acknowledgment {
    const val DEFAULT_MINUTES = 30

    fun scheduledAt(reminder: Reminder, now: Long): Long {
        val pending = reminder.currentScheduledAt ?: return reminder.nextDueAt ?: now
        val regular = Schedule.next(reminder.rule, pending)
        return if (regular != null && regular <= now) regular else pending
    }

    fun next(reminder: Reminder, scheduledAt: Long, now: Long): Pair<Long, Long?> {
        val regular = Schedule.next(reminder.rule, scheduledAt)?.let {
            if (it <= now) Schedule.next(reminder.rule, now) else it
        }
        val nudge = now + (reminder.repeatUntilDoneMinutes ?: DEFAULT_MINUTES) * 60_000L
        return if (regular != null && regular <= nudge) regular to null
            else nudge to scheduledAt
    }
}
