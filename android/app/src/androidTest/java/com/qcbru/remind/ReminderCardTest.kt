package com.qcbru.remind

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qcbru.remind.data.Reminder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReminderCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsActionsAndOpensHistory() {
        var lastAction = ""
        val reminder = Reminder("r1", "Take vitamins", "With breakfast",
            JSONObject("""{"kind":"once","at":"2026-11-03T20:00:00Z"}"""),
            "active", null, null, 1_000L, 1_001L, null, null, 1L, 1_001L)
        compose.setContent { MaterialTheme { ReminderCard(reminder,
            onEdit = { lastAction = "edit" }, onHistory = { lastAction = "history" },
            onDelete = { lastAction = "delete" }, onAction = { lastAction = it }) } }
        compose.onNodeWithText("Take vitamins").assertExists()
        compose.onNodeWithText("Done").performClick()
        assertEquals("done", lastAction)
        compose.onNodeWithText("Snooze 10 min").performClick()
        assertEquals("snooze", lastAction)
        compose.onNodeWithText("Conversations").performClick()
        assertEquals("history", lastAction)
    }
}
