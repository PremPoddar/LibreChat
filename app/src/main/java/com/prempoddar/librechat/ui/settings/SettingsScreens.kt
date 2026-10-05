package com.prempoddar.librechat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.engine.PersonState
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoLabel
import kotlinx.coroutines.launch

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(top = top, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val people by Data.people().collectAsStateWithLifecycle(emptyList())
    val blocked = people.filter { it.state == PersonState.BLOCKED }
    val scope = rememberCoroutineScope()
    Page("Blocked people", onBack) {
        if (blocked.isEmpty()) Text("Nobody is blocked.", modifier = Modifier.padding(24.dp))
        blocked.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name, p.peerId, 40.dp)
                Gap(w = 12.dp)
                Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { AppGraph.messenger.unblock(p.peerId) } }) { Text("Unblock") }
            }
        }
    }
}

/** Plain-language account of what LibreChat protects and what it can't. */
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val sections = listOf(
        "Your keys" to "LibreChat makes your keys on this phone the first time it runs. They never leave it, and there's no account or server that could hold a copy. Reinstalling gives you a new identity.",
        "Direct messages and groups" to "Encrypted end to end with the same building blocks as Signal: an X3DH key agreement and the Double Ratchet for direct messages, Sender Keys for groups. Each message uses a fresh key, so a key stolen tomorrow can't unlock today's messages. Phones that relay your messages carry scrambled bytes they can't read.",
        "Public chat" to "Readable by anyone in range — that's the point of it. Every public message is signed, so nobody can post under your name.",
        "What relays can see" to "Phones passing a message on can see which mesh id it's from and to (or which group id), its size and when it was sent. They can't see the words, photos, names inside groups, or who reacted to what.",
        "Verifying people" to "Anyone can pick any name. Scanning each other's code in person, or comparing the 60-digit safety number, proves you hold each other's real keys. Verified people get a check mark.",
        "On this phone" to "Messages and keys are stored in a database encrypted with SQLCipher, and photos and voice notes are encrypted on disk. The keys for both are guarded by Android Keystore. App lock adds your fingerprint or screen lock and hides LibreChat from screenshots and the recent-apps view.",
        "What it can't do" to "LibreChat can't hide that you're using it from people nearby, and it can't protect messages on a phone someone has unlocked. The encryption follows Signal's published designs but this implementation hasn't had an independent audit.",
    )
    val s = LocalSignal.current
    Page("How LibreChat protects you", onBack) {
        sections.forEach { (title, body) ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(title.uppercase(), style = MonoLabel, color = s.phosphor)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
