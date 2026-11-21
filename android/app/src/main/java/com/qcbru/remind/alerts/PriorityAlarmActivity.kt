package com.qcbru.remind.alerts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        enableEdgeToEdge()
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
        setContent {
            RemindTheme(dark = isSystemInDarkTheme()) {
                PriorityAlarmScreen(
                    title = reminder.title,
                    notes = reminder.notes,
                    onDone = {
                        val local = store.alertIds(uid, occurrence).any { store.isLocalAlert(uid, it) }
                        repo.queueAction(uid, id, occurrence, "done", local)
                        finish()
                    },
                    onLater = {
                        startActivity(Intent(this@PriorityAlarmActivity, MainActivity::class.java).apply {
                            data = Uri.parse("remind://later/$id")
                            putExtra("uid", uid)
                            putExtra("reminderId", id)
                            putExtra("occurrenceId", occurrence)
                        })
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }
    override fun onDestroy() { listener?.remove(); super.onDestroy() }
}

@Composable
private fun PriorityAlarmScreen(
    title: String,
    notes: String,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val screenHeight = maxHeight
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).heightIn(min = screenHeight)
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = screenHeight * 0.66f)
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Alarm, contentDescription = null, modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Priority reminder", color = colors.primary, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(notes, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Done for me")
                    }
                    OutlinedButton(onClick = onLater, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Remind me later")
                    }
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            }
        }
    }
}
