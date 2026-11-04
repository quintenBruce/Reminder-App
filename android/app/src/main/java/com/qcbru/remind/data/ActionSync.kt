package com.qcbru.remind.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.google.firebase.functions.FirebaseFunctionsException
import com.qcbru.remind.RemindApplication

class ActionSync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = RemindRepository(applicationContext)
        val uid = repo.auth.currentUser?.uid ?: return Result.success()
        val store = (applicationContext as RemindApplication).store
        for (pending in store.pending(uid)) {
            try {
                repo.call("applyOccurrenceAction", mapOf(
                    "reminderId" to pending.reminderId,
                    "occurrenceId" to pending.occurrenceId,
                    "action" to pending.action,
                    "snoozeMinutes" to pending.snoozeMinutes,
                    "requestId" to pending.requestId,
                    "deliverySource" to if (pending.localDelivery) "local" else "push",
                ))
                repo.refreshReminder(uid, pending.reminderId)
                store.removeAction(pending.requestId)
            } catch (error: FirebaseFunctionsException) {
                if (error.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION ||
                    error.code == FirebaseFunctionsException.Code.NOT_FOUND) {
                    store.removeAction(pending.requestId)
                    runCatching { repo.refreshReminder(uid, pending.reminderId) }
                    applicationContext.getSharedPreferences("remind", Context.MODE_PRIVATE).edit()
                        .putString("sync_notice", "An offline action was stale. Review the reminder.").apply()
                } else return Result.retry()
            } catch (_: Exception) { return Result.retry() }
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ActionSync>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("remind-actions", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
