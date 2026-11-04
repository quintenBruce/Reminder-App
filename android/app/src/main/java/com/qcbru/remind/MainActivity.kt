package com.qcbru.remind

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CustomCredential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.qcbru.remind.data.Reminder
import com.qcbru.remind.data.Schedule
import com.qcbru.remind.data.PushSync
import com.qcbru.remind.data.RemindRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: RemindViewModel by viewModels()
    private lateinit var voice: VoiceRecorder
    private var recording by mutableStateOf(false)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val localChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.refreshLocal() }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else model.notice("Microphone permission is needed for voice requests")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { model.testPush(); model.onResume() }
        else model.notice("Enable notifications in Android settings to receive alerts")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = VoiceRecorder(this)
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        setContent {
            val state by model.state.collectAsState()
            val dark = when (state.theme) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LaunchedEffect(state.speak) {
                    state.speak?.let { if (ttsReady) tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "remind-reply") }
                    model.clearSpeak()
                }
                RemindScreen(state, model, recording, onSignIn = ::signIn,
                    onMic = { if (recording) stopRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onConfirm = { val needsPush = state.proposal?.action != "delete"; model.confirm()
                        if (needsPush) {
                            if (!model.notificationsAllowed()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else PushSync.enqueue(this@MainActivity)
                        } },
                    onNotifications = { if (Build.VERSION.SDK_INT >= 33 && !model.notificationsAllowed())
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.testPush() },
                    onExactAlarms = ::requestExactAlarms,
                    onHaptic = { window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) })
            }
        }
    }

    private fun signIn() {
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(
                    serverClientId = getString(R.string.default_web_client_id)
                ).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val credential = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request).credential
                require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            } catch (error: Exception) { model.notice(error.message ?: "Google sign-in failed") }
        }
    }

    private fun startRecording() {
        runCatching { voice.start(); recording = true }
            .onFailure { model.notice(it.message ?: "Could not start microphone") }
    }
    private fun stopRecording() {
        recording = false
        runCatching { voice.stop() }.onSuccess(model::transcribe)
            .onFailure { model.notice(it.message ?: "Recording failed") }
    }
    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")))
        else model.notice("Exact alarms are enabled")
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, localChange, IntentFilter(RemindRepository.LOCAL_CHANGE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() { unregisterReceiver(localChange); super.onStop() }
    override fun onResume() { super.onResume(); model.onResume() }
    override fun onDestroy() { voice.cancel(); tts?.shutdown(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindScreen(state: RemindUiState, model: RemindViewModel, recording: Boolean,
    onSignIn: () -> Unit, onMic: () -> Unit, onConfirm: () -> Unit, onNotifications: () -> Unit,
    onExactAlarms: () -> Unit, onHaptic: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var draft by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Reminder?>(null) }
    var deleteSession by remember { mutableStateOf(false) }
    LaunchedEffect(state.notice) { if (state.notice.isNotBlank()) { snackbar.showSnackbar(state.notice); model.clearNotice() } }
    Scaffold(topBar = { TopAppBar(title = { Text("Remind") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (state.uid != null) NavigationBar {
            listOf("Ask", "Upcoming", "History", "Settings").forEach { tab ->
                NavigationBarItem(selected = state.tab == tab, onClick = { model.setTab(tab) },
                    icon = { androidx.compose.material3.Icon(when (tab) {
                        "Ask" -> Icons.Default.Mic
                        "Upcoming" -> Icons.Default.Notifications
                        "History" -> Icons.Default.History
                        else -> Icons.Default.Settings
                    }, contentDescription = tab) }, label = { Text(tab) })
            }
        } } ) { padding ->
        if (state.uid == null) Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text("Speak naturally. Stay on top of what matters.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSignIn) { Text("Continue with Google") }
        }
        else when (state.tab) {
            "Ask" -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("What should I remind you about?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (state.turns.isEmpty()) Text("Try: Remind me every weekday at 8:30 to take my vitamins.")
                    state.turns.forEach { (user, assistant) ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(user, fontWeight = FontWeight.Bold); Text(assistant)
                            }
                        }
                    }
                    state.proposal?.let { proposal -> Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (proposal.action == "delete") "Ready to delete" else "Ready to save",
                                style = MaterialTheme.typography.titleMedium)
                            Text(proposal.readback)
                            proposal.preview.forEach { Text(dateLabel(it)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !state.busy, onClick = { onHaptic(); onConfirm() }) { Text("Confirm") }
                                TextButton(onClick = model::cancelProposal) { Text("Cancel") }
                            }
                        }
                    } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), label = { Text("Ask for a reminder") }, singleLine = true)
                    Button(enabled = draft.isNotBlank() && !state.busy, onClick = {
                        onHaptic(); model.send(draft); draft = ""
                    }) { Text("Send") }
                }
                OutlinedButton(enabled = !state.busy, onClick = { onHaptic(); onMic() }) {
                    Text(if (recording) "Stop recording" else "Speak a request")
                }
                Text("Changes need your confirmation", style = MaterialTheme.typography.bodySmall)
            }
            "Upcoming" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Upcoming", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(search, { search = it }, label = { Text("Search reminders") },
                        modifier = Modifier.fillMaxWidth()) }
                val upcoming = state.reminders.filter { it.active && it.title.contains(search, true) }
                    .sortedBy { it.nextDueAt }
                items(upcoming, key = { it.id }) { reminder -> ReminderCard(reminder,
                    onEdit = { draft = "Change my reminder “${reminder.title}” to "; model.setTab("Ask") },
                    onHistory = { model.openHistory(reminder.id) }, onDelete = { deleting = reminder },
                    onAction = { onHaptic(); model.act(reminder, it) }) }
                if (upcoming.isEmpty()) item { Text("No upcoming reminders") }
            }
            "History" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("History", style = MaterialTheme.typography.headlineMedium) }
                val past = state.reminders.filter { !it.active }
                items(past, key = { it.id }) { reminder -> ReminderCard(reminder,
                    onEdit = { draft = "Change my reminder “${reminder.title}” to "; model.setTab("Ask") },
                    onHistory = { model.openHistory(reminder.id) }, onDelete = { deleting = reminder },
                    onAction = { model.act(reminder, it) }) }
                item { HorizontalDivider(); Text("Recent actions", style = MaterialTheme.typography.titleMedium) }
                items(state.events) { event -> Text("${event["action"]}: ${event["reminderId"]} · ${dateLabel((event["at"] as? Number)?.toLong())}") }
            }
            else -> SettingsScreen(state, Modifier.fillMaxSize().padding(padding).padding(16.dp),
                onTheme = model::setTheme, onSpoken = model::setSpokenReplies,
                onNotifications = onNotifications, onExactAlarms = onExactAlarms,
                onSignOut = model::signOut)
        }
    }
    deleting?.let { reminder -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("Delete reminder?") },
        text = { Text("“${reminder.title}” and its records will be permanently deleted.") },
        confirmButton = { TextButton(onClick = { model.delete(reminder); deleting = null }) { Text("Delete permanently") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
    if (state.detailReminderId != null) AlertDialog(onDismissRequest = model::closeHistory,
        title = { Text("Conversation history") },
        text = { Column(Modifier.height(350.dp).verticalScroll(rememberScrollState())) {
            state.sessions.forEach { (id, linkedAt) -> TextButton(onClick = { model.selectSession(id) }) {
                Text(dateLabel(linkedAt)) } }
            state.savedTurns.forEach { (user, assistant) -> Column(Modifier.padding(vertical = 6.dp)) {
                Text(user, fontWeight = FontWeight.Bold); Text(assistant) } }
            if (state.sessions.isEmpty()) Text("No saved conversations")
            if (state.selectedSession != null) TextButton(onClick = { deleteSession = true }) { Text("Delete this conversation") }
        } }, confirmButton = { TextButton(onClick = model::closeHistory) { Text("Close") } })
    if (deleteSession) AlertDialog(onDismissRequest = { deleteSession = false }, title = { Text("Delete conversation?") },
        confirmButton = { TextButton(onClick = { model.deleteSession(); deleteSession = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteSession = false }) { Text("Keep") } })
}

@Composable
internal fun ReminderCard(reminder: Reminder, onEdit: () -> Unit, onHistory: () -> Unit,
    onDelete: () -> Unit, onAction: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(reminder.title, style = MaterialTheme.typography.titleMedium)
        reminder.repeatUntilDoneMinutes?.let { Text("Repeats every $it min until Done") }
        if (reminder.currentAlert) {
            Text("Alert sent · ${dateLabel(reminder.lastScheduledAt)}")
            Row { TextButton(onClick = { onAction("done") }) { Text("Done") }
                TextButton(onClick = { onAction("snooze") }) { Text("Snooze 10 min") } }
        }
        reminder.nextDueAt?.let { due ->
            Text("Next · ${dateLabel(due)}")
            Schedule.preview(reminder.rule, due, 2).forEach { Text("Later · ${dateLabel(it)}") }
        }
        Row { TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onHistory) { Text("Conversations") }
            TextButton(onClick = onDelete) { Text("Delete") } }
    } }
}

@Composable
private fun SettingsScreen(state: RemindUiState, modifier: Modifier, onTheme: (String) -> Unit,
    onSpoken: (Boolean) -> Unit, onNotifications: () -> Unit, onExactAlarms: () -> Unit,
    onSignOut: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row { listOf("system", "light", "dark").forEach { mode ->
            TextButton(onClick = { onTheme(mode) }) { Text(if (state.theme == mode) "✓ $mode" else mode) } } }
        Row { Text("Spoken replies after voice requests", Modifier.weight(1f))
            Switch(state.spokenReplies, onCheckedChange = onSpoken) }
        OutlinedButton(onClick = onNotifications) { Text("Enable & test notifications") }
        OutlinedButton(onClick = onExactAlarms) { Text("Alarms & reminders access") }
        Text(state.email)
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}

private fun dateLabel(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(it))
} ?: "—"
