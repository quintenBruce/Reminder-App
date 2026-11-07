package com.qcbru.remind.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qcbru.remind.data.RemindRepository
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.data.Schedule
import com.qcbru.remind.data.Acknowledgment
import com.qcbru.remind.widget.ReminderWidgetProvider

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getStringExtra("uid") ?: return
        val id = intent.getStringExtra("reminderId") ?: return
        val due = intent.getLongExtra("dueAt", -1)
        if (due < 0 || context.getSharedPreferences("remind", Context.MODE_PRIVATE).getString("uid", null) != uid) return
        val store = (context.applicationContext as RemindApplication).store
        val reminder = store.reminder(uid, id) ?: return
        if (!reminder.active || reminder.nextDueAt != due) return
        val scheduledAt = Acknowledgment.scheduledAt(reminder, System.currentTimeMillis())
        val occurrenceId = "${id}_$scheduledAt"
        val alertId = "${id}_$due"
        val now = System.currentTimeMillis()
        val shown = AlertNotifier.show(context, uid, reminder.title, reminder.notes, id,
            occurrenceId, alertId, "local")
        if (!shown && !store.hasAlert(uid, alertId)) return
        val (next, pendingOccurrence) = Acknowledgment.next(reminder, scheduledAt, now)
        val advanced = reminder.copy(nextDueAt = next, lastScheduledAt = scheduledAt, lastSentAt = now,
            currentScheduledAt = pendingOccurrence)
        if (store.reminder(uid, id)?.nextDueAt == due) {
            store.saveReminder(uid, advanced)
            ReminderWidgetProvider.update(context)
            context.sendBroadcast(Intent(RemindRepository.LOCAL_CHANGE_ACTION).setPackage(context.packageName))
            AlertScheduler(context).schedule(uid, advanced)
        }
    }
}
