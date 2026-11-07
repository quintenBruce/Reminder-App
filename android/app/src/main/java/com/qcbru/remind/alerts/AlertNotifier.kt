package com.qcbru.remind.alerts

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qcbru.remind.MainActivity
import com.qcbru.remind.R
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.data.PostedNotification
import com.qcbru.remind.data.Reminder

object AlertNotifier {
    private const val notificationId = 1
    private const val migrationKey = "one_notification_per_reminder"

    private fun tag(uid: String, reminderId: String) = "remind:$uid:$reminderId"
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun store(context: Context) = (context.applicationContext as RemindApplication).store
    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun isStale(reminder: Reminder?, posted: PostedNotification?, reminderId: String,
        occurrenceId: String, dueAt: Long): Boolean {
        val scheduledAt = occurrenceId.removePrefix("${reminderId}_").toLongOrNull() ?: return true
        if (reminder == null && posted != null) return true
        if (reminder != null && reminder.status != "active") return true
        if (reminder?.lastAcknowledgedScheduledAt == scheduledAt) return true
        if (reminder?.lastScheduledAt == null && reminder?.nextDueAt != null && dueAt != reminder.nextDueAt) return true
        if (reminder?.lastScheduledAt != null && scheduledAt < reminder.lastScheduledAt) return true
        val postedAt = posted?.occurrenceId?.removePrefix("${reminderId}_")?.toLongOrNull()
        return posted != null && postedAt != null && (scheduledAt < postedAt ||
            (scheduledAt == postedAt && dueAt <= posted.dueAt))
    }

    @Synchronized fun show(context: Context, uid: String, title: String, body: String, reminderId: String,
        occurrenceId: String, alertId: String, source: String): Boolean {
        if (!allowed(context)) return false
        val store = store(context)
        val test = reminderId == "test"
        val dueAt = alertId.removePrefix("${reminderId}_").toLongOrNull()
        if (!test && (dueAt == null || store.hasPendingAction(uid, occurrenceId) ||
                isStale(store.reminder(uid, reminderId), store.postedNotification(uid, reminderId),
                    reminderId, occurrenceId, dueAt))) return false
        val gap = if (test) 0L else 120_000L
        if (!store.claimAlert(uid, alertId, occurrenceId, System.currentTimeMillis(), source, gap)) return false
        post(context, uid, title, body, reminderId, occurrenceId, alertId, silent = false)
        if (!test) store.savePostedNotification(uid, reminderId, PostedNotification(occurrenceId, dueAt!!, alertId))
        return true
    }

    private fun post(context: Context, uid: String, title: String, body: String, reminderId: String,
        occurrenceId: String, alertId: String, silent: Boolean) {
        val identity = Uri.encode("$uid/$reminderId/$occurrenceId/$alertId")
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("remind://open/$identity")
            putExtra("reminderId", reminderId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body.takeIf { it.isNotBlank() })
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open)
        if (silent) notification.setSilent(true)
        if (reminderId != "test") for (action in listOf("done", "later")) {
            if (action == "later") {
                val later = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    data = Uri.parse("remind://later/$identity")
                    putExtra("uid", uid); putExtra("reminderId", reminderId)
                    putExtra("occurrenceId", occurrenceId)
                }
                notification.addAction(0, "Remind me later", PendingIntent.getActivity(context,
                    0, later, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                continue
            }
            val intent = Intent(context, ActionReceiver::class.java).apply {
                this.action = "com.qcbru.remind.$action"
                data = Uri.parse("remind://action/$identity/$action")
                putExtra("uid", uid); putExtra("reminderId", reminderId)
                putExtra("occurrenceId", occurrenceId); putExtra("alertId", alertId)
                putExtra("actionName", action)
            }
            val pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(0, "Done", pending)
        }
        manager(context).notify(tag(uid, reminderId), notificationId, notification.build())
    }

    fun cancel(context: Context, uid: String, reminderId: String) {
        manager(context).cancel(tag(uid, reminderId), notificationId)
    }

    /** A server snapshot can remove obsolete notifications, but never recreates a swiped one. */
    fun reconcile(context: Context, uid: String, reminders: List<Reminder>) {
        val current = reminders.associateBy { it.id }
        for ((id, posted) in store(context).postedNotifications(uid)) {
            val reminder = current[id]
            val postedAt = posted.occurrenceId.removePrefix("${id}_").toLongOrNull()
            val resetSchedule = reminder != null && reminder.lastScheduledAt == null && reminder.nextDueAt != posted.dueAt
            if (reminder == null || reminder.status != "active" || resetSchedule ||
                (reminder.lastScheduledAt != null && postedAt != null && reminder.lastScheduledAt > postedAt) ||
                (reminder.lastScheduledAt == postedAt && !reminder.currentAlert)) cancel(context, uid, id)
            if (resetSchedule) store(context).removePostedNotification(uid, id)
        }
    }

    /** Clears timestamp-based legacy IDs once, then silently restores cached unanswered alerts. */
    fun migrate(context: Context) {
        val migration = context.getSharedPreferences("notification_migration", Context.MODE_PRIVATE)
        if (migration.getBoolean(migrationKey, false)) return
        manager(context).cancelAll()
        val uid = context.getSharedPreferences("remind", Context.MODE_PRIVATE).getString("uid", null)
        if (uid != null && allowed(context)) {
            val store = store(context)
            for (reminder in store.reminders(uid).filter { it.status == "active" && it.currentAlert }) {
                val occurrenceId = reminder.occurrenceId ?: continue
                val sentAt = reminder.lastSentAt ?: continue
                val alertId = "${reminder.id}_$sentAt"
                post(context, uid, reminder.title, reminder.notes, reminder.id, occurrenceId, alertId, silent = true)
                store.savePostedNotification(uid, reminder.id, PostedNotification(occurrenceId, sentAt, alertId))
            }
        }
        migration.edit().putBoolean(migrationKey, true).apply()
    }
}
