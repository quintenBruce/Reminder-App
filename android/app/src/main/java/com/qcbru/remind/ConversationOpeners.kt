package com.qcbru.remind

import android.content.SharedPreferences

internal class ConversationOpeners(private val preferences: SharedPreferences) {
    fun next(): String {
        val last = preferences.getInt("last_opener", -1)
        val remaining = preferences.getString("remaining_openers", "").orEmpty()
            .split(',').mapNotNull { it.toIntOrNull()?.takeIf(lines.indices::contains) }.distinct()
            .ifEmpty {
                val shuffled = lines.indices.shuffled()
                if (shuffled.first() == last) shuffled.drop(1) + last else shuffled
            }
        val next = remaining.first()
        preferences.edit().putInt("last_opener", next)
            .putString("remaining_openers", remaining.drop(1).joinToString(",")).apply()
        return lines[next]
    }

    companion object {
        internal val lines = listOf(
            "What are we trusting a little too much to memory?",
            "Tell me before you walk through another doorway.",
            "What’s your brain saving under “probably fine”?",
            "What does future you need a tiny ambush about?",
            "Got a task that keeps respawning?",
            "What can I remember while your brain plays the same song on loop?",
            "What’s on the agenda for the very organized person we’re becoming?",
            "What keeps coming back to you at a deeply unhelpful hour?",
            "Give me a task. I’ve been standing here looking employable.",
            "What are we hoping to do before it becomes a whole thing?",
            "What should I put on the fridge of your mind?",
            "Which “I’ll do it in a minute” are we talking about today?",
            "What’s one thing we can stop remembering aggressively?",
            "What should I bring up at a time that’s actually useful?",
            "Is there laundry somewhere living a secret second life?",
            "What’s the task you’d remember instantly if someone else had to do it?",
            "Which small errand has somehow become a recurring character?",
            "What’s competing for space with your lunch plans?",
            "Tell me the thing. I’ll be politely annoying about it later.",
            "What can I help you remember before you remember it in the shower?",
            "What should I know before your brain opens another tab?",
            "Have a deadline? A dentist? A plant with concerns?",
            "What’s getting promoted from vague intention to actual plan?",
            "What’s on your to-do list wearing an invisibility cloak?",
            "What should I remind you about with absolutely no judgment and excellent timing?",
            "What have you already said “oh, right” about twice?",
            "What can we do now that’ll make tomorrow you say, “Nice”?",
            "What needs a nudge before it needs an apology?",
            "What would you text yourself if yourself ever texted back?",
            "What’s the plan? Snacks count.",
            "What can I hold onto while you go look for your keys?",
            "Which very small task would like its own dramatic entrance?",
            "What should I remind you about before the leftovers become heirlooms?",
            "What’s been on the list long enough to start paying rent?",
            "What can we do before “later” becomes a calendar month?",
            "What would future you put in the subject line of an urgent email?",
            "Which errand deserves a tiny drumroll?",
            "What should I whisper loudly at the appropriate time?",
            "What appointment is currently hiding behind a screenshot?",
            "What needs doing while we still remember why?",
            "Which household object is quietly depending on you today?",
            "What belongs on the list between “buy milk” and “become unstoppable”?",
            "What would you remember the second you sat down without me?",
            "Which plan is waiting for someone to give it a time?",
            "What can I get out of your head before it starts rearranging the furniture?",
            "What should I remind you about right before you get comfortable?",
            "What’s the thing you definitely put somewhere memorable?",
            "What can we cross off so you can make a fresh, slightly shorter list?",
            "What’s the task with the suspiciously flexible deadline?",
            "What should future you thank us for? A nod is fine.",
        )
    }
}
