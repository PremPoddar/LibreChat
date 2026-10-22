package com.prempoddar.librechat.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.android.MeshStatus
import com.prempoddar.librechat.engine.Person
import kotlinx.coroutines.delay

/** How someone can be reached right now. */
sealed interface Reach {
    /** In range of this phone itself. */
    data class Direct(val wifi: Boolean) : Reach
    /** Through other phones: [via] is the first one on the way (if known), [hops] how many relay. */
    data class Mesh(val via: String?, val hops: Int) : Reach
    data object Away : Reach

    val online: Boolean get() = this !is Away
}

/** Announcements come every 15 s; someone not heard for longer than this has gone. */
const val ONLINE_MS = 90_000L

fun reachOf(p: Person, status: MeshStatus, relays: Map<String, String>, now: Long): Reach {
    status.neighbours[p.peerId]?.let { return Reach.Direct(it.wifi) }
    // Last heard as a neighbour but the link has gone: not reachable that way any more.
    if (now - p.lastSeen > ONLINE_MS || p.hops == 0) return Reach.Away
    return Reach.Mesh(relays[p.peerId], p.hops)
}

/** [person]'s reach, kept current as links come and go and as time passes. */
@Composable
fun rememberReach(person: Person?): Reach {
    val status by AppGraph.mesh.status.collectAsStateWithLifecycle()
    val relays by AppGraph.messenger.relays.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    return person?.let { reachOf(it, status, relays, now) } ?: Reach.Away
}

/** "You → Ben → Asha": the way messages to [name] travel. */
fun pathTo(reach: Reach, name: String, names: Map<String, String>): String = when (reach) {
    is Reach.Direct -> "You → $name"
    is Reach.Mesh -> buildString {
        append("You → ")
        reach.via?.let { append(names[it] ?: "another phone").append(" → ") }
        if (reach.via == null || reach.hops > 1) append("… → ")
        append(name)
    }
    Reach.Away -> ""
}
