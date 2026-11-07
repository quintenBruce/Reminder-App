package com.qcbru.remind.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.qcbru.remind.MainActivity
import com.qcbru.remind.R
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.alerts.ActionReceiver
import java.text.DateFormat
import java.util.Date

class ReminderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context)
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReminderWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val uid = context.getSharedPreferences("remind", Context.MODE_PRIVATE).getString("uid", null)
            val store = (context.applicationContext as RemindApplication).store
            val reminders = uid?.let { store.reminders(it) }.orEmpty().filter { it.active || it.currentAlert }
                .sortedWith(compareByDescending<com.qcbru.remind.data.Reminder> { it.currentAlert }
                    .thenBy { it.nextDueAt ?: Long.MAX_VALUE }).take(3)
            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_reminders)
                views.setTextViewText(R.id.widget_status, when {
                    uid == null -> "Sign in to see reminders"
                    reminders.isEmpty() -> "No upcoming reminders"
                    else -> "Upcoming and needs attention"
                })
                views.setOnClickPendingIntent(R.id.widget_root, activity(context, "open", uid, null, null))
                val rows = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3)
                val titles = intArrayOf(R.id.widget_title_1, R.id.widget_title_2, R.id.widget_title_3)
                val details = intArrayOf(R.id.widget_detail_1, R.id.widget_detail_2, R.id.widget_detail_3)
                val dones = intArrayOf(R.id.widget_done_1, R.id.widget_done_2, R.id.widget_done_3)
                val laters = intArrayOf(R.id.widget_later_1, R.id.widget_later_2, R.id.widget_later_3)
                rows.forEachIndexed { index, row ->
                    val reminder = reminders.getOrNull(index)
                    views.setViewVisibility(row, if (reminder == null) View.GONE else View.VISIBLE)
                    if (reminder == null || uid == null) return@forEachIndexed
                    views.setTextViewText(titles[index], reminder.title)
                    val due = reminder.nextDueAt
                    val whenText = due?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                        ?: "Awaiting Done"
                    views.setTextViewText(details[index], if (reminder.currentAlert) "Needs attention · $whenText" else whenText)
                    views.setOnClickPendingIntent(titles[index], activity(context, "open", uid, reminder.id, null))
                    val occurrence = reminder.occurrenceId
                    views.setViewVisibility(dones[index], if (reminder.currentAlert && occurrence != null) View.VISIBLE else View.GONE)
                    views.setViewVisibility(laters[index], if (reminder.currentAlert && occurrence != null) View.VISIBLE else View.GONE)
                    if (occurrence != null && reminder.currentAlert) {
                        val done = Intent(context, ActionReceiver::class.java).apply {
                            data = Uri.parse("remind://widget/done/$occurrence")
                            putExtra("uid", uid); putExtra("reminderId", reminder.id)
                            putExtra("occurrenceId", occurrence)
                            putExtra("alertId", "${reminder.id}_${reminder.lastScheduledAt}")
                            putExtra("actionName", "done")
                        }
                        views.setOnClickPendingIntent(dones[index], PendingIntent.getBroadcast(context,
                            occurrence.hashCode(), done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        views.setOnClickPendingIntent(laters[index], activity(context, "later", uid, reminder.id, occurrence))
                    }
                }
                manager.updateAppWidget(widgetId, views)
            }
        }

        private fun activity(context: Context, mode: String, uid: String?, reminderId: String?,
            occurrence: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.parse("remind://$mode/widget/${reminderId ?: "all"}/${occurrence ?: "none"}")
                putExtra("uid", uid); putExtra("reminderId", reminderId)
                putExtra("occurrenceId", occurrence)
            }
            return PendingIntent.getActivity(context, (reminderId ?: "all").hashCode() + mode.hashCode(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
