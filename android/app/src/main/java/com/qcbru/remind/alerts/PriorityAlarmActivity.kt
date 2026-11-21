package com.qcbru.remind.alerts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.qcbru.remind.MainActivity
import com.qcbru.remind.RemindApplication
import com.qcbru.remind.RemindTheme
import com.qcbru.remind.data.RemindRepository

class PriorityAlarmActivity : ComponentActivity() {
    private var listener: ListenerRegistration? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val uid = intent.getStringExtra("uid") ?: return finish()
        val id = intent.getStringExtra("reminderId") ?: return finish()
        val occurrence = intent.getStringExtra("occurrenceId") ?: return finish()
        val repo = RemindRepository(this)
        if (repo.auth.currentUser?.uid != uid || repo.cachedUid() != uid) return finish()
        val store = (application as RemindApplication).store
        val reminder = store.reminder(uid, id) ?: return finish()
        if (!reminder.priority || !reminder.active || store.hasPendingAction(uid, occurrence)) return finish()
        listener = FirebaseFirestore.getInstance().collection("users").document(uid).collection("reminders").document(id)
            .addSnapshotListener { doc, _ ->
                if (doc != null && !doc.metadata.isFromCache && (!doc.exists() || doc.getString("status") != "active" ||
                    doc.getLong("lastAcknowledgedScheduledAt") == occurrence.substringAfterLast('_').toLongOrNull() ||
                    doc.getBoolean("priority") != true ||
                    (reminder.sharedVersion != null && doc.getLong("sharedVersion") != reminder.sharedVersion) ||
                    (doc.getLong("lastScheduledAt") == null && doc.getLong("nextDueAt") != occurrence.substringAfterLast('_').toLongOrNull()) ||
                    (doc.getLong("lastScheduledAt") ?: 0L) > (occurrence.substringAfterLast('_').toLongOrNull() ?: 0L))) finish()
            }
        setContent { RemindTheme(dark = false) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text("Priority reminder", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(20.dp))
                    Text(reminder.title, style = MaterialTheme.typography.headlineLarge)
                    Text(reminder.notes)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = {
                        val local = store.alertIds(uid, occurrence).any { store.isLocalAlert(uid, it) }
                        repo.queueAction(uid, id, occurrence, "done", local); finish()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Done for me") }
                    OutlinedButton(onClick = {
                        startActivity(Intent(this@PriorityAlarmActivity, MainActivity::class.java).apply {
                            data = Uri.parse("remind://later/$id"); putExtra("uid", uid)
                            putExtra("reminderId", id); putExtra("occurrenceId", occurrence)
                        }); finish()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Remind me later") }
                    TextButton(onClick = { finish() }) { Text("Close") }
                }
            }
        } }
    }
    override fun onDestroy() { listener?.remove(); super.onDestroy() }
}
