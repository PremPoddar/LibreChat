package com.prempoddar.librechat.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.engine.Person
import com.prempoddar.librechat.engine.PersonState
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.ago
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.components.Radar
import com.prempoddar.librechat.ui.components.RadarBlip
import com.prempoddar.librechat.ui.components.SectionLabel
import com.prempoddar.librechat.ui.components.SignalGlyph
import com.prempoddar.librechat.ui.groupedId
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.delay

private const val LIVE_MS = 90_000L

@Composable
fun NearbyTab(onOpenPerson: (String) -> Unit) {
    val people by Data.people().collectAsStateWithLifecycle(emptyList())
    val status by AppGraph.mesh.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    val visible = people.filter { it.state != PersonState.BLOCKED && it.peerId != runCatching { AppGraph.messenger.me }.getOrNull() }
    val live = visible.filter { now - it.lastSeen < LIVE_MS }
    val direct = live.filter { it.hops == 0 }.sortedByDescending { status.neighbours[it.peerId]?.rssi ?: -100 }
    val relayed = live.filter { it.hops > 0 }.sortedBy { it.hops }
    val earlier = visible.filter { it.lastSeen in 1 until now - LIVE_MS }.sortedByDescending { it.lastSeen }.take(30)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(top = top, bottom = 24.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, top = 12.dp)) {
                Text("Nearby", style = MaterialTheme.typography.headlineMedium)
                MeshChip(Modifier.padding(top = 6.dp))
            }
        }
        item {
            val blips = live.map { p ->
                val angle = ((p.peerId.hashCode().toLong() and 0xFFFF) % 360).toFloat()
                val distance = if (p.hops == 0) {
                    val rssi = status.neighbours[p.peerId]?.rssi ?: -75
                    (0.22f + ((-rssi - 40).coerceIn(0, 55) / 55f) * 0.33f)
                } else {
                    (0.55f + p.hops * 0.09f).coerceAtMost(0.95f)
                }
                RadarBlip(angle, distance, p.hops == 0)
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Radar(Modifier.size(260.dp), blips, sweeping = status.running)
            }
        }
        if (live.isEmpty()) {
            item {
                Text(
                    if (status.running) "Listening… Anyone running LibreChat within Bluetooth range will appear here, and people further away once someone in between relays for them."
                    else "The mesh is off. Check the status above.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        if (direct.isNotEmpty()) item { SectionLabel("In range · ${direct.size}") }
        items(direct, key = { "d" + it.peerId }) { PersonRow(it, status.neighbours[it.peerId]?.rssi, now, onOpenPerson) }
        if (relayed.isNotEmpty()) item { SectionLabel("Through the mesh · ${relayed.size}") }
        items(relayed, key = { "r" + it.peerId }) { PersonRow(it, null, now, onOpenPerson) }
        if (earlier.isNotEmpty()) item { SectionLabel("Seen earlier") }
        items(earlier, key = { "e" + it.peerId }) { PersonRow(it.copy(hops = Int.MAX_VALUE), null, now, onOpenPerson) }
    }
}

@Composable
private fun PersonRow(p: Person, rssi: Int?, now: Long, onOpen: (String) -> Unit) {
    val s = LocalSignal.current
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(p.peerId) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(p.name, p.peerId, 42.dp, live = p.hops != Int.MAX_VALUE)
        Gap(w = 14.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (p.verified) Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 6.dp).size(16.dp), tint = s.phosphor)
            }
            Text("${groupedId(p.peerId)} · ${ago(p.lastSeen, now)}", style = MonoSmall, color = s.faint)
        }
        SignalGlyph(p.hops, rssi)
    }
}
