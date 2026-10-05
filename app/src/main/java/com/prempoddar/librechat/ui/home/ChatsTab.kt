package com.prempoddar.librechat.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.data.toEntity
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.ago
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.person.rememberContactScanner
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.Mono
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTab(onOpenChat: (String) -> Unit, onNewGroup: () -> Unit, onSearch: () -> Unit, onNearby: () -> Unit) {
    val convs by Data.conversations().collectAsStateWithLifecycle(emptyList())
    var showRequests by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val scan = rememberContactScanner()
    val requests = convs.filter { it.request }
    val shown = if (showRequests) requests else convs.filter { !it.request }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(contentPadding = PaddingValues(top = top, bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (showRequests) "Requests" else "LibreChat", style = MaterialTheme.typography.headlineMedium)
                        MeshChip(Modifier.padding(top = 6.dp))
                    }
                    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "Search messages") }
                }
            }
            if (requests.isNotEmpty() || showRequests) {
                item {
                    TextButton(onClick = { showRequests = !showRequests }, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                        Text(if (showRequests) "← Back to chats" else "${requests.size} message request${if (requests.size > 1) "s" else ""}")
                    }
                }
            }
            items(shown, key = { it.id }) { c -> ConversationRow(c, onOpenChat) }
            if (shown.size <= 1 && !showRequests) {
                item {
                    Column(Modifier.padding(24.dp)) {
                        Text("Nobody to talk to yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Say something in Public, or open Nearby to see who's in range and message them.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { sheet = true },
            icon = { Icon(Icons.Outlined.Edit, null) }, text = { Text("New") },
            containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = LocalSignal.current.phosphor,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            ListItem(headlineContent = { Text("Message someone nearby") }, leadingContent = { Icon(Icons.Outlined.Radar, null) },
                modifier = Modifier.clickable { sheet = false; onNearby() })
            ListItem(headlineContent = { Text("New group") }, leadingContent = { Icon(Icons.Outlined.GroupAdd, null) },
                modifier = Modifier.clickable { sheet = false; onNewGroup() })
            ListItem(headlineContent = { Text("Scan a contact's code") }, supportingContent = { Text("Adds and verifies them in one step") },
                leadingContent = { Icon(Icons.Outlined.QrCodeScanner, null) },
                modifier = Modifier.clickable { sheet = false; scan() }.padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: Conversation, onOpen: (String) -> Unit) {
    val s = LocalSignal.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val muted = c.mutedUntil > now
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { menu = true })
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (c.kind == ConvKind.PUBLIC) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CellTower, null, tint = s.phosphor)
                }
            } else {
                Avatar(c.title, c.id, 48.dp, group = c.kind == ConvKind.GROUP)
            }
            Gap(w = 14.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (c.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(start = 6.dp).size(14.dp), tint = s.faint)
                    if (muted) Icon(Icons.Rounded.NotificationsOff, "Muted", Modifier.padding(start = 4.dp).size(14.dp), tint = s.faint)
                    if (c.timerSeconds > 0) Icon(Icons.Rounded.Timer, "Disappearing messages", Modifier.padding(start = 4.dp).size(14.dp), tint = s.faint)
                }
                Text(
                    if (c.kind == ConvKind.PUBLIC && c.preview.isEmpty()) "Everyone in range can read this" else c.preview.ifEmpty { "No messages yet" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(ago(c.lastActivity).uppercase(), style = MonoSmall, color = if (c.unread > 0) s.phosphor else s.faint)
                if (c.unread > 0) {
                    Box(
                        Modifier.padding(top = 6.dp).clip(CircleShape).background(if (muted) s.faint else s.phosphor).padding(horizontal = 7.dp, vertical = 1.dp),
                    ) { Text(if (c.unread > 99) "99+" else c.unread.toString(), fontFamily = Mono, color = MaterialTheme.colorScheme.background, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            val update = { f: (Conversation) -> Conversation -> scope.launch { AppGraph.dao.putConversation(f(c).toEntity()) }; menu = false }
            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin") }, onClick = { update { it.copy(pinned = !it.pinned) } })
            if (muted) {
                DropdownMenuItem(text = { Text("Unmute") }, onClick = { update { it.copy(mutedUntil = 0) } })
            } else {
                DropdownMenuItem(text = { Text("Mute for 8 hours") }, onClick = { update { it.copy(mutedUntil = now + 8 * 3_600_000L) } })
                DropdownMenuItem(text = { Text("Mute for a week") }, onClick = { update { it.copy(mutedUntil = now + 7 * 86_400_000L) } })
                DropdownMenuItem(text = { Text("Mute always") }, onClick = { update { it.copy(mutedUntil = Long.MAX_VALUE) } })
            }
            DropdownMenuItem(text = { Text("Clear history") }, onClick = { menu = false; confirm = "clear" })
            if (c.kind != ConvKind.PUBLIC) DropdownMenuItem(text = { Text("Delete chat") }, onClick = { menu = false; confirm = "delete" })
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "Clear history?" else "Delete this chat?") },
            text = { Text("This removes the messages from this phone only. Others keep their copies.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        AppGraph.dao.mediaIn(c.id).forEach { m -> m.mediaPath?.let(AppGraph.files::delete) }
                        AppGraph.dao.clearConversation(c.id)
                        if (what == "delete") AppGraph.dao.deleteConversation(c.id)
                        else AppGraph.dao.putConversation(c.copy(preview = "", unread = 0).toEntity())
                    }
                }) { Text(if (what == "clear") "Clear" else "Delete", color = s.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
