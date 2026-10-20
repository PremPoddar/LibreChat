package com.prempoddar.librechat.ui.home

import com.prempoddar.librechat.ui.design.tap
import androidx.compose.material.icons.rounded.Backup
import android.widget.Toast
import androidx.biometric.BiometricManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.crypto.ContactCard
import com.prempoddar.librechat.data.AppSettings
import com.prempoddar.librechat.ui.ConvTimers
import com.prempoddar.librechat.ui.design.PillTextField
import com.prempoddar.librechat.ui.design.SettingsGroup
import com.prempoddar.librechat.ui.design.SettingsRow
import com.prempoddar.librechat.ui.design.SwitchRow
import com.prempoddar.librechat.ui.design.TonalCard
import com.prempoddar.librechat.ui.groupedId
import com.prempoddar.librechat.ui.person.qrBitmap
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.launch

@Composable
fun YouTab(onBlocked: () -> Unit, onSecurity: () -> Unit, onAppearance: () -> Unit, onAbout: () -> Unit, onBackup: () -> Unit = {}) {
    val context = LocalContext.current
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<com.prempoddar.librechat.crypto.Identity?>(null) }
    LaunchedEffect(Unit) {
        AppGraph.mesh.ensureStarted()
        me = AppGraph.messenger.myIdentity
    }
    var editName by remember { mutableStateOf(false) }
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { AppGraph.settings.update(f) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(top = top + 12.dp, bottom = 24.dp),
    ) {
        Text("You", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val id = me
        TonalCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        settings.name.ifBlank { "No name" }, style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f, fill = false),
                    )
                    FilledTonalIconButton(onClick = tap { editName = true }) { Icon(Icons.Rounded.Edit, "Change name", Modifier.size(18.dp)) }
                }
                if (id != null) {
                    Text(groupedId(id.peerId.value), style = MonoSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    val qr = remember(id, settings.name) { qrBitmap(ContactCard.encode(id.publicIdentity(), settings.name)) }
                    // QR codes need dark-on-light to scan, so the code always sits on white.
                    Box(Modifier.padding(top = 18.dp).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(16.dp)) {
                        Image(qr, "Your LibreChat code", Modifier.size(200.dp))
                    }
                    Text(
                        "Let someone scan this in person to add you and verify that your keys are really yours.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }

        SettingsGroup("Mesh") {
            SwitchRow("Stay in the mesh", "Receive and relay messages while LibreChat is closed", Icons.Rounded.WifiTethering, settings.stayInMesh) { on -> update { it.copy(stayInMesh = on) } }
            SwitchRow("Notifications", "New messages and requests", Icons.Rounded.Notifications, settings.notifications) { on -> update { it.copy(notifications = on) } }
        }
        SettingsGroup("Privacy") {
            val canLock = remember {
                BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
            }
            SwitchRow(
                "App lock", if (canLock) "Fingerprint, face or screen lock to open LibreChat" else "Set a screen lock on this phone first",
                Icons.Rounded.Lock, settings.appLock, enabled = canLock,
            ) { on -> update { it.copy(appLock = on) } }
            var timerMenu by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                SettingsRow("Disappearing messages", "${ConvTimers.label(settings.defaultTimer)} for new chats you start", Icons.Rounded.Timer, onClick = { timerMenu = true }) { Chevron() }
                // Anchored at the row's right edge, next to the arrow that opens it.
                Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp)) {
                DropdownMenu(timerMenu, onDismissRequest = { timerMenu = false }) {
                    ConvTimers.choices.forEach { t ->
                        DropdownMenuItem(text = { Text(ConvTimers.label(t)) }, onClick = tap { timerMenu = false; update { it.copy(defaultTimer = t) } })
                    }
                }
                }
            }
            SettingsRow("Blocked people", "They can't message you; you still relay for them", Icons.Rounded.Block, onClick = onBlocked) { Chevron() }
            SettingsRow("How LibreChat protects you", "Encryption, relays and what they can see", Icons.Rounded.Shield, onClick = onSecurity) { Chevron() }
        }
        SettingsGroup("General") {
            SettingsRow("Back up and restore", "Move LibreChat to a new phone, sealed with a passphrase", Icons.Rounded.Backup, onClick = onBackup) { Chevron() }
            SettingsRow("Look & feel", "Theme, colours, font, shapes and haptics", Icons.Rounded.Palette, onClick = onAppearance) { Chevron() }
            SettingsRow("About LibreChat", "Version, licences", Icons.Rounded.Info, onClick = onAbout) { Chevron() }
        }
        Text(
            "No servers, no accounts, no internet",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }

    if (editName) {
        var value by remember { mutableStateOf(settings.name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("Your name") },
            text = {
                PillTextField(value, { value = it.take(32) }, "Name", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = tap {
                    editName = false
                    update { it.copy(name = value.trim()) }
                    scope.launch { AppGraph.messenger.announce() }
                    Toast.makeText(context, "People nearby see the new name with your next announcement", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = tap { editName = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
