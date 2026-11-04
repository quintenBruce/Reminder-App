package com.qcbru.remind.alerts

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qcbru.remind.MainActivity
import com.qcbru.remind.RemindApplication

object AlertNotifier {
    fun show(context: Context, uid: String, title: String, body: String, reminderId: String,
        occurrenceId: String, alertId: String, source: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val store = (context.applicationContext as RemindApplication).store
        val reminder = store.reminder(uid, reminderId)
        val scheduledAt = occurrenceId.removePrefix("${reminderId}_").toLongOrNull()
        if (reminderId != "test" && (store.hasPendingAction(uid, occurrenceId) ||
                (scheduledAt != null && reminder?.lastAcknowledgedScheduledAt == scheduledAt))) return false
        val gap = if (reminderId == "test") 0L else if (reminder?.repeatUntilDoneMinutes != null ||
            (scheduledAt != null && reminder?.currentScheduledAt == scheduledAt))
            120_000L else Long.MAX_VALUE
        if (!store.claimAlert(uid, alertId, occurrenceId, System.currentTimeMillis(), source, gap)) return false
        val open = PendingIntent.getActivity(context, alertId.hashCode(), Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminderId", reminderId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body.takeIf { it.isNotBlank() })
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open)
        if (reminderId != "test") for (action in listOf("done", "snooze")) {
            val intent = Intent(context, ActionReceiver::class.java).apply {
                this.action = "com.qcbru.remind.$action"
                data = android.net.Uri.parse("remind://action/$alertId/$action")
                putExtra("uid", uid); putExtra("reminderId", reminderId)
                putExtra("occurrenceId", occurrenceId); putExtra("alertId", alertId)
                putExtra("actionName", action)
            }
            val pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(0, if (action == "done") "Done" else "Snooze 10 min", pending)
        }
        context.getSystemService(NotificationManager::class.java).notify(alertId.hashCode(), notification.build())
        return true
    }
}
