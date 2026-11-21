package com.qcbru.remind

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConversationOpenersTest {
    @Test fun drawsAllFiftyBeforeRepeatingAcrossInstances() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("opener_test", 0)
        val firstCycle = List(50) { ConversationOpeners(prefs).next() }
        assertEquals(50, ConversationOpeners.lines.size)
        assertEquals(50, firstCycle.toSet().size)
        assertEquals(ConversationOpeners.lines.toSet(), firstCycle.toSet())
        val secondCycle = List(50) { ConversationOpeners(prefs).next() }
        assertNotEquals(firstCycle.last(), secondCycle.first())
        assertEquals(firstCycle.toSet(), secondCycle.toSet())
    }
}
