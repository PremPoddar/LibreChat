package com.prempoddar.librechat.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.ui.components.Dot
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoSmall

/** The mesh at a glance, and the fix when it isn't running. */
@Composable
fun MeshChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by AppGraph.mesh.status.collectAsStateWithLifecycle()
    val s = LocalSignal.current
    val (color, text, fix) = when {
        status.bluetoothOff -> Triple(s.amber, "BLUETOOTH OFF · TAP TO TURN ON", { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        status.missingPermission -> Triple(s.amber, "NEARBY PERMISSION NEEDED", {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
        })
        !status.running -> Triple(s.faint, "MESH OFF", null)
        status.neighbours.isEmpty() -> Triple(s.amber, "SEARCHING", null)
        else -> Triple(s.phosphor, "${status.neighbours.size} LINKED${if (status.neighbours.values.any { it.wifi }) " · WI-FI" else ""}", null)
    }
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .then(if (fix != null) Modifier.clickable { fix(); AppGraph.mesh.restart() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, 7.dp)
        Gap(w = 7.dp)
        Text(text, style = MonoSmall, color = color)
    }
}
