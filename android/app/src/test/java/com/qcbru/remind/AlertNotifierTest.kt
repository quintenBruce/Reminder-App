package com.qcbru.remind

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.qcbru.remind.alerts.AlertNotifier
import com.qcbru.remind.data.PostedNotification
import com.qcbru.remind.data.Reminder
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RemindApplication::class)
class AlertNotifierTest {
    private fun reminder(last: Long? = 100L, acknowledged: Long? = null, next: Long? = 200L) =
        Reminder("r", "Take medicine", "", JSONObject("""{"kind":"once","at":"2026-11-07T20:00:00Z"}"""),
            "active", next, last, last, last, acknowledged, 30, 1, 0)

    @Test fun aFollowUpReplacesOnlyItsOwnReminderAndOldDeliveriesCannotReturn() {
        val first = PostedNotification("r_100", 100, "r_100")
        assertFalse(AlertNotifier.isStale(reminder(), first, "r", "r_100", 130))
        assertTrue(AlertNotifier.isStale(reminder(), first, "r", "r_100", 100))
        val followUp = PostedNotification("r_100", 130, "r_130")
        assertTrue(AlertNotifier.isStale(reminder(), followUp, "r", "r_100", 120))
        assertFalse(AlertNotifier.isStale(reminder(last = 100, next = 200), followUp, "r", "r_200", 200))
        val nextOccurrence = PostedNotification("r_200", 200, "r_200")
        assertTrue(AlertNotifier.isStale(reminder(last = 200), nextOccurrence, "r", "r_100", 160))
    }

    @Test fun acknowledgedAndRescheduledAlertsStayGone() {
        assertTrue(AlertNotifier.isStale(reminder(acknowledged = 100), null, "r", "r_100", 130))
        assertTrue(AlertNotifier.isStale(reminder(last = null, next = 300), null, "r", "r_100", 100))
        assertFalse(AlertNotifier.isStale(reminder(last = null, next = 300), null, "r", "r_300", 300))
    }

    @Test fun followUpAndNextOccurrenceReplaceTheNotificationWithoutReopeningAReportedSwipe() {
        val context = RuntimeEnvironment.getApplication() as RemindApplication
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val uid = "notification-test-account"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        context.store.saveReminder(uid, reminder(last = null, next = 100))
        assertTrue(AlertNotifier.show(context, uid, "First", "", "r", "r_100", "r_100", "push"))
        assertEquals(1, manager.activeNotifications.size)
        val firstActions = manager.activeNotifications.single().notification.actions.map { it.actionIntent }
        context.store.saveReminder(uid, reminder(last = 100, next = 200))
        assertTrue(AlertNotifier.show(context, uid, "Next", "", "r", "r_200", "r_200", "push"))
        assertEquals(1, manager.activeNotifications.size)
        val nextActions = manager.activeNotifications.single().notification.actions.map { it.actionIntent }
        assertEquals(2, nextActions.size)
        assertNotEquals(firstActions, nextActions)
        assertEquals("Next", manager.activeNotifications.single().notification.extras
            .getString(NotificationCompat.EXTRA_TITLE))
        manager.cancel("remind:$uid:r", 1)
        AlertNotifier.reconcile(context, uid, listOf(reminder(last = 200, next = 300)))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun deletedReminderIsCanceledWithoutTouchingAnotherAccount() {
        val context = RuntimeEnvironment.getApplication() as RemindApplication
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        for (uid in listOf("first-account", "second-account")) {
            context.store.saveReminder(uid, reminder(last = null, next = 100))
            assertTrue(AlertNotifier.show(context, uid, uid, "", "r", "r_100", "r_100", "push"))
        }
        assertEquals(2, manager.activeNotifications.size)
        context.store.replaceReminders("first-account", emptyList())
        AlertNotifier.reconcile(context, "first-account", emptyList())
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(AlertNotifier.isStale(null, context.store.postedNotification("first-account", "r"),
            "r", "r_100", 130))
    }

    @Test fun upgradeCollapsesLegacyNotificationsToOneSilentReminder() {
        val context = RuntimeEnvironment.getApplication() as RemindApplication
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val uid = "upgrade-account"
        context.store.saveReminder(uid, reminder(last = 100, next = 200))
        context.getSharedPreferences("remind", Context.MODE_PRIVATE).edit().putString("uid", uid).commit()
        context.getSharedPreferences("notification_migration", Context.MODE_PRIVATE).edit()
            .putBoolean("one_notification_per_reminder", false).commit()
        val old = NotificationCompat.Builder(context, "reminders").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Old alert").build()
        manager.notify(42, old)
        manager.notify(43, old)
        AlertNotifier.migrate(context)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("remind:$uid:r", manager.activeNotifications.single().tag)
    }
}
