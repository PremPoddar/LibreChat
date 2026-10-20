package com.prempoddar.librechat.ui.onboarding

import com.prempoddar.librechat.ui.design.tap
import com.prempoddar.librechat.ui.design.PillTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.android.AwareTransport
import com.prempoddar.librechat.android.Permissions
import com.prempoddar.librechat.ui.components.Radar
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoLabel
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var stay by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { s ->
            when (s) {
                0 -> Welcome { step = 1 }
                1 -> NameStep(name, { name = it }) { step = 2 }
                2 -> PermissionStep { step = 3 }
                else -> BackgroundStep(stay, { stay = it }) {
                    scope.launch {
                        AppGraph.settings.update { it.copy(name = name.trim(), stayInMesh = stay, onboarded = true) }
                        AppGraph.mesh.acquire("ui")
                        onDone()
                    }
                }
            }
        }
    }
}

@Composable
private fun Page(eyebrow: String, title: String, body: String, content: @Composable () -> Unit = {}, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(eyebrow, style = MonoLabel, color = LocalSignal.current.phosphor)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.weight(1f).height(24.dp))
        Button(onClick = tap(onAction), enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(action) }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Radar(Modifier.size(240.dp))
        Spacer(Modifier.height(40.dp))
        Text("LibreChat", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Messages that hop from phone to phone.\nNo internet, no servers, no accounts.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = tap(onNext), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Set up") }
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onNext: () -> Unit) {
    val ok = name.trim().length in 1..32
    Page(
        "STEP 1 OF 3", "What should people nearby see?",
        "Your name goes out with your announcements. Your keys are created on this phone and never leave it.",
        content = {
            PillTextField(
                name, { onName(it.take(32)) }, "Your name",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onNext() }),
            )
        },
        action = "Continue", enabled = ok, onAction = onNext,
    )
}

@Composable
private fun PermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bt = remember(tick) { Permissions.granted(context, Permissions.bluetooth) }
    val awareSupported = remember { AwareTransport.supported(context) }
    val wifi = remember(tick) { Permissions.granted(context, Permissions.wifi) }
    val notify = remember(tick) { Permissions.granted(context, Permissions.notifications) }
    Page(
        "STEP 2 OF 3", "Let LibreChat find phones nearby",
        "Nothing here uses your location. Android groups Bluetooth scanning under these permissions.",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PermissionCard("Nearby devices", "Required. Finds and links with other phones over Bluetooth.", bt) {
                    ask.launch(Permissions.bluetooth.toTypedArray())
                }
                if (awareSupported) {
                    PermissionCard("Wi-Fi Aware", "Optional. Sends photos and voice notes much faster to phones right next to you.", wifi) {
                        ask.launch(Permissions.wifi.toTypedArray())
                    }
                }
                if (Permissions.notifications.isNotEmpty()) {
                    PermissionCard("Notifications", "Optional. Tells you when a message arrives.", notify) {
                        ask.launch(Permissions.notifications.toTypedArray())
                    }
                }
            }
        },
        action = if (bt) "Continue" else "Allow nearby devices",
        onAction = { if (bt) onNext() else ask.launch((Permissions.bluetooth + Permissions.notifications).toTypedArray()) },
    )
}

@Composable
private fun PermissionCard(title: String, body: String, granted: Boolean, onAllow: () -> Unit) {
    val s = LocalSignal.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (granted) s.phosphor.copy(alpha = 0.5f) else s.line, MaterialTheme.shapes.medium)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Text("ON", style = MonoLabel, color = s.phosphor, modifier = Modifier.padding(horizontal = 12.dp))
        else TextButton(onClick = tap(onAllow)) { Text("Allow") }
    }
}

@Composable
private fun BackgroundStep(stay: Boolean, onStay: (Boolean) -> Unit, onDone: () -> Unit) {
    Page(
        "STEP 3 OF 3", "Stay in the mesh?",
        "When LibreChat keeps running in the background you receive messages with the app closed, and your phone passes messages on for others — which is what makes the mesh reach further. It uses a little battery and shows a quiet notification.",
        content = {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Stay in the mesh", style = MaterialTheme.typography.titleMedium)
                    Text("You can change this any time in You → Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(stay, onStay)
            }
        },
        action = "Start", onAction = onDone,
    )
}
