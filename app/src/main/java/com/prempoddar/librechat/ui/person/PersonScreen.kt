package com.prempoddar.librechat.ui.person

import com.prempoddar.librechat.ui.design.tap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.crypto.ContactCard
import com.prempoddar.librechat.crypto.SafetyNumber
import com.prempoddar.librechat.engine.ConvIds
import com.prempoddar.librechat.engine.PersonState
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.ago
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.SignalGlyph
import com.prempoddar.librechat.ui.groupedId
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.Mono
import com.prempoddar.librechat.ui.theme.MonoLabel
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.launch

@Composable
fun PersonScreen(peerId: String, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val person by Data.person(peerId).collectAsStateWithLifecycle(null)
    val status by AppGraph.mesh.status.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val s = LocalSignal.current
    var showMine by remember { mutableStateOf(false) }
    var confirmVerify by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<com.prempoddar.librechat.crypto.Identity?>(null) }
    LaunchedEffect(Unit) { AppGraph.mesh.ensureStarted(); me = AppGraph.messenger.myIdentity }
    val scan = rememberContactScanner(expectedPeer = peerId)
    val p = person
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(top = top, bottom = 32.dp)) {
        IconButton(onClick = tap(onBack)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        if (p == null) {
            Text("This person isn't known on this phone.", modifier = Modifier.padding(24.dp))
            return@Column
        }
        val live = System.currentTimeMillis() - p.lastSeen < 90_000
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(p.name, p.peerId, 88.dp, live = live)
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.headlineSmall)
                if (p.verified) Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 6.dp).size(20.dp), tint = s.phosphor)
            }
            Text(groupedId(p.peerId), style = MonoSmall, color = s.faint, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SignalGlyph(if (live) p.hops else Int.MAX_VALUE, status.neighbours[p.peerId]?.rssi)
                Text(
                    if (p.lastSeen > 0) "  ·  heard ${ago(p.lastSeen)} ago" else "  ·  added by code, not heard yet",
                    style = MonoSmall, color = s.faint,
                )
            }
            if (p.state == PersonState.BLOCKED) Text("BLOCKED", style = MonoLabel, color = s.danger, modifier = Modifier.padding(top = 8.dp))
        }

        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = p.state != PersonState.BLOCKED,
                onClick = tap {
                    scope.launch {
                        val conv = AppGraph.messenger.openDirect(p.peerId)
                        if (conv.timerSeconds == 0L && (settings?.defaultTimer ?: 0) > 0 && conv.lastActivity >= System.currentTimeMillis() - 2_000) {
                            AppGraph.messenger.setTimer(conv.id, settings!!.defaultTimer)
                        }
                        onMessage(ConvIds.direct(p.peerId))
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Message") }
            OutlinedButton(
                onClick = tap { scope.launch { if (p.state == PersonState.BLOCKED) AppGraph.messenger.unblock(p.peerId) else AppGraph.messenger.block(p.peerId) } },
                modifier = Modifier.weight(1f),
            ) { Text(if (p.state == PersonState.BLOCKED) "Unblock" else "Block", color = if (p.state == PersonState.BLOCKED) MaterialTheme.colorScheme.primary else s.danger) }
        }

        val mine = me
        if (mine != null) {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(20.dp),
            ) {
                Text("SAFETY NUMBER", style = MonoLabel, color = s.faint)
                Text(
                    if (p.verified) "You've verified ${p.name}. If this number ever changes, you're no longer talking to the same keys."
                    else "Meet ${p.name} in person or call them, and check you both see the same number. Or scan each other's code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                val number = remember(p.identity) { SafetyNumber.of(mine.publicIdentity(), p.publicIdentity).split(" ") }
                number.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { Text(it, fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 19.sp, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = tap(scan), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Text("  Scan theirs")
                    }
                    FilledTonalButton(onClick = tap { showMine = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.QrCode2, null, Modifier.size(18.dp)); Text("  Show mine")
                    }
                }
                TextButton(onClick = tap { if (p.verified) scope.launch { AppGraph.messenger.setVerified(p.peerId, false) } else confirmVerify = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (p.verified) "Clear verification" else "The numbers match: mark verified")
                }
            }
        }
    }

    if (showMine && me != null) {
        AlertDialog(
            onDismissRequest = { showMine = false },
            title = { Text("Your code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val qr = remember { qrBitmap(ContactCard.encode(me!!.publicIdentity(), settings?.name.orEmpty())) }
                    Box(Modifier.clip(MaterialTheme.shapes.medium).background(Color.White).padding(12.dp)) { Image(qr, "Your LibreChat code", Modifier.size(220.dp)) }
                    Text("Let ${p?.name ?: "them"} scan this from their LibreChat.", textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = tap { showMine = false }) { Text("Done") } },
        )
    }
    if (confirmVerify && p != null) {
        AlertDialog(
            onDismissRequest = { confirmVerify = false },
            title = { Text("Mark ${p.name} as verified?") },
            text = { Text("Only do this if you compared all 60 digits with ${p.name} directly, not over a message here.") },
            confirmButton = { TextButton(onClick = tap { confirmVerify = false; scope.launch { AppGraph.messenger.setVerified(p.peerId, true) } }) { Text("Verified") } },
            dismissButton = { TextButton(onClick = tap { confirmVerify = false }) { Text("Cancel") } },
        )
    }
}
