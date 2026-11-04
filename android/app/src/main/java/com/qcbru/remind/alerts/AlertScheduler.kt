package com.qcbru.remind.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.data.Reminder

class AlertScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val store = (context.applicationContext as RemindApplication).store

    fun reschedule(uid: String) {
        for (reminder in store.reminders(uid)) schedule(uid, reminder)
    }

    fun schedule(uid: String, reminder: Reminder) {
        val due = reminder.nextDueAt ?: return
        if (!reminder.active) return
        val intent = alarm(uid, reminder.id, due)
        val fireAt = maxOf(System.currentTimeMillis() + 1_000L, due + 90_000L)
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms())
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
        else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
    }

    fun cancelAll(uid: String) {
        for (reminder in store.reminders(uid)) reminder.nextDueAt?.let { alarms.cancel(alarm(uid, reminder.id, it)) }
    }

    private fun alarm(uid: String, reminderId: String, due: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.qcbru.remind.ALARM"
            data = android.net.Uri.parse("remind://alarm/$uid/$reminderId/$due")
            putExtra("uid", uid); putExtra("reminderId", reminderId); putExtra("dueAt", due)
        }
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
