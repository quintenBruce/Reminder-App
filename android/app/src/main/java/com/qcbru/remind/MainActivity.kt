package com.qcbru.remind

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
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
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import com.qcbru.remind.data.calendarSnoozeOptions
import com.qcbru.remind.data.dateLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    private val model: RemindViewModel by viewModels()
    private lateinit var voice: VoiceRecorder
    private var recording by mutableStateOf(false)
    private var recordingReady by mutableStateOf(false)
    private var recordingLimit: Job? = null
    private var exactAlarmGuide by mutableStateOf(false)
    private var guideAfterNotification = false
    private var testAfterNotification = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lifecycleScope.launch { model.onNetworkAvailable() }
        }
    }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val localChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { model.refreshLocal() }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else model.notice("Microphone permission is needed for voice requests")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (testAfterNotification) model.testPush()
            PushSync.enqueue(this)
        }
        else model.notice("Enable notifications in Android settings to receive alerts")
        testAfterNotification = false
        if (guideAfterNotification) showExactAlarmGuide()
        guideAfterNotification = false
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
                LaunchedEffect(model) {
                    model.completionHaptics.collect {
                        window.decorView.performHapticFeedback(HapticFeedbackConstantsCompat.CONFIRM)
                    }
                }
                LaunchedEffect(model) { model.reminderCreated.collect { guideReminderPermissions() } }
                RemindScreen(state, model, onSignIn = ::signIn,
                    onMic = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onConfirm = model::confirm,
                    onNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && !model.notificationsAllowed()) {
                            testAfterNotification = true
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else model.testPush()
                    },
                    onExactAlarms = ::requestExactAlarms,
                    onHaptic = { window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) })
                if (recording || recordingReady) VoiceSheet(recordingReady, voice::amplitude,
                    onSend = ::stopRecording, onCancel = ::cancelRecording)
                if (exactAlarmGuide) AlertDialog(
                    onDismissRequest = { exactAlarmGuide = false },
                    title = { Text("Allow exact alarms?") },
                    text = { Text("This helps reminders ring on time, including when you’re offline. Without it, local alerts may be delayed.") },
                    confirmButton = { TextButton(onClick = { exactAlarmGuide = false; requestExactAlarms() }) { Text("Open settings") } },
                    dismissButton = { TextButton(onClick = { exactAlarmGuide = false }) { Text("Not now") } },
                )
            }
        }
        if (savedInstanceState == null) handleReminderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    private fun handleReminderIntent(intent: Intent) {
        val link = intent.data
        if (link?.scheme == "https" && link.host == "reminder-app-a2150.web.app" && link.pathSegments.firstOrNull() == "invite") {
            link.pathSegments.getOrNull(1)?.let(model::receiveInvite)
            return
        }
        val uid = intent.getStringExtra("uid")
        if (uid != null && uid != RemindRepository(this).cachedUid()) return
        val reminderId = intent.getStringExtra("reminderId") ?: return
        if (intent.data?.scheme == "remind" && intent.data?.host == "later") {
            val occurrenceId = intent.getStringExtra("occurrenceId") ?: return
            val ownerUid = uid ?: return
            model.requestSnoozeChooser(ownerUid, reminderId, occurrenceId)
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
        if (recording || recordingReady || model.state.value.busy) return
        runCatching {
            voice.start {
                cancelRecording()
                model.notice("Could not record audio. Try speaking again.")
            }
            recording = true
            recordingLimit = lifecycleScope.launch {
                delay(60_000)
                if (recording) {
                    recording = false
                    runCatching { voice.finish() }.onSuccess { recordingReady = true }
                        .onFailure { model.notice(it.message ?: "Recording failed") }
                }
            }
        }
            .onFailure { model.notice(it.message ?: "Could not start microphone") }
    }
    private fun stopRecording() {
        if (!recording && !recordingReady) return
        recordingLimit?.cancel()
        recording = false
        recordingReady = false
        runCatching { voice.stop() }.onSuccess(model::transcribe)
            .onFailure { model.notice(it.message ?: "Recording failed") }
    }
    private fun cancelRecording() {
        recordingLimit?.cancel()
        recording = false
        recordingReady = false
        voice.cancel()
    }

    private fun guideReminderPermissions() {
        val prefs = getSharedPreferences("remind_permission_guidance", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !model.notificationsAllowed() && !prefs.getBoolean("notifications", false)) {
            prefs.edit().putBoolean("notifications", true).apply()
            guideAfterNotification = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else showExactAlarmGuide()
    }

    private fun showExactAlarmGuide() {
        if (Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return
        val prefs = getSharedPreferences("remind_permission_guidance", MODE_PRIVATE)
        if (prefs.getBoolean("exact_alarm", false)) return
        prefs.edit().putBoolean("exact_alarm", true).apply()
        exactAlarmGuide = true
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName")))
        else model.notice("Exact alarms are enabled")
    }
    override fun onStart() {
        super.onStart()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(this, localChange, IntentFilter(RemindRepository.LOCAL_CHANGE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() {
        cancelRecording()
        model.onBackground()
        connectivity.unregisterNetworkCallback(networkCallback)
        unregisterReceiver(localChange)
        super.onStop()
    }
    override fun onResume() { super.onResume(); model.onResume() }
    override fun onDestroy() { voice.cancel(); tts?.shutdown(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindScreen(state: RemindUiState, model: RemindViewModel,
    onSignIn: () -> Unit, onMic: () -> Unit, onConfirm: () -> Unit, onNotifications: () -> Unit,
    onExactAlarms: () -> Unit, onHaptic: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    val conversationList = rememberLazyListState()
    var lastScrolledResponse by rememberSaveable { mutableStateOf("") }
    var draftFocused by remember { mutableStateOf(false) }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (keyboardWasVisible && !imeVisible && draftFocused) focusManager.clearFocus()
        keyboardWasVisible = imeVisible
    }
    val sendDraft = {
        if (draft.isNotBlank() && !state.busy) {
            onHaptic()
            model.send(draft)
            draft = ""
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var filter by rememberSaveable { mutableStateOf("All") }
    var leaving by remember { mutableStateOf<Reminder?>(null) }
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
        bottomBar = { if (state.uid != null && state.profileReady && !imeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("Ask", "Upcoming", "History", "Friends", "Settings").forEach { tab ->
                NavigationBarItem(selected = state.tab == tab, onClick = { model.setTab(tab) },
                    icon = { Icon(when (tab) {
                        "Ask" -> Icons.Default.Mic
                        "Upcoming" -> Icons.Default.Notifications
                        "History" -> Icons.Default.History
                        "Friends" -> Icons.Default.Group
                        else -> Icons.Default.Settings
                    }, contentDescription = if (tab == "Ask") "Create" else tab) },
                    label = { Text(if (tab == "Ask") "Create" else tab) })
            }
        } } ) { padding ->
        if (state.uid == null) WelcomeScreen(Modifier.fillMaxSize().padding(padding), onSignIn)
        else if (!state.profileLoaded) AccountCheckScreen(state, Modifier.fillMaxSize().padding(padding))
        else if (!state.profileReady) ProfileScreen(state, model, Modifier.fillMaxSize().padding(padding))
        else when (state.tab) {
            "Ask" -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                val hasPendingMessage = state.pendingMessage != null ||
                    state.composerProgress == ComposerProgress.TRANSCRIBING
                LaunchedEffect(hasPendingMessage) {
                    if (hasPendingMessage) {
                        val pendingIndex = 1 + state.turns.size +
                            (if (state.conversationOpener.isNotBlank()) 1 else 0) +
                            (if (state.recipientChoices.isNotEmpty()) 1 else 0) +
                            (if (state.proposal != null) 1 else 0)
                        snapshotFlow { conversationList.layoutInfo.totalItemsCount }.first { it > pendingIndex }
                        conversationList.animateScrollToItem(pendingIndex)
                    }
                }
                LaunchedEffect(state.conversationId, state.turns.size) {
                    val response = "${state.conversationId}:${state.turns.size}"
                    if (state.turns.isNotEmpty() && response != lastScrolledResponse) {
                        // Anchor after the entire review, even when the card is taller than the viewport.
                        val endIndex = 1 + state.turns.size +
                            (if (state.conversationOpener.isNotBlank()) 1 else 0) +
                            (if (state.recipientChoices.isNotEmpty()) 1 else 0) +
                            (if (state.proposal != null) 1 else 0)
                        withFrameNanos { }
                        snapshotFlow { conversationList.layoutInfo.totalItemsCount }.first { it == endIndex + 1 }
                        conversationList.animateScrollToItem(endIndex)
                        lastScrolledResponse = response
                    }
                }
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = {
                        if (model.startFreshConversation()) {
                            draft = ""
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    LazyColumn(Modifier.fillMaxSize(), state = conversationList, contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            ScreenHeading("New reminder", "Type or speak your reminder.")
                        }
                        if (state.conversationOpener.isNotBlank()) item {
                            AssistantMessage(state.conversationOpener)
                        }
                        items(state.turns) { (user, assistant) ->
                            ConversationTurn(user, assistant)
                        }
                        if (state.recipientChoices.isNotEmpty()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Choose a friend", style = MaterialTheme.typography.titleMedium)
                                state.recipientChoices.forEach { (name, username) ->
                                    OutlinedButton(enabled = !state.busy, onClick = { model.chooseRecipient(username) }) { Text("$name (@$username)") }
                                }
                            }
                        }
                        state.proposal?.let { proposal -> item {
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (proposal.action == "delete") "Review deletion" else "Review reminder",
                                        style = MaterialTheme.typography.titleMedium)
                                    val review = proposal.review
                                    if (review == null) {
                                        // Older Functions deployments only return an unstructured summary.
                                        Text(proposal.readback.replace(
                                            Regex("; follow up every \\d+ minutes until Done\\. Confirm\\?", RegexOption.IGNORE_CASE), ""),
                                            style = MaterialTheme.typography.bodyLarge)
                                    } else {
                                        review.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                                        review.actionDetail?.let { Text(it) }
                                        review.whenAt?.let { Text("When · ${dateLabel(it)}") }
                                        review.recurrence?.takeUnless { it == "Once" }?.let { cadence ->
                                            val repeatTime = review.repeatTime?.let { " at $it" }.orEmpty()
                                            Text("Repeats · $cadence$repeatTime")
                                        }
                                        review.scheduleZone?.let { Text("Schedule follows $it. Alert times shown in Central.") }
                                        review.startsAt?.let { Text("Starts · ${dateLabel(it)}") }
                                        review.endsAt?.let { Text("Ends · ${dateLabel(it)}") }
                                        review.notes?.takeIf { it.isNotBlank() }?.let { Text("Notes · $it") }
                                        if (review.recipients.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Shared with · ${review.recipients.joinToString()}")
                                                Text("Participants can edit. Done and Snooze apply individually.",
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        review.followUpMinutes?.let { Text("Follow-up · Every $it minutes until Done") }
                                    }
                                    if (proposal.action == "create" || proposal.action == "edit") {
                                        if (review?.whenAt == null && proposal.preview.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("NEXT ALERTS", style = MaterialTheme.typography.labelMedium)
                                                proposal.preview.take(3).forEach { Text("•  ${dateLabel(it)}",
                                                    style = MaterialTheme.typography.bodyMedium) }
                                            }
                                        }
                                        if (proposal.priority) {
                                            Text("Fullscreen alarm when permitted. Shared with everyone; each person can override it.", style = MaterialTheme.typography.bodySmall)
                                            PriorityAccessButton()
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            androidx.compose.material3.Checkbox(checked = proposal.priority, onCheckedChange = { model.setProposalPriority(it) })
                                            Text("Priority alarm")
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(enabled = !state.busy,
                                            onClick = { onHaptic(); onConfirm() }) { Text(if (proposal.action == "create") "Create" else "Confirm") }
                                        TextButton(onClick = model::cancelProposal) { Text("Cancel") }
                                    }
                                }
                            }
                        } }
                        if (hasPendingMessage) item(key = "pending-message") {
                            PendingConversationMessage(state.pendingMessage)
                        }
                        state.composerProgress?.takeUnless { it == ComposerProgress.TRANSCRIBING }?.let { progress -> item {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(progress.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } }
                        item(key = "conversation-end") { Spacer(Modifier.height(1.dp)) }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f).onFocusChanged { draftFocused = it.isFocused },
                            label = { Text("Reminder") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendDraft() }))
                        IconButton(enabled = draft.isNotBlank() && !state.busy, onClick = sendDraft) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                    if (!imeVisible) FilledTonalButton(enabled = !state.busy, onClick = { focusManager.clearFocus(); onHaptic(); onMic() },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.turns.isEmpty()) "Speak reminder" else "Speak")
                    }
                }
            }
            "Upcoming" -> PullToRefreshBox(isRefreshing = state.refreshing,
                onRefresh = model::refresh, modifier = Modifier.fillMaxSize().padding(padding)) { LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val upcoming = state.reminders.filter { it.active && it.title.contains(search, true) && (filter == "All" || (filter == "Shared") == (it.sharedId != null)) }
                    .sortedBy { it.nextDueAt }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Personal", "Shared").forEach { label ->
                            FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                        }
                    }
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
                    onLeave = { leaving = reminder }, onPriority = { model.priorityOverride(reminder, it) },
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
                    onLeave = { leaving = reminder }, onPriority = { model.priorityOverride(reminder, it) },
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
            "Friends" -> FriendsScreen(state, model, Modifier.fillMaxSize().padding(padding))
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
            val options by produceState(calendarSnoozeOptions(ZonedDateTime.now())) {
                while (true) {
                    value = calendarSnoozeOptions(ZonedDateTime.now())
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
            OutlinedButton(onClick = { model.snooze(minutes = 60) }, modifier = Modifier.fillMaxWidth()) {
                Text("In 1 hour")
            }
            options.forEach { option ->
                OutlinedButton(onClick = { model.snooze(exactAt = option.at) }, modifier = Modifier.fillMaxWidth()) {
                    Text(option.label)
                }
            }
            Text("A regular reminder may arrive sooner.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = model::closeSnoozeChooser) { Text("Cancel") } })
    val showInviteLoading = state.inviteLoading && state.uid != null &&
        (state.profileReady || (!state.profileLoaded && state.profileChecking))
    if (showInviteLoading || state.inviter != null || state.inviteError != null) {
        val loading = showInviteLoading
        AlertDialog(
            onDismissRequest = { if (!loading) model.closeInvite() },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = !loading, dismissOnClickOutside = !loading),
            title = { Text(state.inviter?.let { "Add $it?" } ?: "Friend invitation") },
            text = {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading invitation…")
                    }
                } else Text(state.inviteError ?: "You will both become friends and can share reminders.")
            },
            confirmButton = {
                if (!loading && state.inviter != null) {
                    TextButton(onClick = model::acceptInvite, enabled = !state.busy) { Text("Add Friend") }
                }
            },
            dismissButton = {
                if (!loading) TextButton(onClick = model::closeInvite) {
                    Text(if (state.inviteError != null) "Close" else "Cancel")
                }
            },
        )
    }
    leaving?.let { reminder -> AlertDialog(onDismissRequest = { leaving = null }, title = { Text("Leave this reminder?") },
        text = { Text("Your alerts stop. Other participants keep the shared reminder.") },
        confirmButton = { TextButton(onClick = { model.leave(reminder); leaving = null }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { leaving = null }) { Text("Cancel") } }) }
    deleting?.let { reminder -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(if (reminder.sharedId != null) "Delete for everyone?" else "Delete reminder?") },
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
    onDelete: () -> Unit, onAction: (String) -> Unit, onLeave: () -> Unit = {}, onPriority: (Boolean?) -> Unit = {}) {
    var menuExpanded by remember { mutableStateOf(false) }
    var scheduleExpanded by rememberSaveable(reminder.id) { mutableStateOf(false) }
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
                        if (reminder.sharedId != null) DropdownMenuItem(text = { Text("Leave shared reminder") },
                            onClick = { menuExpanded = false; onLeave() })
                        DropdownMenuItem(text = { Text("My alerts: normal") }, onClick = { menuExpanded = false; onPriority(false) })
                        DropdownMenuItem(text = { Text("My alerts: priority") }, onClick = { menuExpanded = false; onPriority(true) })
                        if (reminder.sharedId != null) DropdownMenuItem(text = { Text("My alerts: follow shared setting") }, onClick = { menuExpanded = false; onPriority(null) })
                        DropdownMenuItem(text = { Text(if (reminder.sharedId != null) "Delete for everyone" else "Delete") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            if (reminder.sharedId != null) Text("Shared · ${reminder.participantNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (reminder.priority) { Text("Priority alarm", style = MaterialTheme.typography.labelMedium); PriorityAccessButton() }
            if (reminder.notes.isNotBlank()) Text(reminder.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            reminder.nextDueAt?.let { due ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Schedule, contentDescription = null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Next: ${dateLabel(due)}${if (reminder.currentScheduledAt != null) " · follow-up" else ""}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { scheduleExpanded = !scheduleExpanded }) {
                        Icon(if (scheduleExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (scheduleExpanded) "Show fewer times" else "Show more times")
                    }
                }
                if (scheduleExpanded) {
                    val regular = if (reminder.currentScheduledAt != null)
                        Schedule.preview(reminder.rule, reminder.currentScheduledAt, 3)
                    else Schedule.preview(reminder.rule, due, 2)
                    if (regular.isNotEmpty()) Text("${if (reminder.currentScheduledAt != null) "SCHEDULED" else "LATER"} TIMES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    regular.forEach { time ->
                        Text(dateLabel(time), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    PriorityAccessButton()
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                Text(state.email, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSignOut, enabled = !state.signingOut) {
                    if (state.signingOut) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.signingOut) "Signing out…" else "Sign out")
                }
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
            Text("Create reminders by typing or speaking.",
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
        ConversationMessage(user, fromUser = true)
        if (assistant.isNotBlank()) AssistantMessage(assistant)
    }
}

@Composable
internal fun PendingConversationMessage(message: String?) {
    if (message != null) {
        ConversationMessage(message, fromUser = true)
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(ComposerProgress.TRANSCRIBING.label)
            }
        }
    }
}

@Composable
private fun AssistantMessage(text: String) {
    ConversationMessage(text, fromUser = false)
}

@Composable
private fun ConversationMessage(text: String, fromUser: Boolean) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth * 0.8f),
            shape = MaterialTheme.shapes.large,
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (fromUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = if (fromUser) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
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
