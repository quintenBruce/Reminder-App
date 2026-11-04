package com.qcbru.remind.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qcbru.remind.data.ActionSync
import com.qcbru.remind.data.RemindRepository
import com.qcbru.remind.data.PushSync

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = RemindRepository(context).cachedUid() ?: return
        AlertScheduler(context).reschedule(uid)
        ActionSync.enqueue(context)
        PushSync.enqueue(context)
    }
}
