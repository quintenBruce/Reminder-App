package com.qcbru.remind

import android.app.Application
import com.qcbru.remind.data.LocalStore
import com.qcbru.remind.data.PendingAction
import com.qcbru.remind.data.Reminder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalStoreTest {
    private lateinit var store: LocalStore

    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("remind.db")
        store = LocalStore(context)
    }
    @After fun close() { store.close() }

    private fun reminder(id: String) = Reminder(id, id, "", JSONObject("""{"kind":"once","at":"2026-11-03T20:00:00Z"}"""),
        "active", 1L, null, null, null, null, null, 1L, 0L)

    @Test fun mirrorsAccountsSeparately() {
        store.replaceReminders("alice", listOf(reminder("a"), reminder("b")))
        store.replaceReminders("bob", listOf(reminder("b")))
        store.replaceReminders("alice", listOf(reminder("a")))
        assertEquals(listOf("a"), store.reminders("alice").map { it.id })
        assertEquals(listOf("b"), store.reminders("bob").map { it.id })
        store.clearAccount("alice")
        assertTrue(store.reminders("alice").isEmpty())
        assertEquals(1, store.reminders("bob").size)
    }

    @Test fun deduplicatesTheSameAlertButKeepsLaterNudges() {
        assertTrue(store.claimAlert("alice", "r1_100", "r1_1", 100, "local", 120_000))
        assertFalse(store.claimAlert("alice", "r1_100", "r1_1", 101, "push", 120_000))
        assertTrue(store.isLocalAlert("alice", "r1_100"))
        assertEquals(listOf("r1_100"), store.alertIds("alice", "r1_1"))
        assertFalse(store.claimAlert("alice", "r1_200", "r1_1", 60_000, "push", 120_000))
        assertTrue(store.claimAlert("alice", "r1_200", "r1_1", 300_000, "push", 120_000))
        assertFalse(store.isLocalAlert("alice", "r1_200"))
        assertTrue(store.claimAlert("bob", "r1_100", "r1_1", 100, "push", Long.MAX_VALUE))
        assertFalse(store.claimAlert("bob", "r1_300", "r1_1", 600_000, "push", Long.MAX_VALUE))
    }

    @Test fun queuesActionsAndPreservesLocalOverlayUntilSync() {
        val original = reminder("r1")
        store.replaceReminders("alice", listOf(original))
        val action = PendingAction("r1_1_done", "alice", "r1", "r1_1", "done", null, true)
        store.queue(action); store.queue(action)
        assertTrue(store.hasPendingAction("alice", "r1_1"))
        val local = original.copy(title = "Local title")
        store.saveReminder("alice", local)
        store.replaceReminders("alice", listOf(original))
        assertEquals("Local title", store.reminder("alice", "r1")?.title)
        assertEquals(listOf(action), store.pending("alice"))
        store.removeAction(action.requestId)
        assertFalse(store.hasPendingAction("alice", "r1_1"))
        store.replaceReminders("alice", listOf(original))
        assertEquals("r1", store.reminder("alice", "r1")?.title)
    }
}
