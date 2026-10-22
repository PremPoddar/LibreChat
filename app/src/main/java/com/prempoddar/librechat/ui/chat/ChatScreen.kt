package com.prempoddar.librechat.ui.chat

import com.prempoddar.librechat.ui.design.tap
import com.prempoddar.librechat.engine.Messenger
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.android.Permissions
import com.prempoddar.librechat.data.toEntity
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.ConvIds
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.engine.MsgKind
import com.prempoddar.librechat.engine.Person
import com.prempoddar.librechat.protocol.MediaKind
import com.prempoddar.librechat.ui.ConvTimers
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.clock
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.Reach
import com.prempoddar.librechat.ui.components.pathTo
import com.prempoddar.librechat.ui.components.rememberReach
import com.prempoddar.librechat.ui.ago
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.components.SignalGlyph
import com.prempoddar.librechat.ui.components.Ticks
import com.prempoddar.librechat.ui.media.Photos
import com.prempoddar.librechat.ui.media.VoicePlayer
import com.prempoddar.librechat.ui.media.VoiceRecorder
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.Mono
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
private const val DELETE_FOR_EVERYONE_MS = 24L * 60 * 60 * 1000

@Composable
fun ChatScreen(convId: String, onBack: () -> Unit, onOpenPerson: (String) -> Unit, onGroupInfo: (String) -> Unit, onOpenChat: (String) -> Unit = {}) {
    val kind = ConvIds.kind(convId)
    val conv by Data.conversation(convId).collectAsStateWithLifecycle(null)
    val messages by Data.messages(convId).collectAsStateWithLifecycle(emptyList())
    val person by (if (kind == ConvKind.DIRECT) Data.person(ConvIds.peerOf(convId)) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsStateWithLifecycle(null)
    val group by (if (kind == ConvKind.GROUP) Data.group(ConvIds.groupOf(convId)) else kotlinx.coroutines.flow.flowOf(null))
        .collectAsStateWithLifecycle(null)
    val people by Data.people().collectAsStateWithLifecycle(emptyList())
    val names = remember(people) { people.associate { it.peerId to it.name } }
    val scope = rememberCoroutineScope()
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }

    DisposableEffect(convId) {
        AppGraph.mesh.openConversation = convId
        AppGraph.notifier.cancel(convId)
        onDispose { if (AppGraph.mesh.openConversation == convId) AppGraph.mesh.openConversation = null }
    }
    LaunchedEffect(messages.size) {
        AppGraph.mesh.ensureStarted()
        AppGraph.messenger.markRead(convId)
    }

    val c = conv
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Header(convId, kind, c, person, group?.members?.size, onBack, onOpenPerson, onGroupInfo, Modifier.padding(top = top))
        if (c?.request == true && person != null) RequestBar(person!!)
        if (kind == ConvKind.DIRECT) {
            val old = ConvIds.peerOf(convId)
            val successor by Data.successor(old).collectAsStateWithLifecycle(null)
            successor?.let { new -> MovedBar(old, new, c?.title.orEmpty(), onOpenPerson, onMoved = { onOpenChat(ConvIds.direct(new)) }) }
        }

        val byId = remember(messages) { messages.associateBy { it.id } }
        val listState = rememberLazyListState()
        val items = remember(messages) { messages.asReversed() }
        LaunchedEffect(messages.lastOrNull()?.id) { if (listState.firstVisibleItemIndex < 3) listState.animateScrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items.forEachIndexed { i, m ->
                val older = items.getOrNull(i + 1)
                val first = older == null || older.sender != m.sender || older.kind == MsgKind.SYSTEM || m.at - older.at > 5 * 60_000
                item(key = m.id) {
                    Bubble(m, first, kind, names, byId[m.replyTo], onLongPress = { actionsFor = it }, onSwipeReply = { replyTo = it })
                }
                if (older == null || !sameDay(older.at, m.at)) item(key = "day-${m.id}") { DayLabel(m.at) }
            }
            item(key = "banner") { EncryptionBanner(kind, person, onVerify = { person?.let { onOpenPerson(it.peerId) } }) }
        }
        Composer(convId, kind, replyTo, names, onClearReply = { replyTo = null })
    }

    actionsFor?.let { m -> MessageActions(m, kind, onDismiss = { actionsFor = null }, onReply = { replyTo = m }) }
}

@Composable
private fun Header(
    convId: String, kind: ConvKind, conv: Conversation?, person: Person?, members: Int?,
    onBack: () -> Unit, onOpenPerson: (String) -> Unit, onGroupInfo: (String) -> Unit, modifier: Modifier,
) {
    val s = LocalSignal.current
    val status by AppGraph.mesh.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var timerDialog by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = tap(onBack)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Row(
            Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable {
                when (kind) {
                    ConvKind.DIRECT -> person?.let { onOpenPerson(it.peerId) }
                    ConvKind.GROUP -> onGroupInfo(ConvIds.groupOf(convId))
                    else -> Unit
                }
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind == ConvKind.PUBLIC) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CellTower, null, tint = s.phosphor, modifier = Modifier.size(20.dp))
                }
            } else {
                Avatar(conv?.title ?: "", convId, 38.dp, group = kind == ConvKind.GROUP, online = person?.let { rememberReach(it).online })
            }
            Gap(w = 12.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conv?.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (person?.verified == true) Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 4.dp).size(15.dp), tint = s.phosphor)
                }
                when (kind) {
                    ConvKind.DIRECT -> person?.let { p ->
                        val live = now - p.lastSeen < 90_000
                        // How messages reach them right now: straight to their phone, hopping through
                        // others (and which), or held until they're back in range.
                        val near = status.neighbours[p.peerId]
                        val reach = rememberReach(p)
                        val people by Data.people().collectAsStateWithLifecycle(emptyList())
                        val names = remember(people) { people.associate { it.peerId to it.name } }
                        val (label, colour) = when (reach) {
                            is Reach.Direct -> "${pathTo(reach, p.name, names)} · DIRECT · ${if (reach.wifi) "WI-FI" else "BLUETOOTH"}" to s.phosphor
                            is Reach.Mesh -> "${pathTo(reach, p.name, names)} · MESH" to s.amber
                            Reach.Away -> "OFFLINE · SEEN ${ago(p.lastSeen).uppercase()} · MESSAGES WAIT" to s.faint
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SignalGlyph(if (live) p.hops else Int.MAX_VALUE, near?.rssi)
                            Text("  $label", style = MonoSmall, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ConvKind.GROUP -> Text("${members ?: 0} members", style = MonoSmall, color = s.faint)
                    ConvKind.PUBLIC -> Text("EVERYONE IN RANGE", style = MonoSmall, color = s.faint)
                }
            }
        }
        if ((conv?.timerSeconds ?: 0) > 0) {
            Icon(Icons.Rounded.Timer, "Disappearing: ${ConvTimers.label(conv!!.timerSeconds)}", tint = s.amber, modifier = Modifier.padding(end = 4.dp).size(20.dp))
        }
        Box {
            IconButton(onClick = tap { menu = true }) { Icon(Icons.Rounded.MoreVert, "Chat options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (kind == ConvKind.DIRECT && person != null) DropdownMenuItem(text = { Text("Profile & verify") }, onClick = tap { menu = false; onOpenPerson(person.peerId) })
                if (kind == ConvKind.GROUP) DropdownMenuItem(text = { Text("Group info") }, onClick = tap { menu = false; onGroupInfo(ConvIds.groupOf(convId)) })
                if (kind != ConvKind.PUBLIC) DropdownMenuItem(text = { Text("Disappearing messages") }, onClick = tap { menu = false; timerDialog = true })
                conv?.let { cv ->
                    val muted = cv.mutedUntil > now
                    DropdownMenuItem(text = { Text(if (muted) "Unmute" else "Mute") }, onClick = tap {
                        menu = false
                        scope.launch { AppGraph.dao.putConversation(cv.copy(mutedUntil = if (muted) 0 else Long.MAX_VALUE).toEntity()) }
                    })
                    DropdownMenuItem(text = { Text(if (cv.pinned) "Unpin" else "Pin") }, onClick = tap {
                        menu = false
                        scope.launch { AppGraph.dao.putConversation(cv.copy(pinned = !cv.pinned).toEntity()) }
                    })
                }
                if (kind == ConvKind.DIRECT && person != null) DropdownMenuItem(text = { Text("Block", color = s.danger) }, onClick = tap { menu = false; blockDialog = true })
            }
        }
    }
    if (timerDialog) {
        AlertDialog(
            onDismissRequest = { timerDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    Text("New messages in this chat delete themselves on both phones after:", style = MaterialTheme.typography.bodyMedium)
                    ConvTimers.choices.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable {
                            timerDialog = false
                            scope.launch { AppGraph.messenger.setTimer(convId, t) }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = conv?.timerSeconds == t, onClick = null)
                            Text(ConvTimers.label(t), modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = tap { timerDialog = false }) { Text("Close") } },
        )
    }
    if (blockDialog && person != null) {
        AlertDialog(
            onDismissRequest = { blockDialog = false },
            title = { Text("Block ${person.name}?") },
            text = { Text("Their messages and requests are dropped. Your phone still relays their packets for others, unread.") },
            confirmButton = { TextButton(onClick = tap { blockDialog = false; scope.launch { AppGraph.messenger.block(person.peerId) } }) { Text("Block", color = s.danger) } },
            dismissButton = { TextButton(onClick = tap { blockDialog = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Someone with this chat's name turned up with new keys, as after reinstalling: offer to carry the
 * chat over, but only on the user's word, since anyone can pick any name.
 */
@Composable
private fun MovedBar(old: String, new: String, name: String, onOpenPerson: (String) -> Unit, onMoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = LocalSignal.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text("Is this $name on a new install?", style = MaterialTheme.typography.titleSmall)
        Text(
            "A phone called $name is nearby with a new identity, which happens when LibreChat is reinstalled. " +
                "Messages sent here go to the old identity and won't arrive. Move this chat over if you're sure it's them; " +
                "compare safety numbers or scan their code to check.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = tap { scope.launch { AppGraph.messenger.continueWith(old, new); onMoved() } }) { Text("Move chat") }
            OutlinedButton(onClick = tap { onOpenPerson(new) }) { Text("Check") }
            TextButton(onClick = tap { scope.launch { AppGraph.messenger.keepSeparate(old) } }) { Text("Not them", color = s.faint) }
        }
    }
}

@Composable
private fun RequestBar(person: Person) {
    val scope = rememberCoroutineScope()
    val s = LocalSignal.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text("${person.name} wants to chat", style = MaterialTheme.typography.titleSmall)
        Text(
            "They won't see that you've read their messages until you accept. Replying also accepts.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = tap { scope.launch { AppGraph.messenger.accept(person.peerId) } }) { Text("Accept") }
            OutlinedButton(onClick = tap { scope.launch { AppGraph.messenger.block(person.peerId) } }) { Text("Block", color = s.danger) }
        }
    }
}

@Composable
private fun EncryptionBanner(kind: ConvKind, person: Person?, onVerify: () -> Unit) {
    val s = LocalSignal.current
    val (icon, text) = when (kind) {
        ConvKind.PUBLIC -> Icons.Rounded.CellTower to "Public: anyone in range can read this, and phones pass it on. Every message is signed, so names can't be faked."
        ConvKind.GROUP -> Icons.Rounded.Lock to "End-to-end encrypted group. Only members can read it; relaying phones see scrambled bytes."
        ConvKind.DIRECT -> if (person?.verified == true) {
            Icons.Rounded.Verified to "End-to-end encrypted and verified. You've confirmed these are ${person.name}'s real keys."
        } else {
            Icons.Rounded.Lock to "End-to-end encrypted. Tap to verify ${person?.name ?: "them"} in person so nobody can pretend to be them."
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 24.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface).clickable(enabled = kind == ConvKind.DIRECT, onClick = onVerify).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (kind == ConvKind.PUBLIC) s.amber else s.phosphor, modifier = Modifier.size(18.dp))
        Gap(w = 10.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayLabel(at: Long) {
    val label = remember(at) {
        val day = 86_400_000L
        val today = System.currentTimeMillis() / day
        when (at / day) {
            today -> "TODAY"
            today - 1 -> "YESTERDAY"
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at)).uppercase()
        }
    }
    Text(label, style = MonoSmall, color = LocalSignal.current.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
}

private fun sameDay(a: Long, b: Long) = a / 86_400_000L == b / 86_400_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    m: ChatMessage, first: Boolean, kind: ConvKind, names: Map<String, String>, replied: ChatMessage?,
    onLongPress: (ChatMessage) -> Unit, onSwipeReply: (ChatMessage) -> Unit,
) {
    val s = LocalSignal.current
    if (m.kind == MsgKind.SYSTEM) {
        Text(m.text, style = MonoSmall, color = s.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 24.dp))
        return
    }
    val shape = RoundedCornerShape(
        topStart = if (!m.mine && first) 4.dp else 18.dp, topEnd = if (m.mine && first) 4.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp,
    )
    Column(
        Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 2.dp),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        if (!m.mine && first && kind != ConvKind.DIRECT) {
            Text(names[m.sender] ?: "Someone", style = MaterialTheme.typography.labelMedium, color = s.phosphor, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (m.deleted) MaterialTheme.colorScheme.background else if (m.mine) s.mine else s.theirs)
                .combinedClickable(onClick = {}, onLongClick = { if (!m.deleted) onLongPress(m) }, onDoubleClick = { if (!m.deleted) onSwipeReply(m) })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
          val onBubble = if (m.deleted) MaterialTheme.colorScheme.onSurfaceVariant else if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
          val meta = onBubble.copy(alpha = 0.7f)
          CompositionLocalProvider(LocalContentColor provides onBubble) {
            if (m.deleted) {
                Text("Message deleted", style = MaterialTheme.typography.bodyMedium, color = meta)
            } else {
                replied?.let { r ->
                    Column(
                        Modifier.padding(bottom = 6.dp).clip(MaterialTheme.shapes.small).background(onBubble.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(if (r.mine) "You" else names[r.sender] ?: "Someone", style = MaterialTheme.typography.labelMedium, color = if (m.mine) onBubble else s.phosphor)
                        Text(
                            when { r.deleted -> "Message deleted"; r.kind == MsgKind.PHOTO -> "📷 Photo"; r.kind == MsgKind.VOICE -> "🎤 Voice note"; else -> r.text },
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (m.kind) {
                    MsgKind.PHOTO -> PhotoContent(m)
                    MsgKind.VOICE -> VoiceContent(m)
                    MsgKind.FILE -> FileContent(m)
                    else -> Unit
                }
                if (m.text.isNotEmpty()) Text(m.text, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.expiresAt > 0) Icon(Icons.Rounded.Timer, "Disappears", Modifier.padding(end = 4.dp).size(11.dp), tint = meta)
                Text(clock(m.at), style = MonoSmall, color = meta)
                if (m.mine && !m.deleted) Ticks(m.status, Modifier.padding(start = 4.dp))
            }
          }
        }
        if (m.reactions.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val scope = rememberCoroutineScope()
                m.reactions.forEach { (emoji, who) ->
                    val mine = runCatching { AppGraph.messenger.me in who }.getOrDefault(false)
                    Row(
                        Modifier.clip(CircleShape).background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { scope.launch { AppGraph.messenger.react(m.id, emoji) } }.padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(emoji)
                        if (who.size > 1) Text(" ${who.size}", fontFamily = Mono, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoContent(m: ChatMessage) {
    val media = m.media ?: return
    val s = LocalSignal.current
    val ratio = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 4f / 3f
    val full by produceState<ImageBitmap?>(null, media.path) { value = media.path?.let { withContext(Dispatchers.IO) { Photos.load(it) } } }
    val thumb = remember(media.thumbnail) { Photos.decode(media.thumbnail) }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = 4.dp).widthIn(max = 276.dp).fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium)) {
        val image = full ?: thumb
        if (image != null) {
            Image(
                image, "Photo", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatioSafe(ratio).then(if (full == null) Modifier.blur(12.dp) else Modifier)
                    .clickable(enabled = full != null) { open = true },
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatioSafe(ratio).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        if (full == null && !m.mine) {
            Text(
                "RECEIVING ${media.received}/${media.chunks}", style = MonoSmall, color = s.phosphor,
                modifier = Modifier.align(Alignment.Center).clip(CircleShape).background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    if (open && full != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
            Image(full!!, "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { open = false })
        }
    }
}

/** Very tall or wide photos are cropped to something a chat bubble can hold. */
private fun Modifier.aspectRatioSafe(r: Float) = this.aspectRatio(r.coerceIn(0.6f, 2f))

@Composable
private fun VoiceContent(m: ChatMessage) {
    val media = m.media ?: return
    val s = LocalSignal.current
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        while (playing) {
            position = VoicePlayer.position().toLong()
            if (VoicePlayer.playingPath != media.path) playing = false
            delay(200)
        }
    }
    Row(Modifier.padding(bottom = 4.dp).widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
        val path = media.path
        IconButton(
            enabled = path != null,
            onClick = tap { if (path != null) playing = VoicePlayer.toggle(path) { playing = false } },
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play voice note", tint = s.phosphor) }
        Gap(w = 10.dp)
        Column {
            val shown = if (playing) position else media.durationMs
            Text("%d:%02d".format(shown / 60_000, shown / 1000 % 60), style = MonoSmall)
            if (path == null) Text("RECEIVING ${media.received}/${media.chunks}", style = MonoSmall, color = s.phosphor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(m: ChatMessage, kind: ConvKind, onDismiss: () -> Unit, onReply: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val s = LocalSignal.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            QUICK.forEach { e ->
                Text(
                    e, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.clip(CircleShape).clickable { onDismiss(); scope.launch { AppGraph.messenger.react(m.id, e) } }.padding(10.dp),
                )
            }
        }
        ListItem(headlineContent = { Text("Reply") }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Reply, null) },
            modifier = Modifier.clickable { onDismiss(); onReply() })
        if (m.text.isNotEmpty()) {
            ListItem(headlineContent = { Text("Copy text") }, leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                modifier = Modifier.clickable { onDismiss(); clipboard.setText(AnnotatedString(m.text)) })
        }
        ListItem(headlineContent = { Text("Delete for me") }, leadingContent = { Icon(Icons.Rounded.Delete, null) },
            modifier = Modifier.clickable { onDismiss(); scope.launch { AppGraph.messenger.deleteForMe(m.id) } })
        if (m.mine && System.currentTimeMillis() - m.at < DELETE_FOR_EVERYONE_MS) {
            ListItem(headlineContent = { Text("Delete for everyone", color = s.danger) }, leadingContent = { Icon(Icons.Rounded.Delete, null, tint = s.danger) },
                supportingContent = { Text(if (kind == ConvKind.PUBLIC) "Phones that already have it are asked to remove it" else "Removed from their phone too") },
                modifier = Modifier.clickable { onDismiss(); scope.launch { AppGraph.messenger.deleteForEveryone(m.id) } })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Composer(convId: String, kind: ConvKind, replyTo: ChatMessage?, names: Map<String, String>, onClearReply: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = LocalSignal.current
    var text by rememberSaveable(convId) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recordStart by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val recorder = remember { VoiceRecorder(context) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(context, "Hold the mic to record", Toast.LENGTH_SHORT).show()
    }
    // Preparing and sending carry on in the background even if this chat is closed meanwhile.
    fun sendPhoto(uri: Uri) {
        val caption = text.trim()
        text = ""
        val reply = replyTo?.id
        onClearReply()
        AppGraph.work.launch {
            val error = runCatching {
                val photo = com.prempoddar.librechat.ui.media.Photos.prepare(context, uri) ?: error("That photo couldn't be opened")
                AppGraph.messenger.sendMedia(convId, MediaKind.PHOTO, "image/jpeg", photo.bytes, photo.thumbnail, photo.width, photo.height, caption = caption, replyTo = reply)
            }.exceptionOrNull()
            if (error != null) withContext(Dispatchers.Main) {
                Toast.makeText(context, error.message?.takeIf { it.length < 80 } ?: "The photo couldn't be sent", Toast.LENGTH_LONG).show()
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::sendPhoto) }
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> shot?.let { if (ok) sendPhoto(it) }; shot = null }
    fun openCamera() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".camera", java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg"))
        shot = uri
        runCatching { takePhoto.launch(uri) }.onFailure { Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show() }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else Toast.makeText(context, "Allow the camera to take photos here", Toast.LENGTH_SHORT).show()
    }
    var photoMenu by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val caption = text.trim()
        text = ""
        val reply = replyTo?.id
        onClearReply()
        AppGraph.work.launch {
            val error = runCatching {
                val resolver = context.contentResolver
                var name = "file"
                var size = -1L
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
                }
                val limit = (if (kind == ConvKind.PUBLIC) Messenger.MAX_PUBLIC_CHUNKS else Messenger.MAX_CHUNKS).toLong() * Messenger.CHUNK_SIZE - 64
                if (size > limit) error("That's too big to send here (up to ${limit / 1_000_000} MB)")
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("That file couldn't be opened")
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                AppGraph.messenger.sendMedia(convId, MediaKind.FILE, mime, bytes, ByteArray(0), caption = caption, replyTo = reply, name = name)
            }.exceptionOrNull()
            if (error != null) withContext(Dispatchers.Main) {
                Toast.makeText(context, error.message?.takeIf { it.length < 90 } ?: "The file couldn't be sent", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = System.currentTimeMillis() - recordStart
            if (elapsed >= VoiceRecorder.MAX_MS) break
            delay(200)
        }
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(3.dp, 32.dp).background(s.phosphor))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Replying to ${if (r.mine) "yourself" else names[r.sender] ?: "them"}", style = MaterialTheme.typography.labelMedium, color = s.phosphor)
                    Text(r.text.ifEmpty { if (r.kind == MsgKind.PHOTO) "📷 Photo" else "🎤 Voice note" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = tap(onClearReply)) { Icon(Icons.Rounded.Close, "Cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            if (!recording) {
                Box {
                    IconButton(onClick = tap { photoMenu = true }) { Icon(Icons.Rounded.AddCircle, "Send a photo or file") }
                    DropdownMenu(photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(text = { Text("Gallery") }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, onClick = tap {
                            photoMenu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) }, onClick = tap {
                            photoMenu = false
                            if (Permissions.has(context, Manifest.permission.CAMERA)) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                        })
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }, onClick = tap {
                            photoMenu = false; pickFile.launch(arrayOf("*/*"))
                        })
                    }
                }
            }
            if (recording) {
                Row(Modifier.weight(1f).height(52.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(s.danger))
                    Text(
                        "  %d:%02d  ·  release to send".format(elapsed / 60_000, elapsed / 1000 % 60),
                        style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TextField(
                    value = text, onValueChange = { text = it.take(4_000) },
                    placeholder = { Text(if (kind == ConvKind.PUBLIC) "Message everyone nearby" else "Message") },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
            Gap(w = 6.dp)
            if (text.isNotBlank() || kind == ConvKind.PUBLIC) {
                IconButton(
                    enabled = text.isNotBlank(),
                    onClick = tap {
                        val t = text.trim()
                        text = ""
                        val reply = replyTo?.id
                        onClearReply()
                        scope.launch { AppGraph.messenger.sendText(convId, t, reply) }
                    },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                // Hold to record a voice note; lift to send, drag off to cancel.
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(if (recording) s.danger else MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(convId) {
                            detectTapGestures(onPress = { offset ->
                                if (!Permissions.has(context, Manifest.permission.RECORD_AUDIO)) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                if (!recorder.start()) return@detectTapGestures
                                recordStart = System.currentTimeMillis()
                                recording = true
                                val released = tryAwaitRelease()
                                recording = false
                                if (!released) {
                                    recorder.cancel()
                                    return@detectTapGestures
                                }
                                val (bytes, ms) = recorder.stop() ?: run {
                                    Toast.makeText(context, "Hold to record", Toast.LENGTH_SHORT).show()
                                    return@detectTapGestures
                                }
                                val reply = replyTo?.id
                                onClearReply()
                                scope.launch { AppGraph.messenger.sendMedia(convId, MediaKind.VOICE, "audio/mp4", bytes, ByteArray(0), durationMs = ms, replyTo = reply) }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Mic, "Hold to record a voice note", tint = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
    }
}

/** A file sent as a file: its name and size; tap to open it with another app once it has arrived. */
@Composable
private fun FileContent(m: ChatMessage) {
    val media = m.media ?: return
    val context = LocalContext.current
    val s = LocalSignal.current
    val path = media.path
    val name = media.name.ifBlank { "File" }
    Row(
        Modifier.padding(bottom = 4.dp).widthIn(min = 220.dp, max = 300.dp).clip(RoundedCornerShape(12.dp))
            .background(LocalContentColor.current.copy(alpha = 0.08f))
            .clickable(enabled = path != null) {
                val ext = name.substringAfterLast('.', "bin")
                val file = AppGraph.files.playable(path!!, ext) ?: return@clickable
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".camera", file)
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, media.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Open $name",
                        ),
                    )
                }.onFailure { Toast.makeText(context, "No app here can open this file", Toast.LENGTH_SHORT).show() }
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Description, null, Modifier.size(32.dp), tint = s.phosphor)
        Gap(w = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val size = if (media.size >= 1_000_000) "%.1f MB".format(media.size / 1_000_000f) else "${(media.size / 1000).coerceAtLeast(1)} KB"
            Text(if (path == null && !m.mine) "RECEIVING ${media.received}/${media.chunks} · $size" else size, style = MonoSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}
