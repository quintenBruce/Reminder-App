package com.qcbru.remind.alerts

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.qcbru.remind.data.RemindRepository
import com.qcbru.remind.data.ActionSync
import com.qcbru.remind.data.PushSync

class RemindMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val repo = RemindRepository(this)
        val uid = repo.cachedUid() ?: return
        val data = message.data
        if (data["uid"] != uid) return
        val reminderId = data["reminderId"] ?: return
        val occurrenceId = data["occurrenceId"] ?: reminderId
        val alertId = data["alertId"] ?: occurrenceId
        AlertNotifier.show(this, uid, data["title"] ?: "Remind", data["body"] ?: "",
            reminderId, occurrenceId, alertId, "push")
        ActionSync.enqueue(this)
    }

    override fun onNewToken(token: String) {
        if (RemindRepository(this).cachedUid() == null) return
        PushSync.enqueue(this)
    }
}
