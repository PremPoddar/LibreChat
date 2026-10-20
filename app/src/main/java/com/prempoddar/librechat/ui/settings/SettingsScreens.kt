package com.prempoddar.librechat.ui.settings

import com.prempoddar.librechat.ui.design.tap
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
import androidx.compose.runtime.setValue
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
            IconButton(onClick = tap(onBack)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
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
                TextButton(onClick = tap { scope.launch { AppGraph.messenger.unblock(p.peerId) } }) { Text("Unblock") }
            }
        }
    }
}

/** Plain-language account of what LibreChat protects and what it can't. */
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val sections = listOf(
        "Your keys" to "LibreChat makes your keys on this phone the first time it runs. They leave it only in a backup you make, sealed with your passphrase, and there's no account or server that could hold a copy. Reinstalling without a backup gives you a new identity.",
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

/**
 * Backing up LibreChat to move it to a new phone: everything sealed with a passphrase; and
 * restoring such a backup, which replaces LibreChat on this phone.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var again by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var restoreUri by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null) }
    var restorePass by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var restored by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = "Sealing your backup…"
        scope.launch {
            note = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { com.prempoddar.librechat.backup.ChatBackup.backup(context, it, pass.toCharArray()) }
                "Backup saved. Keep the passphrase safe: without it nobody, including you, can open it."
            }.getOrElse { "The backup couldn't be saved: ${it.message}" }
            pass = ""; again = ""; busy = null
        }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { restoreUri = it }
    val s = LocalSignal.current
    Page("Back up and restore", onBack) {
        Text("Back up", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your identity, contacts, chats, photos, voice notes and settings, in one file sealed with a passphrase. Use it to move LibreChat to a new phone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (12 or more characters)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        androidx.compose.material3.OutlinedTextField(again, { again = it }, label = { Text("Same passphrase again") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            isError = again.isNotEmpty() && again != pass)
        androidx.compose.material3.Button(
            enabled = busy == null && pass.length >= 12 && pass == again,
            onClick = tap { save.launch("LibreChat backup $stamp.lcbackup") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Save backup") }
        Gap(28.dp)
        Text("Restore", style = MaterialTheme.typography.titleMedium)
        Text(
            "Replaces LibreChat on this phone with a backup, then restarts it. Messages people sent you after the backup was made can't be read on the restored copy: each chat's keys move on with every message. Ask them to send those again.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedButton(enabled = busy == null, onClick = tap { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Choose a backup…") }
        busy?.let { Text(it, color = s.phosphor, modifier = Modifier.padding(top = 16.dp)) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
    }
    restoreUri?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (busy == null) { restoreUri = null; restorePass = "" } },
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text("Everything LibreChat has on this phone now is replaced by the backup.")
                    androidx.compose.material3.OutlinedTextField(restorePass, { restorePass = it }, label = { Text("Backup passphrase") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = busy == null && restorePass.isNotEmpty(), onClick = tap {
                    busy = "Checking and restoring…"
                    scope.launch {
                        val r = runCatching {
                            context.contentResolver.openInputStream(uri)!!.use { com.prempoddar.librechat.backup.ChatBackup.restore(context, it, restorePass.toCharArray()) }
                        }
                        busy = null; restorePass = ""; restoreUri = null
                        if (r.isSuccess) restored = true else note = r.exceptionOrNull()?.message ?: "That backup couldn't be restored"
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(enabled = busy == null, onClick = tap { restoreUri = null; restorePass = "" }) { Text("Cancel") } },
        )
    }
    if (restored) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Restored") },
            text = { Text("LibreChat will now restart on your backup.") },
            confirmButton = { TextButton(onClick = tap { com.prempoddar.librechat.backup.ChatBackup.restart(context) }) { Text("Restart") } },
        )
    }
}
