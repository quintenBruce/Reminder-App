package com.qcbru.remind

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.qcbru.remind.alerts.AlertScheduler
import com.qcbru.remind.data.ActionSync
import com.qcbru.remind.data.Proposal
import com.qcbru.remind.data.PushSync
import com.qcbru.remind.data.RemindRepository
import com.qcbru.remind.data.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

data class RemindUiState(
    val uid: String? = null,
    val email: String = "",
    val tab: String = "Ask",
    val reminders: List<Reminder> = emptyList(),
    val events: List<Map<String, Any?>> = emptyList(),
    val turns: List<Pair<String, String>> = emptyList(),
    val proposal: Proposal? = null,
    val conversationId: String = "",
    val busy: Boolean = false,
    val notice: String = "",
    val speak: String? = null,
    val theme: String = "system",
    val spokenReplies: Boolean = true,
    val detailReminderId: String? = null,
    val sessions: List<Pair<String, Long>> = emptyList(),
    val selectedSession: String? = null,
    val savedTurns: List<Pair<String, String>> = emptyList(),
)

class RemindViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = RemindRepository(application)
    private val prefs = application.getSharedPreferences("remind", 0)
    private val store = (application as RemindApplication).store
    private val mutable = MutableStateFlow(RenindInitial())
    val state = mutable.asStateFlow()
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        val verified = user?.isEmailVerified == true && user.providerData.any { it.providerId == "google.com" }
        if (verified) openAccount(user.uid, user.email ?: "")
        else {
            repo.stopObserving()
            mutable.update { it.copy(uid = null, email = "", reminders = emptyList(), proposal = null) }
        }
    }

    init { repo.auth.addAuthStateListener(authListener) }

    private fun RenindInitial() = RemindUiState(
        theme = prefs.getString("theme", "system") ?: "system",
        spokenReplies = prefs.getBoolean("spokenReplies", true),
    )

    private fun openAccount(uid: String, email: String) {
        if (mutable.value.uid == uid) return
        repo.setActiveUid(uid)
        val previous = prefs.getString("conversation:$uid", null)
        val fresh = repo.newConversationId()
        prefs.edit().putString("conversation:$uid", fresh).apply()
        mutable.update { it.copy(uid = uid, email = email, conversationId = fresh, turns = emptyList(),
            proposal = null, reminders = store.reminders(uid), notice = prefs.getString("sync_notice", "") ?: "") }
        prefs.edit().remove("sync_notice").apply()
        repo.observe(uid, { reminders -> mutable.update { it.copy(reminders = reminders) } },
            { events -> mutable.update { it.copy(events = events) } }, { error -> notice(error) })
        viewModelScope.launch {
            runCatching { repo.initialize() }.onFailure { notice(it.message ?: "Could not initialize history") }
            if (previous != null) runCatching { repo.discardDraft(previous) }
            if (notificationsAllowed()) PushSync.enqueue(getApplication())
            ActionSync.enqueue(getApplication())
            AlertScheduler(getApplication()).reschedule(uid)
        }
    }

    fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun refreshLocal() { mutable.value.uid?.let { uid -> mutable.update { it.copy(reminders = store.reminders(uid)) } } }
    fun onResume() { refreshLocal(); if (mutable.value.uid != null && notificationsAllowed()) PushSync.enqueue(getApplication()) }

    fun setTab(tab: String) { mutable.update { it.copy(tab = tab) } }
    fun setTheme(theme: String) {
        prefs.edit().putString("theme", theme).apply()
        mutable.update { it.copy(theme = theme) }
    }
    fun setSpokenReplies(enabled: Boolean) {
        prefs.edit().putBoolean("spokenReplies", enabled).apply()
        mutable.update { it.copy(spokenReplies = enabled) }
    }
    fun notice(text: String) { mutable.update { it.copy(notice = text) } }
    fun clearNotice() { mutable.update { it.copy(notice = "") } }
    fun clearSpeak() { mutable.update { it.copy(speak = null) } }

    fun send(text: String, spoken: Boolean = false) {
        val uid = mutable.value.uid ?: return
        if (text.isBlank() || mutable.value.busy) return
        if (mutable.value.proposal != null && text.trim().matches(Regex("(?i)yes|confirm|do it|okay|ok"))) {
            confirm(); return
        }
        val conversationId = mutable.value.conversationId
        val pendingId = mutable.value.proposal?.id
        mutable.update { it.copy(busy = true, notice = "") }
        viewModelScope.launch {
            try {
                val result = repo.propose(text.trim(), conversationId, TimeZone.getDefault().id, pendingId)
                if (mutable.value.uid != uid) return@launch
                val reply = result["reply"] as? String ?: ""
                val proposal = (result["proposal"] as? Map<*, *>)?.let { Proposal.fromMap(it) }
                mutable.update { it.copy(turns = it.turns + (text.trim() to reply), proposal = proposal,
                    speak = if (spoken && it.spokenReplies) reply else null) }
            } catch (error: Exception) { notice(error.message ?: "Request failed") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun transcribe(audioBase64: String) {
        mutable.update { it.copy(busy = true, notice = "Transcribing…") }
        viewModelScope.launch {
            try {
                val transcript = repo.call("transcribeAudio", mapOf("audioBase64" to audioBase64,
                    "mimeType" to "audio/mp4"))["transcript"] as? String ?: error("No transcript")
                mutable.update { it.copy(busy = false) }
                send(transcript, true)
            } catch (error: Exception) { notice(error.message ?: "Transcription failed")
                mutable.update { it.copy(busy = false) } }
        }
    }

    fun confirm() {
        val proposal = mutable.value.proposal ?: return
        mutable.update { it.copy(busy = true, notice = "") }
        viewModelScope.launch {
            try {
                repo.confirm(proposal)
                freshConversation()
                notice(if (proposal.action == "delete") "Reminder deleted" else "Reminder saved")
            } catch (error: Exception) { notice(error.message ?: "Could not save") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun cancelProposal() {
        val old = mutable.value.conversationId
        freshConversation()
        viewModelScope.launch { runCatching { repo.discardDraft(old) } }
    }

    private fun freshConversation() {
        val uid = mutable.value.uid ?: return
        val fresh = repo.newConversationId()
        prefs.edit().putString("conversation:$uid", fresh).apply()
        mutable.update { it.copy(conversationId = fresh, turns = emptyList(), proposal = null, speak = null) }
    }

    fun act(reminder: Reminder, action: String) {
        val uid = mutable.value.uid ?: return
        val occurrence = reminder.occurrenceId ?: return
        repo.queueAction(uid, reminder.id, occurrence, action, true)
        mutable.update { it.copy(reminders = store.reminders(uid)) }
        notice(if (action == "done") "Marked done" else "Snoozed 10 minutes")
    }

    fun delete(reminder: Reminder) {
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { repo.deleteReminder(reminder); notice("Reminder deleted") }
            catch (error: Exception) { notice(error.message ?: "Could not delete") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun openHistory(reminderId: String) {
        val uid = mutable.value.uid ?: return
        mutable.update { it.copy(detailReminderId = reminderId, sessions = emptyList(), savedTurns = emptyList()) }
        viewModelScope.launch {
            runCatching { repo.conversations(uid, reminderId) }.onSuccess { sessions ->
                mutable.update { it.copy(sessions = sessions) }
                sessions.firstOrNull()?.let { selectSession(it.first) }
            }.onFailure { notice(it.message ?: "Could not load conversations") }
        }
    }

    fun selectSession(id: String) {
        val uid = mutable.value.uid ?: return
        mutable.update { it.copy(selectedSession = id, savedTurns = emptyList()) }
        viewModelScope.launch { runCatching { repo.turns(uid, id) }
            .onSuccess { turns -> mutable.update { it.copy(savedTurns = turns) } }
            .onFailure { notice(it.message ?: "Could not load conversation") } }
    }

    fun closeHistory() { mutable.update { it.copy(detailReminderId = null, selectedSession = null) } }

    fun deleteSession() {
        val id = mutable.value.selectedSession ?: return
        val reminderId = mutable.value.detailReminderId ?: return
        viewModelScope.launch { runCatching { repo.deleteConversation(id) }
            .onSuccess { openHistory(reminderId); notice("Conversation deleted") }
            .onFailure { notice(it.message ?: "Could not delete conversation") } }
    }

    fun testPush() {
        viewModelScope.launch { runCatching { repo.registerPush(true) }
            .onSuccess { mutable.value.uid?.let { AlertScheduler(getApplication()).reschedule(it) }
                notice("Test notification sent") }
            .onFailure { notice(it.message ?: "Could not enable notifications") } }
    }

    fun signOut() { viewModelScope.launch { runCatching { repo.signOut() }
        .onFailure { notice(it.message ?: "Could not sign out") } } }

    override fun onCleared() { repo.stopObserving(); repo.auth.removeAuthStateListener(authListener) }
}
