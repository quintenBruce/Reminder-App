package com.qcbru.remind.data

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.alerts.AlertScheduler
import com.qcbru.remind.widget.ReminderWidgetProvider
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.TimeUnit

class RemindRepository(private val context: Context) {
    companion object { const val LOCAL_CHANGE_ACTION = "com.qcbru.remind.LOCAL_CHANGE" }
    val auth = FirebaseAuth.getInstance()
    private val database = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance("us-south1")
    private val store = (context.applicationContext as RemindApplication).store
    private val preferences = context.getSharedPreferences("remind", Context.MODE_PRIVATE)
    private var reminderListener: ListenerRegistration? = null
    private var eventListener: ListenerRegistration? = null

    suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *> {
        val timeoutSeconds = if (name in setOf("confirmProposal", "deleteReminder", "initializeConversations")) 540L else 120L
        val result = functions.getHttpsCallable(name).withTimeout(timeoutSeconds, TimeUnit.SECONDS).call(data).await().data
        return result as? Map<*, *> ?: emptyMap<String, Any>()
    }

    fun observe(uid: String, onReminders: (List<Reminder>) -> Unit, onEvents: (List<Map<String, Any?>>) -> Unit,
        onError: (String) -> Unit) {
        reminderListener?.remove(); eventListener?.remove()
        onReminders(store.reminders(uid))
        reminderListener = database.collection("users").document(uid).collection("reminders")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { onError(error.message ?: "Could not load reminders"); return@addSnapshotListener }
                if (snapshot != null) {
                    val reminders = snapshot.documents.mapNotNull { doc -> runCatching { Reminder.fromDocument(doc) }.getOrNull() }
                    // A cached snapshot may be incomplete. Only a server snapshot replaces the alarm mirror.
                    if (!snapshot.metadata.isFromCache) {
                        store.replaceReminders(uid, reminders)
                        AlertScheduler(context).reschedule(uid)
                        ReminderWidgetProvider.update(context)
                    }
                    onReminders(if (snapshot.metadata.isFromCache) reminders else store.reminders(uid))
                }
            }
        eventListener = database.collection("users").document(uid).collection("events")
            .orderBy("at", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { onError(error.message ?: "Could not load history"); return@addSnapshotListener }
                onEvents(snapshot?.documents?.map { it.data.orEmpty() + ("id" to it.id) } ?: emptyList())
            }
    }

    fun stopObserving() { reminderListener?.remove(); eventListener?.remove() }

    suspend fun refresh(uid: String): Pair<List<Reminder>, List<Map<String, Any?>>> {
        val owner = database.collection("users").document(uid)
        val reminders = owner.collection("reminders").get(Source.SERVER).await().documents
            .mapNotNull { runCatching { Reminder.fromDocument(it) }.getOrNull() }
        val events = owner.collection("events").orderBy("at", Query.Direction.DESCENDING)
            .limit(100).get(Source.SERVER).await().documents
            .map { it.data.orEmpty() + ("id" to it.id) }
        store.replaceReminders(uid, reminders)
        AlertScheduler(context).reschedule(uid)
        ReminderWidgetProvider.update(context)
        return store.reminders(uid) to events
    }

    suspend fun initialize() { call("initializeConversations") }

    suspend fun propose(text: String, conversationId: String, zone: String, pendingId: String?): Map<*, *> =
        call("proposeAction", mapOf("text" to text, "conversationId" to conversationId,
            "timeZone" to zone, "pendingProposalId" to pendingId))

    suspend fun confirm(proposal: Proposal) {
        if (proposal.action == "delete") call("deleteReminder", mapOf("reminderId" to proposal.targetId,
            "expectedVersion" to proposal.baseVersion, "proposalId" to proposal.id))
        else call("confirmProposal", mapOf("proposalId" to proposal.id))
    }

    suspend fun deleteReminder(reminder: Reminder) {
        call("deleteReminder", mapOf("reminderId" to reminder.id, "expectedVersion" to reminder.version))
    }

    suspend fun deleteConversation(id: String) { call("deleteConversation", mapOf("conversationId" to id)) }

    suspend fun conversations(uid: String, reminderId: String): List<Pair<String, Long>> =
        database.collection("users").document(uid).collection("conversations")
            .whereEqualTo("reminderId", reminderId).get().await().documents
            .filter { it.getString("state") == "linked" }
            .map { it.id to (it.getLong("linkedAt") ?: 0L) }.sortedByDescending { it.second }

    suspend fun turns(uid: String, conversationId: String): List<Pair<String, String>> =
        database.collection("users").document(uid).collection("conversations")
            .document(conversationId).collection("turns").orderBy("createdAt").get().await().documents
            .map { (it.getString("user") ?: "") to (it.getString("assistant") ?: "") }

    suspend fun refreshReminder(uid: String, id: String) {
        val doc = database.collection("users").document(uid).collection("reminders").document(id).get(Source.SERVER).await()
        if (doc.exists()) store.saveReminder(uid, Reminder.fromDocument(doc))
        else store.removeReminder(uid, id)
        AlertScheduler(context).reschedule(uid)
        ReminderWidgetProvider.update(context)
    }

    suspend fun discardDraft(id: String) { call("deleteConversation", mapOf("conversationId" to id, "draftOnly" to true)) }

    suspend fun registerPush(test: Boolean) {
        val uid = auth.currentUser?.uid ?: error("Sign in first")
        val token = FirebaseMessaging.getInstance().token.await()
        call("registerDevice", mapOf("token" to token, "test" to test))
        val old = preferences.getString("push_token", null)
        if (old != null && old != token) call("unregisterDevice", mapOf("token" to old))
        preferences.edit().putString("push_token", token).putString("uid", uid).apply()
    }

    suspend fun signOut() {
        val uid = auth.currentUser?.uid
        val token = preferences.getString("push_token", null)
        if (uid != null && token != null) call("unregisterDevice", mapOf("token" to token))
        stopObserving()
        if (uid != null) {
            AlertScheduler(context).cancelAll(uid)
            store.clearAccount(uid)
            ReminderWidgetProvider.update(context)
        }
        preferences.edit().clear().apply()
        ReminderWidgetProvider.update(context)
        FirebaseMessaging.getInstance().deleteToken().await()
        auth.signOut()
    }

    fun cachedUid(): String? = preferences.getString("uid", null)
    fun setActiveUid(uid: String) {
        val old = cachedUid()
        if (old != null && old != uid) {
            AlertScheduler(context).cancelAll(old)
            store.clearAccount(old)
        }
        preferences.edit().putString("uid", uid).apply()
        ReminderWidgetProvider.update(context)
    }

    fun queueAction(uid: String, reminderId: String, occurrenceId: String, action: String,
        local: Boolean, snoozeMinutes: Int = 10, snoozeAt: Long? = null): PendingAction {
        val pending = PendingAction(UUID.randomUUID().toString(), uid, reminderId, occurrenceId, action,
            if (action == "snooze") snoozeMinutes else null, snoozeAt, local)
        store.queue(pending)
        val manager = context.getSystemService(NotificationManager::class.java)
        store.alertIds(uid, occurrenceId).forEach { manager.cancel(it.hashCode()) }
        store.reminder(uid, reminderId)?.let { reminder ->
            store.saveReminder(uid, LocalActions.apply(reminder, occurrenceId, action,
                System.currentTimeMillis(), snoozeMinutes, snoozeAt))
        }
        context.sendBroadcast(Intent(LOCAL_CHANGE_ACTION).setPackage(context.packageName))
        AlertScheduler(context).reschedule(uid)
        ReminderWidgetProvider.update(context)
        ActionSync.enqueue(context)
        return pending
    }

    fun newConversationId(): String = UUID.randomUUID().toString()
}
