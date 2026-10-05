package com.prempoddar.librechat.ui.home

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.crypto.ContactCard
import com.prempoddar.librechat.data.AppSettings
import com.prempoddar.librechat.data.ThemeMode
import com.prempoddar.librechat.ui.ConvTimers
import com.prempoddar.librechat.ui.groupedId
import com.prempoddar.librechat.ui.person.qrBitmap
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoLabel
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.launch

@Composable
fun YouTab(onOpenChat: (String) -> Unit, onBlocked: () -> Unit, onSecurity: () -> Unit) {
    val context = LocalContext.current
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<com.prempoddar.librechat.crypto.Identity?>(null) }
    LaunchedEffect(Unit) {
        AppGraph.mesh.ensureStarted()
        me = AppGraph.messenger.myIdentity
    }
    var editName by remember { mutableStateOf(false) }
    val s = LocalSignal.current
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { AppGraph.settings.update(f) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(top = top + 12.dp, bottom = 24.dp),
    ) {
        Text("You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
        val id = me
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(settings.name.ifBlank { "No name" }, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { editName = true }) { Icon(Icons.Rounded.Edit, "Change name", Modifier.size(18.dp)) }
            }
            if (id != null) {
                Text(groupedId(id.peerId.value), style = MonoSmall, color = s.faint)
                val qr = remember(id, settings.name) { qrBitmap(ContactCard.encode(id.publicIdentity(), settings.name)) }
                Box(Modifier.padding(top = 18.dp).clip(MaterialTheme.shapes.medium).background(Color.White).padding(14.dp)) {
                    Image(qr, "Your LibreChat code", Modifier.size(200.dp))
                }
                Text(
                    "Let someone scan this in person to add you and verify that your keys are really yours.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        Group("Mesh") {
            ToggleRow("Stay in the mesh", "Receive and relay messages while LibreChat is closed", settings.stayInMesh) { on -> update { it.copy(stayInMesh = on) } }
        }
        Group("Privacy") {
            val canLock = remember {
                BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
            }
            ToggleRow("App lock", if (canLock) "Fingerprint, face or screen lock to open LibreChat" else "Set a screen lock on this phone first", settings.appLock, enabled = canLock) { on ->
                update { it.copy(appLock = on) }
            }
            var timerMenu by remember { mutableStateOf(false) }
            Box {
                LinkRow("Disappearing messages", "${ConvTimers.label(settings.defaultTimer)} for new chats you start") { timerMenu = true }
                DropdownMenu(timerMenu, onDismissRequest = { timerMenu = false }) {
                    ConvTimers.choices.forEach { t ->
                        DropdownMenuItem(text = { Text(ConvTimers.label(t)) }, onClick = { timerMenu = false; update { it.copy(defaultTimer = t) } })
                    }
                }
            }
            LinkRow("Blocked people", "They can't message you; you still relay for them", onBlocked)
            LinkRow("How LibreChat protects you", "Encryption, relays and what they can see", onSecurity)
        }
        Group("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                options.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(settings.theme == mode, { update { it.copy(theme = mode) } }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                }
            }
            ToggleRow("Notifications", "New messages and requests", settings.notifications) { on -> update { it.copy(notifications = on) } }
        }
        Text(
            "LibreChat · no servers, no accounts, no internet",
            style = MonoSmall, color = s.faint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }

    if (editName) {
        var value by remember { mutableStateOf(settings.name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Your name") },
            text = { OutlinedTextField(value, { value = it.take(32) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    editName = false
                    update { it.copy(name = value.trim()) }
                    scope.launch { AppGraph.messenger.announce() }
                    Toast.makeText(context, "People nearby see the new name with your next announcement", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MonoLabel, color = LocalSignal.current.faint, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp))
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LinkRow(title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = LocalSignal.current.faint)
    }
}
