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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
            RemindTheme(dark = dark) {
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
        handleReminderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    private fun handleReminderIntent(intent: Intent) {
        val uid = intent.getStringExtra("uid")
        if (uid != null && uid != RemindRepository(this).cachedUid()) return
        val reminderId = intent.getStringExtra("reminderId") ?: return
        if (intent.data?.scheme == "remind" && intent.data?.host == "later") {
            intent.getStringExtra("occurrenceId")?.let { model.openSnoozeChooser(reminderId, it) }
        } else model.setTab("Upcoming")
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
    var draft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Reminder?>(null) }
    var deleteSession by remember { mutableStateOf(false) }
    LaunchedEffect(state.notice) { if (state.notice.isNotBlank()) { snackbar.showSnackbar(state.notice); model.clearNotice() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { if (state.uid != null) TopAppBar(
            title = { Text("remind", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        ) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (state.uid != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Ask", "Upcoming", "History", "Settings").forEach { tab ->
                NavigationBarItem(selected = state.tab == tab, onClick = { model.setTab(tab) },
                    icon = { Icon(when (tab) {
                        "Ask" -> Icons.Default.Mic
                        "Upcoming" -> Icons.Default.Notifications
                        "History" -> Icons.Default.History
                        else -> Icons.Default.Settings
                    }, contentDescription = if (tab == "Ask") "Create" else tab) },
                    label = { Text(if (tab == "Ask") "Create" else tab) })
            }
        } } ) { padding ->
        if (state.uid == null) WelcomeScreen(Modifier.fillMaxSize().padding(padding), onSignIn)
        else when (state.tab) {
            "Ask" -> Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        ScreenHeading("New reminder", "Type or speak your reminder.")
                    }
                    if (state.turns.isEmpty()) item {
                        Surface(shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("What do you need to remember?", style = MaterialTheme.typography.titleLarge)
                                Text("Review the times before saving.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("TRY AN EXAMPLE", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            draft = "Remind me every weekday at 8:30 to take my vitamins"
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Every weekday at 8:30 · Take vitamins")
                        }
                    }
                    items(state.turns) { (user, assistant) ->
                        ConversationTurn(user, assistant)
                    }
                    state.proposal?.let { proposal -> item {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (proposal.action == "delete") "Review deletion" else "Review reminder",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(proposal.readback, style = MaterialTheme.typography.bodyLarge)
                                if (proposal.action == "create" || proposal.action == "edit") {
                                    Text("UPCOMING TIMES", style = MaterialTheme.typography.labelMedium)
                                    proposal.preview.take(5).forEach { Text(dateLabel(it),
                                        style = MaterialTheme.typography.bodyMedium) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !state.busy,
                                        onClick = { onHaptic(); onConfirm() }) { Text("Confirm") }
                                    TextButton(onClick = model::cancelProposal) { Text("Cancel") }
                                }
                            }
                        }
                    } }
                    if (state.busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Checking the schedule…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f), label = { Text("Reminder") },
                            singleLine = true)
                        IconButton(enabled = draft.isNotBlank() && !state.busy, onClick = {
                            onHaptic(); model.send(draft); draft = ""
                        }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                    }
                    FilledTonalButton(enabled = !state.busy, onClick = { onHaptic(); onMic() },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recording) "Stop recording" else "Speak reminder")
                    }
                    Text("Review and confirm before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            "Upcoming" -> PullToRefreshBox(isRefreshing = state.refreshing,
                onRefresh = model::refresh, modifier = Modifier.fillMaxSize().padding(padding)) { LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val upcoming = state.reminders.filter { it.active && it.title.contains(search, true) }
                    .sortedBy { it.nextDueAt }
                item {
                    ScreenHeading("Upcoming", "Scheduled reminders and items needing attention.")
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(search, { search = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Search reminders") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(18.dp))
                    Text("${upcoming.size} ${if (upcoming.size == 1) "REMINDER" else "REMINDERS"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(upcoming, key = { it.id }) { reminder -> ReminderCard(reminder,
                    onEdit = { draft = "Change my reminder “${reminder.title}” to "; model.setTab("Ask") },
                    onHistory = { model.openHistory(reminder.id) }, onDelete = { deleting = reminder },
                    onAction = { onHaptic(); model.act(reminder, it) }) }
                if (upcoming.isEmpty()) item {
                    EmptyState(if (search.isBlank()) "Nothing scheduled yet" else "No matching reminders",
                        if (search.isBlank()) "Create a reminder to get started."
                        else "Try a different search.")
                }
            } }
            "History" -> PullToRefreshBox(isRefreshing = state.refreshing,
                onRefresh = model::refresh, modifier = Modifier.fillMaxSize().padding(padding)) { LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ScreenHeading("History", "Past reminders and recent activity.") }
                val past = state.reminders.filter { !it.active }
                items(past, key = { it.id }) { reminder -> ReminderCard(reminder,
                    onEdit = { draft = "Change my reminder “${reminder.title}” to "; model.setTab("Ask") },
                    onHistory = { model.openHistory(reminder.id) }, onDelete = { deleting = reminder },
                    onAction = { model.act(reminder, it) }) }
                if (past.isEmpty() && state.events.isEmpty()) item {
                    EmptyState("No history yet", "Completed reminders and actions appear here.")
                }
                if (state.events.isNotEmpty()) item {
                    Spacer(Modifier.height(10.dp))
                    Text("RECENT ACTIONS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.events) { event ->
                    val action = event["action"]?.toString().orEmpty()
                    val title = state.reminders.find { it.id == event["reminderId"] }?.title ?: "Reminder"
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface) {
                        ListItem(headlineContent = { Text(title) },
                            supportingContent = { Text("${action.replaceFirstChar { it.uppercase() }} · ${dateLabel((event["at"] as? Number)?.toLong())}") },
                            leadingContent = { Icon(Icons.Default.History, contentDescription = null) })
                    }
                }
            } }
            else -> SettingsScreen(state, Modifier.fillMaxSize().padding(padding).padding(16.dp),
                onTheme = model::setTheme, onSpoken = model::setSpokenReplies,
                onNotifications = onNotifications, onExactAlarms = onExactAlarms,
                onSignOut = model::signOut)
        }
    }
    if (state.uid != null && state.snoozeReminderId != null) AlertDialog(
        onDismissRequest = model::closeSnoozeChooser,
        title = { Text("Remind me later") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("In 10 minutes" to 10, "In 1 hour" to 60,
                "This evening (6:00 PM)" to 0).forEach { (label, minutes) ->
                OutlinedButton(onClick = {
                    if (minutes == 0) model.snooze(10, model.eveningSnoozeAt())
                    else model.snooze(minutes)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
            Text("A regular reminder may arrive sooner.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = model::closeSnoozeChooser) { Text("Cancel") } })
    deleting?.let { reminder -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("Delete reminder?") },
        text = { Text("“${reminder.title}” and its records will be permanently deleted.") },
        confirmButton = { TextButton(onClick = { model.delete(reminder); deleting = null }) { Text("Delete permanently") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
    if (state.detailReminderId != null) AlertDialog(onDismissRequest = model::closeHistory,
        title = { Text("Conversations") },
        text = { Column(Modifier.height(350.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.sessions.isEmpty()) {
                Text("No saved conversations for this reminder yet.",
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("SAVED CONVERSATIONS", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.sessions.forEach { (id, linkedAt) ->
                Row(Modifier.fillMaxWidth().clickable { model.selectSession(id) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text(dateLabel(linkedAt), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    if (state.selectedSession == id) Icon(Icons.Default.Check,
                        contentDescription = "Selected", modifier = Modifier.size(18.dp))
                }
            }
            if (state.savedTurns.isNotEmpty()) HorizontalDivider()
            state.savedTurns.forEach { (user, assistant) -> ConversationTurn(user, assistant) }
            if (state.selectedSession != null) TextButton(onClick = { deleteSession = true }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete this conversation")
            }
        } }, confirmButton = { TextButton(onClick = model::closeHistory) { Text("Close") } })
    if (deleteSession) AlertDialog(onDismissRequest = { deleteSession = false }, title = { Text("Delete conversation?") },
        confirmButton = { TextButton(onClick = { model.deleteSession(); deleteSession = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteSession = false }) { Text("Keep") } })
}

@Composable
internal fun ReminderCard(reminder: Reminder, onEdit: () -> Unit, onHistory: () -> Unit,
    onDelete: () -> Unit, onAction: (String) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (reminder.currentAlert)
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = if (reminder.currentAlert) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (reminder.currentAlert) "NEEDS ATTENTION" else if (reminder.active) "NEXT UP" else "PAST REMINDER",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() })
                        DropdownMenuItem(text = { Text("Conversations") },
                            leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null) },
                            onClick = { menuExpanded = false; onHistory() })
                        DropdownMenuItem(text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            if (reminder.notes.isNotBlank()) Text(reminder.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reminder.active) {
                Text("Follows up every ${reminder.repeatUntilDoneMinutes ?: 30} min until Done", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reminder.active) Text("UPCOMING TIMES", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            reminder.nextDueAt?.let { due ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("${if (reminder.currentScheduledAt != null) "Follow-up" else "Next"}: ${dateLabel(due)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                val regular = if (reminder.currentScheduledAt != null)
                    Schedule.preview(reminder.rule, reminder.currentScheduledAt, 5)
                else listOf(due) + Schedule.preview(reminder.rule, due, 4)
                regular.take(5).forEachIndexed { index, time ->
                    Text("${if (index == 0) "Scheduled" else "Then"} ${dateLabel(time)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (reminder.currentAlert) {
                Text("Awaiting Done since ${dateLabel(reminder.lastScheduledAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction("done") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Done")
                    }
                    FilledTonalButton(onClick = { onAction("snooze") }, modifier = Modifier.weight(1f)) {
                        Text("Remind me later", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: RemindUiState, modifier: Modifier, onTheme: (String) -> Unit,
    onSpoken: (Boolean) -> Unit, onNotifications: () -> Unit, onExactAlarms: () -> Unit,
    onSignOut: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenHeading("Settings", "Make Remind work the way you like.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Text("Choose a look for this phone.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "light", "dark").forEach { mode ->
                        FilterChip(selected = state.theme == mode, onClick = { onTheme(mode) },
                            label = { Text(mode.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column {
                ListItem(headlineContent = { Text("Spoken replies") },
                    supportingContent = { Text("Read replies aloud after voice requests") },
                    trailingContent = { Switch(state.spokenReplies, onCheckedChange = onSpoken) })
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Text("Test delivery and allow accurate local alerts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null,
                            Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Enable & test notifications")
                    }
                    OutlinedButton(onClick = onExactAlarms, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Alarms & reminders access")
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Text(state.email, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(modifier: Modifier, onSignIn: () -> Unit) {
    Box(modifier) {
        Column(Modifier.align(Alignment.Center).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null,
                    modifier = Modifier.padding(20.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text("Make room for what matters.", style = MaterialTheme.typography.headlineMedium)
            Text("Create reminders by typing or speaking. Review the schedule before saving.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with Google")
            }
        }
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationTurn(user: String, assistant: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Text(user, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (assistant.isNotBlank()) {
            Surface(shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(assistant, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Notifications, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dateLabel(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(it))
} ?: "—"
