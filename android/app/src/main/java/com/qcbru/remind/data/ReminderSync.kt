package com.qcbru.remind.data

import android.content.Context
import androidx.work.*
import com.qcbru.remind.alerts.AlertNotifier

class ReminderSync(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = RemindRepository(applicationContext)
        val uid = inputData.getString("uid") ?: return Result.success()
        val id = inputData.getString("reminderId") ?: return Result.success()
        if (repo.auth.currentUser?.uid != uid || repo.cachedUid() != uid) return Result.success()
        return try {
            repo.refreshReminder(uid, id)
            if (repo.auth.currentUser?.uid != uid || repo.cachedUid() != uid) return Result.success()
            val store = (applicationContext as com.qcbru.remind.RemindApplication).store
            val reminder = store.reminder(uid, id)
            if (reminder != null && inputData.getString("sync") != "true") AlertNotifier.show(applicationContext, uid,
                reminder.title, reminder.notes, id,
                inputData.getString("occurrenceId") ?: id, inputData.getString("alertId") ?: id, "push")
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context, data: Map<String, String>) {
            val input = Data.Builder(); data.forEach { (key, value) -> input.putString(key, value) }
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderSync>()
                .setInputData(input.build()).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
