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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.firebase.functions.FirebaseFunctionsException
import java.util.TimeZone

enum class ComposerProgress(val label: String) {
    THINKING("Thinking…"),
    TRANSCRIBING("Transcribing…"),
    SAVING("Saving…"),
}

data class RemindUiState(
    val profileStatus: String = "Checking your account…",
    val profileChecking: Boolean = true,
    val signingOut: Boolean = false,
    val friendsRefreshing: Boolean = false,
    val profileLoaded: Boolean = false,
    val profileReady: Boolean = false,
    val displayName: String = "",
    val username: String = "",
    val friends: List<Map<String, String>> = emptyList(),
    val preparingFriendLink: Boolean = false,
    val inviter: String? = null,
    val inviteLoading: Boolean = false,
    val inviteError: String? = null,
    val recipientChoices: List<Pair<String, String>> = emptyList(),
    val matchName: String = "",
    val originalText: String = "",
    val uid: String? = null,
    val email: String = "",
    val tab: String = "Ask",
    val reminders: List<Reminder> = emptyList(),
    val events: List<Map<String, Any?>> = emptyList(),
    val turns: List<Pair<String, String>> = emptyList(),
    val proposal: Proposal? = null,
    val conversationId: String = "",
    val conversationOpener: String = "",
    val busy: Boolean = false,
    val composerProgress: ComposerProgress? = null,
    val pendingMessage: String? = null,
    val notice: String = "",
    val speak: String? = null,
    val theme: String = "system",
    val spokenReplies: Boolean = true,
    val detailReminderId: String? = null,
    val sessions: List<Pair<String, Long>> = emptyList(),
    val selectedSession: String? = null,
    val savedTurns: List<Pair<String, String>> = emptyList(),
    val snoozeReminderId: String? = null,
    val snoozeOccurrenceId: String? = null,
    val refreshing: Boolean = false,
)

class RemindViewModel(application: Application) : AndroidViewModel(application) {
    private data class SnoozeRequest(val uid: String, val reminderId: String, val occurrenceId: String)
    private val repo = RemindRepository(application)
    private val prefs = application.getSharedPreferences("remind", 0)
    private val openers = ConversationOpeners(application.getSharedPreferences("conversation_openers", 0))
    private val store = (application as RemindApplication).store
    private val mutable = MutableStateFlow(RenindInitial())
    val state = mutable.asStateFlow()
    val completionHaptics = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reminderCreated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var profileJob: Job? = null
    private var profileWaiting = false
    private var foreground = false
    private var pendingSnooze: SnoozeRequest? = null
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        val verified = user?.isEmailVerified == true && user.providerData.any { it.providerId == "google.com" }
        if (verified) openAccount(user.uid, user.email ?: "")
        else {
            profileJob?.cancel()
            invitePreviewJob?.cancel()
            repo.stopObserving()
            mutable.update { it.copy(uid = null, email = "", reminders = emptyList(), proposal = null, profileLoaded = false, profileReady = false, friendsRefreshing = false, friends = emptyList(), preparingFriendLink = false, inviter = null, inviteError = null) }
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
        val opener = openers.next()
        mutable.update { it.copy(uid = uid, email = email, conversationId = fresh, conversationOpener = opener, turns = emptyList(),
            pendingMessage = null, composerProgress = null, busy = false,
            profileReady = prefs.contains("profile_username:$uid"), profileLoaded = prefs.contains("profile_username:$uid"),
            displayName = prefs.getString("profile_name:$uid", "") ?: "", username = prefs.getString("profile_username:$uid", "") ?: "",
            proposal = null, reminders = store.reminders(uid), notice = prefs.getString("sync_notice", "") ?: "") }
        loadProfile()
        resolvePendingSnooze()
        prefs.edit().remove("sync_notice").apply()
        repo.observe(uid, { reminders -> mutable.update { it.copy(reminders = reminders) } },
            { events -> mutable.update { it.copy(events = events) } }, { error -> notice(error) })
        viewModelScope.launch {
            runCatching { repo.initialize() }.onFailure { notice(it.message ?: "Could not initialize history") }
            runCatching { repo.call("ensureAcknowledgmentPolicy") }
                .onFailure { notice(it.message ?: "Could not update reminder follow-ups") }
            if (previous != null) runCatching { repo.discardDraft(previous) }
            if (notificationsAllowed()) PushSync.enqueue(getApplication())
            ActionSync.enqueue(getApplication())
            AlertScheduler(getApplication()).reschedule(uid)
        }
    }

    private var inviteToken: String? = null
    private var invitePreviewJob: Job? = null
    private fun accountTask(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (error: Exception) { notice(error.message ?: "Request failed") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun loadProfile() {
        val uid = mutable.value.uid ?: return
        profileJob?.cancel()
        profileWaiting = false
        mutable.update { it.copy(profileChecking = true, profileStatus = "Checking your account…") }
        profileJob = viewModelScope.launch {
            var backoff = 1000L
            while (true) {
                try {
                    val profile = repo.call("accountProfile")["profile"] as? Map<*, *>
                    if (mutable.value.uid != uid) return@launch
                    if (profile != null) prefs.edit().putString("profile_name:$uid", profile["displayName"] as? String)
                        .putString("profile_username:$uid", profile["username"] as? String).apply()
                    mutable.update { it.copy(profileChecking = false, profileLoaded = true, profileReady = profile != null,
                        displayName = profile?.get("displayName") as? String ?: "", username = profile?.get("username") as? String ?: "") }
                    if (profile != null) previewInvite()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val code = (error as? FirebaseFunctionsException)?.code
                    val retryable = code in setOf(FirebaseFunctionsException.Code.UNAVAILABLE,
                        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, FirebaseFunctionsException.Code.INTERNAL,
                        FirebaseFunctionsException.Code.UNKNOWN, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
                    if (!retryable || !foreground) {
                        mutable.update { it.copy(profileChecking = false,
                            profileStatus = "Remind couldn’t verify your account.") }
                        return@launch
                    }
                    mutable.update { it.copy(profileStatus = "Connecting to your account…") }
                    profileWaiting = true
                    delay(backoff)
                    profileWaiting = false
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }
    fun saveProfile(name: String, username: String) = accountTask {
        repo.call("saveProfile", mapOf("displayName" to name, "username" to username)); loadProfile()
    }
    fun loadFriends() {
        val uid = mutable.value.uid ?: return
        if (mutable.value.friendsRefreshing) return
        mutable.update { it.copy(friendsRefreshing = true) }
        viewModelScope.launch {
            try {
                val result = repo.call("listFriends")["friends"] as? List<*> ?: emptyList<Any>()
                val friends = result.mapNotNull { item ->
                    (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
                }
                if (mutable.value.uid == uid) mutable.update { it.copy(friends = friends) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (mutable.value.uid == uid) notice(error.message ?: "Could not refresh friends.")
            } finally {
                if (mutable.value.uid == uid) mutable.update { it.copy(friendsRefreshing = false) }
            }
        }
    }
    fun shareFriendLink(onReady: (String) -> Unit) = accountTask {
        val uid = mutable.value.uid ?: return@accountTask
        mutable.update { it.copy(preparingFriendLink = true) }
        try {
            val url = (repo.call("createFriendInvite")["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: error("Could not get a friend link.")
            if (mutable.value.uid == uid && foreground && mutable.value.tab == "Friends") onReady(url)
        } finally {
            mutable.update { it.copy(preparingFriendLink = false) }
        }
    }
    fun removeFriend(uid: String) = accountTask {
        repo.call("removeFriend", mapOf("uid" to uid)); mutable.update { it.copy(friends = it.friends.filter { f -> f["uid"] != uid }) }
    }
    fun receiveInvite(token: String) {
        if (!token.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return
        invitePreviewJob?.cancel()
        invitePreviewJob = null
        inviteToken = token
        prefs.edit().putString("pending_invite", token).apply()
        mutable.update { it.copy(inviteLoading = true, inviter = null, inviteError = null) }
        previewInvite()
    }
    private fun previewInvite() {
        val uid = mutable.value.uid ?: return
        if (!mutable.value.profileReady || invitePreviewJob?.isActive == true) return
        val token = inviteToken ?: prefs.getString("pending_invite", null) ?: return
        if (mutable.value.inviter != null || mutable.value.inviteError != null) return
        inviteToken = token
        mutable.update { it.copy(inviteLoading = true) }
        invitePreviewJob = viewModelScope.launch {
            try {
                val result = repo.call("friendInvite", mapOf("token" to token))
                val profile = result["profile"] as? Map<*, *> ?: error("Invitation unavailable.")
                val name = profile["displayName"] as? String ?: error("Invitation unavailable.")
                val username = profile["username"] as? String ?: error("Invitation unavailable.")
                if (inviteToken == token && mutable.value.uid == uid) {
                    mutable.update { it.copy(inviteLoading = false, inviter = "$name (@$username)") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (inviteToken == token && mutable.value.uid == uid) {
                    mutable.update { it.copy(inviteLoading = false, inviteError = error.message ?: "Invitation unavailable.") }
                }
            }
        }
    }
    fun closeInvite() {
        inviteToken = null
        prefs.edit().remove("pending_invite").apply()
        mutable.update { it.copy(inviter = null, inviteLoading = false, inviteError = null) }
    }
    fun acceptInvite() = accountTask {
        val token = inviteToken ?: return@accountTask
        if (mutable.value.inviteLoading || mutable.value.inviter == null) return@accountTask
        repo.call("friendInvite", mapOf("token" to token, "accept" to true))
        if (inviteToken == token && !mutable.value.inviteLoading) closeInvite()
        mutable.update { it.copy(tab = "Friends") }; notice("You are now friends")
    }
    fun setProposalPriority(priority: Boolean) { mutable.update { it.copy(proposal = it.proposal?.copy(priority = priority)) } }
    fun leave(reminder: Reminder) = accountTask {
        repo.call("leaveSharedReminder", mapOf("reminderId" to reminder.id)); notice("You left the reminder")
    }
    fun priorityOverride(reminder: Reminder, priority: Boolean?) = accountTask {
        repo.call("setPriorityOverride", mapOf("reminderId" to reminder.id, "priority" to priority)); notice("Your alert preference was saved")
    }

    fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun refreshLocal() { mutable.value.uid?.let { uid -> mutable.update { it.copy(reminders = store.reminders(uid)) } } }
    fun onResume() {
        foreground = true
        if (!mutable.value.profileLoaded && profileJob?.isActive != true) loadProfile()
        refreshLocal()
        if (mutable.value.uid != null && notificationsAllowed()) PushSync.enqueue(getApplication())
    }

    fun onBackground() {
        foreground = false
        profileJob?.cancel()
        profileWaiting = false
    }

    fun onNetworkAvailable() {
        if (foreground && profileWaiting) loadProfile()
    }

    fun refresh() {
        val uid = mutable.value.uid ?: return
        if (mutable.value.refreshing) return
        mutable.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val (reminders, events) = repo.refresh(uid)
                if (mutable.value.uid == uid) mutable.update { it.copy(reminders = reminders, events = events) }
            } catch (_: Exception) { notice("Could not refresh. Showing saved reminders.") }
            finally { mutable.update { it.copy(refreshing = false) } }
        }
    }

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
        val current = mutable.value
        val uid = current.uid ?: return
        val message = text.trim()
        if (message.isEmpty() || (current.busy && !(spoken && current.composerProgress == ComposerProgress.TRANSCRIBING))) return
        if (current.proposal != null && message.matches(Regex("(?i)yes|confirm|do it|okay|ok"))) {
            confirm(message); return
        }
        val conversationId = current.conversationId
        val pendingId = current.proposal?.id
        fun sameConversation() = mutable.value.uid == uid && mutable.value.conversationId == conversationId
        mutable.update { it.copy(busy = true, composerProgress = ComposerProgress.THINKING, pendingMessage = message, notice = "") }
        viewModelScope.launch {
            try {
                val result = repo.propose(message, conversationId, TimeZone.getDefault().id, pendingId)
                if (!sameConversation()) return@launch
                val reply = result["reply"] as? String ?: ""
                val proposal = (result["proposal"] as? Map<*, *>)?.let { Proposal.fromMap(it) }
                val choices = (result["recipientChoices"] as? List<*>)?.mapNotNull { item ->
                    (item as? Map<*, *>)?.let { (it["displayName"] as? String ?: "") to (it["username"] as? String ?: "") }
                } ?: emptyList()
                mutable.update { it.copy(turns = it.turns + (message to if (proposal == null) reply else ""), proposal = proposal,
                    composerProgress = null, pendingMessage = null,
                    recipientChoices = choices, matchName = result["matchName"] as? String ?: "", originalText = result["originalText"] as? String ?: "",
                    speak = if (spoken && it.spokenReplies) reply else null) }
                if (reply.isNotBlank()) completionHaptics.tryEmit(Unit)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (sameConversation()) notice(error.message ?: "Request failed") }
            finally { if (sameConversation()) mutable.update { it.copy(busy = false, composerProgress = null, pendingMessage = null) } }
        }
    }

    fun transcribe(audioBase64: String) {
        val current = mutable.value
        if (current.uid == null || current.busy) return
        fun sameConversation() = mutable.value.uid == current.uid && mutable.value.conversationId == current.conversationId
        mutable.update { it.copy(busy = true, composerProgress = ComposerProgress.TRANSCRIBING, notice = "") }
        viewModelScope.launch {
            try {
                val transcript = repo.call("transcribeAudio", mapOf("audioBase64" to audioBase64,
                    "mimeType" to "audio/mp4"))["transcript"] as? String ?: error("No transcript")
                if (transcript.isBlank()) error("No transcript")
                if (sameConversation()) send(transcript, true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (sameConversation()) {
                    notice(error.message ?: "Transcription failed")
                    mutable.update { it.copy(busy = false, composerProgress = null) }
                }
            }
        }
    }

    fun chooseRecipient(username: String) {
        val current = mutable.value
        if (current.matchName.isBlank() || current.recipientChoices.none { it.second == username }) return
        val name = Regex.escape(current.matchName.removePrefix("@"))
        val mention = Regex("(?<![\\p{L}\\p{N}_@])@?$name(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        val corrected = mention.replaceFirst(current.originalText, "@$username")
        send(if (corrected == current.originalText) "${current.originalText}. I meant @$username." else corrected)
    }

    fun confirm() = confirm(null)

    private fun confirm(message: String?) {
        if (mutable.value.busy && !(message != null && mutable.value.composerProgress == ComposerProgress.TRANSCRIBING)) return
        val proposal = mutable.value.proposal ?: return
        mutable.update { it.copy(busy = true, composerProgress = ComposerProgress.SAVING, pendingMessage = message, notice = "") }
        viewModelScope.launch {
            try {
                repo.confirm(proposal)
                if (proposal.action == "create") reminderCreated.tryEmit(Unit)
                freshConversation()
                notice(if (proposal.action == "delete") "Reminder deleted" else "Reminder saved")
                completionHaptics.tryEmit(Unit)
            } catch (error: Exception) { notice(error.message ?: "Could not save") }
            finally { mutable.update { it.copy(busy = false, composerProgress = null, pendingMessage = null) } }
        }
    }

    fun cancelProposal() {
        startFreshConversation()
    }

    fun startFreshConversation(): Boolean {
        if (mutable.value.uid == null || mutable.value.busy) return false
        val old = mutable.value.conversationId
        val hadConversation = mutable.value.turns.isNotEmpty() || mutable.value.proposal != null
        freshConversation()
        if (hadConversation) viewModelScope.launch { runCatching { repo.discardDraft(old) } }
        return true
    }

    private fun freshConversation() {
        val uid = mutable.value.uid ?: return
        val fresh = repo.newConversationId()
        prefs.edit().putString("conversation:$uid", fresh).apply()
        val opener = openers.next()
        mutable.update { it.copy(conversationId = fresh, conversationOpener = opener, turns = emptyList(), proposal = null, speak = null,
            recipientChoices = emptyList(), matchName = "", originalText = "", notice = "") }
    }

    fun act(reminder: Reminder, action: String) {
        if (action == "snooze") {
            reminder.occurrenceId?.let { openSnoozeChooser(reminder.id, it) }
            return
        }
        val uid = mutable.value.uid ?: return
        val occurrence = reminder.occurrenceId ?: return
        val local = store.alertIds(uid, occurrence).any { store.isLocalAlert(uid, it) }
        repo.queueAction(uid, reminder.id, occurrence, action, local)
        mutable.update { it.copy(reminders = store.reminders(uid)) }
        notice(if (action == "done") "Marked done" else "Reminding you later")
        if (action == "done") completionHaptics.tryEmit(Unit)
    }

    fun requestSnoozeChooser(uid: String, reminderId: String, occurrenceId: String) {
        pendingSnooze = SnoozeRequest(uid, reminderId, occurrenceId)
        resolvePendingSnooze()
    }

    private fun resolvePendingSnooze() {
        val request = pendingSnooze ?: return
        val uid = mutable.value.uid ?: return
        pendingSnooze = null
        if (uid != request.uid) {
            notice("This reminder belongs to another account")
        } else if (!openSnoozeChooser(request.reminderId, request.occurrenceId)) {
            notice("That alert is no longer current")
        }
    }

    fun openSnoozeChooser(reminderId: String, occurrenceId: String): Boolean {
        val uid = mutable.value.uid ?: return false
        val reminder = store.reminder(uid, reminderId) ?: return false
        if (!currentOccurrence(uid, reminder, occurrenceId)) return false
        mutable.update { it.copy(snoozeReminderId = reminderId, snoozeOccurrenceId = occurrenceId) }
        return true
    }

    private fun currentOccurrence(uid: String, reminder: Reminder, occurrenceId: String): Boolean {
        val scheduledAt = occurrenceId.removePrefix("${reminder.id}_").toLongOrNull() ?: return false
        if (reminder.lastAcknowledgedScheduledAt == scheduledAt ||
            (reminder.lastScheduledAt != null && scheduledAt < reminder.lastScheduledAt)) return false
        return (reminder.currentAlert && reminder.occurrenceId == occurrenceId) ||
            store.postedNotification(uid, reminder.id)?.occurrenceId == occurrenceId
    }

    fun closeSnoozeChooser() { mutable.update { it.copy(snoozeReminderId = null, snoozeOccurrenceId = null) } }

    fun snooze(minutes: Int? = null, exactAt: Long? = null) {
        if (exactAt != null && exactAt <= System.currentTimeMillis()) return
        val uid = mutable.value.uid ?: return
        val id = mutable.value.snoozeReminderId ?: return
        val occurrence = mutable.value.snoozeOccurrenceId ?: return
        val reminder = store.reminder(uid, id) ?: return
        if (!currentOccurrence(uid, reminder, occurrence)) {
            closeSnoozeChooser()
            notice("That alert is no longer current")
            return
        }
        val local = store.alertIds(uid, occurrence).any { store.isLocalAlert(uid, it) }
        repo.queueAction(uid, id, occurrence, "snooze", local, minutes, exactAt)
        closeSnoozeChooser()
        refreshLocal()
        notice("Reminding you later")
        completionHaptics.tryEmit(Unit)
    }

    fun delete(reminder: Reminder) {
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { repo.deleteReminder(reminder); notice("Reminder deleted"); completionHaptics.tryEmit(Unit) }
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

    fun signOut() {
        if (mutable.value.signingOut) return
        mutable.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            try {
                repo.signOut()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice(error.message ?: "Could not sign out")
            } finally {
                mutable.update { it.copy(signingOut = false) }
            }
        }
    }

    override fun onCleared() { repo.stopObserving(); repo.auth.removeAuthStateListener(authListener) }
}
