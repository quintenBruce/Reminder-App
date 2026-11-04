package com.qcbru.remind.alerts

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.data.LocalActions
import com.qcbru.remind.data.RemindRepository

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getStringExtra("uid") ?: return
        val id = intent.getStringExtra("reminderId") ?: return
        val occurrenceId = intent.getStringExtra("occurrenceId") ?: return
        val alertId = intent.getStringExtra("alertId") ?: return
        val action = intent.getStringExtra("actionName")?.takeIf { it == "done" || it == "snooze" } ?: return
        val repo = RemindRepository(context)
        if (repo.cachedUid() != uid) return
        val store = (context.applicationContext as RemindApplication).store
        val reminder = store.reminder(uid, id) ?: return
        val updated = LocalActions.apply(reminder, occurrenceId, action, System.currentTimeMillis())
        store.saveReminder(uid, updated)
        AlertScheduler(context).schedule(uid, updated)
        repo.queueAction(uid, id, occurrenceId, action, store.isLocalAlert(uid, alertId))
        context.getSystemService(NotificationManager::class.java).cancel(alertId.hashCode())
    }
}
