package com.qcbru.remind

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ProfileScreen(state: RemindUiState, model: RemindViewModel, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Make yourself known", style = MaterialTheme.typography.headlineMedium)
        Text("Friends see your name and username. Your email stays private.")
        if (!state.profileLoaded) {
            Text("Checking your account…")
            Button(onClick = model::loadProfile, enabled = !state.busy) { Text("Retry") }
        } else {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(username, { username = it.take(24) }, label = { Text("Username") }, singleLine = true,
                supportingText = { Text("3–24 letters, numbers, or underscores") })
            Button(onClick = { model.saveProfile(name, username) }, enabled = !state.busy && name.isNotBlank() && username.matches(Regex("[A-Za-z0-9_]{3,24}"))) { Text("Continue") }
        }
        TextButton(onClick = model::signOut) { Text("Sign out") }
    }
}

@Composable
fun FriendsScreen(state: RemindUiState, model: RemindViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.loadFriends() }
    var removing by remember { mutableStateOf<Map<String, String>?>(null) }
    Column(modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Friends", style = MaterialTheme.typography.headlineMedium)
        Text("${state.displayName} · @${state.username}")
        Text("Friends can share reminders with you. Everyone can edit; Done and snooze stay personal.")
        Button(onClick = model::createInvite, enabled = !state.busy) { Text("Create invitation link") }
        if (state.inviteUrl.isNotBlank()) {
            Text("Expires in 7 days. One friend can accept. A new link replaces this one.")
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Add me on Remind: ${state.inviteUrl}")
                }, "Share invitation"))
            }) { Text("Share link") }
            TextButton(onClick = model::revokeInvite) { Text("Revoke link") }
        }
        if (state.friends.isEmpty()) Text("No friends yet. Send an invitation to get started.")
        state.friends.forEach { friend ->
            ListItem(headlineContent = { Text(friend["displayName"].orEmpty()) },
                supportingContent = { Text("@${friend["username"]}") },
                trailingContent = { TextButton(onClick = { removing = friend }) { Text("Remove") } })
        }
        TextButton(onClick = model::loadFriends) { Text("Refresh friends") }
    }
    removing?.let { friend -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove ${friend["displayName"]}?") },
        text = { Text("Existing shared reminders remain. You can leave those individually.") },
        confirmButton = { TextButton(onClick = { model.removeFriend(friend["uid"]!!); removing = null }) { Text("Remove friend") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
