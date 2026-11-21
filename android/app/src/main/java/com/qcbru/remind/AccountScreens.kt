package com.qcbru.remind

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
            Text(state.profileStatus)
            if (state.profileChecking) CircularProgressIndicator()
        } else {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(username, { username = it.take(24) }, label = { Text("Username") }, singleLine = true,
                supportingText = { Text("3–24 letters, numbers, or underscores") })
            Button(onClick = { model.saveProfile(name, username) }, enabled = !state.busy && name.isNotBlank() && username.matches(Regex("[A-Za-z0-9_]{3,24}"))) { Text("Continue") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(state: RemindUiState, model: RemindViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.loadFriends() }
    var removing by remember { mutableStateOf<Map<String, String>?>(null) }
    PullToRefreshBox(isRefreshing = state.friendsRefreshing, onRefresh = model::loadFriends, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Friends", style = MaterialTheme.typography.headlineMedium)
            Text("${state.displayName} · @${state.username}")
            Text("Friends can share reminders with you. Everyone can edit; Done and snooze stay personal.")
            Button(onClick = {
                model.shareFriendLink { url ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Add me on Remind: $url")
                    }, "Share invitation"))
                }
            }, enabled = !state.busy) {
                Text(if (state.preparingFriendLink) "Getting link…" else "Share friend link")
            }
            if (state.friends.isEmpty()) Text("No friends yet. Send an invitation to get started.")
            state.friends.forEach { friend ->
                ListItem(headlineContent = { Text(friend["displayName"].orEmpty()) },
                    supportingContent = { Text("@${friend["username"]}") },
                    trailingContent = { TextButton(onClick = { removing = friend }) { Text("Remove") } })
            }
        }
    }
    removing?.let { friend -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove ${friend["displayName"]}?") },
        text = { Text("Existing shared reminders remain. You can leave those individually.") },
        confirmButton = { TextButton(onClick = { model.removeFriend(friend["uid"]!!); removing = null }) { Text("Remove friend") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
