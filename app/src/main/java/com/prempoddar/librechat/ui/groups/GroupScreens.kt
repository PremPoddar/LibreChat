package com.prempoddar.librechat.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.engine.ConvIds
import com.prempoddar.librechat.engine.Person
import com.prempoddar.librechat.engine.PersonState
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.components.Avatar
import com.prempoddar.librechat.ui.components.Gap
import com.prempoddar.librechat.ui.components.SectionLabel
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoLabel
import kotlinx.coroutines.launch

/** People who can be put in a group: anyone known and not blocked. */
@Composable
private fun candidates(exclude: Set<String> = emptySet()): List<Person> {
    val people by Data.people().collectAsStateWithLifecycle(emptyList())
    val me = remember { runCatching { AppGraph.messenger.me }.getOrNull() }
    return people.filter { it.state != PersonState.BLOCKED && it.peerId != me && it.peerId !in exclude }
        .sortedWith(compareByDescending<Person> { it.state == PersonState.ACCEPTED || it.verified }.thenByDescending { it.lastSeen })
}

@Composable
private fun PickRow(p: Person, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.name, p.peerId, 40.dp, live = System.currentTimeMillis() - p.lastSeen < 90_000)
        Gap(w = 12.dp)
        Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (p.verified) Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(end = 8.dp).size(16.dp), tint = LocalSignal.current.phosphor)
        Checkbox(checked, { onToggle() })
    }
}

@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val people = candidates()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = top)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("New group", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            name, { name = it.take(40) }, label = { Text("Group name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        SectionLabel("Members · ${picked.size}")
        LazyColumn(Modifier.weight(1f)) {
            items(people, key = { it.peerId }) { p ->
                PickRow(p, p.peerId in picked) { if (p.peerId in picked) picked.remove(p.peerId) else picked.add(p.peerId) }
            }
            if (people.isEmpty()) item {
                Text("Nobody known yet. People appear here once they've been in range or you've scanned their code.", modifier = Modifier.padding(24.dp))
            }
        }
        Button(
            enabled = name.isNotBlank() && picked.isNotEmpty(),
            onClick = {
                scope.launch {
                    val gid = AppGraph.messenger.createGroup(name, picked.toList())
                    val t = AppGraph.settings.current().defaultTimer
                    if (t > 0) AppGraph.messenger.setTimer(ConvIds.group(gid), t)
                    onCreated(ConvIds.group(gid))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).heightIn(min = 52.dp),
        ) { Text("Create group") }
    }
}

@Composable
fun GroupInfoScreen(groupId: String, onBack: () -> Unit, onOpenPerson: (String) -> Unit) {
    val group by Data.group(groupId).collectAsStateWithLifecycle(null)
    val people by Data.people().collectAsStateWithLifecycle(emptyList())
    val byId = remember(people) { people.associateBy { it.peerId } }
    val scope = rememberCoroutineScope()
    val s = LocalSignal.current
    var me by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { AppGraph.mesh.ensureStarted(); me = AppGraph.messenger.me }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val g = group
    val admin = g != null && g.admin == me && g.active
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(top = top, bottom = 32.dp)) {
        item { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
        if (g == null) return@LazyColumn
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(g.name, ConvIds.group(g.id), 84.dp, group = true)
                Text(g.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                if (!g.active) Text("YOU'RE NO LONGER IN THIS GROUP", style = MonoLabel, color = s.danger, modifier = Modifier.padding(top = 6.dp))
                if (admin) TextButton(onClick = { renaming = true }) { Text("Rename") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Members · ${g.members.size}", Modifier.weight(1f))
                if (admin) IconButton(onClick = { adding = true }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) { Icon(Icons.Rounded.PersonAdd, "Add members") }
            }
        }
        items(g.members, key = { it }) { id ->
            val p = byId[id]
            Row(
                Modifier.fillMaxWidth().clickable(enabled = id != me) { onOpenPerson(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p?.name ?: "You", id, 40.dp)
                Gap(w = 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (id == me) "You" else p?.name ?: "Someone", style = MaterialTheme.typography.titleSmall)
                    if (id == g.admin) Text("ADMIN", style = MonoLabel, color = s.phosphor)
                }
                if (p?.verified == true) Icon(Icons.Rounded.Verified, "Verified", Modifier.size(16.dp), tint = s.phosphor)
                if (admin && id != me) IconButton(onClick = { scope.launch { AppGraph.messenger.updateGroup(g.id, remove = listOf(id)) } }) {
                    Icon(Icons.Rounded.PersonRemove, "Remove ${p?.name ?: "member"}", tint = s.danger)
                }
            }
        }
        if (g.active) item {
            TextButton(onClick = { leaving = true }, modifier = Modifier.padding(16.dp)) { Text("Leave group", color = s.danger) }
        }
    }

    if (adding && g != null) {
        val picked = remember { mutableStateListOf<String>() }
        val options = candidates(exclude = g.members.toSet())
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add members") },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(options, key = { it.peerId }) { p -> PickRow(p, p.peerId in picked) { if (p.peerId in picked) picked.remove(p.peerId) else picked.add(p.peerId) } } } },
            confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { adding = false; scope.launch { AppGraph.messenger.updateGroup(g.id, add = picked.toList()) } }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (renaming && g != null) {
        var value by remember { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename group") },
            text = { OutlinedTextField(value, { value = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { renaming = false; scope.launch { AppGraph.messenger.updateGroup(g.id, name = value) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (leaving && g != null) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave ${g.name}?") },
            text = { Text("You'll stop receiving its messages. The others get new keys so they can't be read with yours.") },
            confirmButton = { TextButton(onClick = { leaving = false; scope.launch { AppGraph.messenger.leaveGroup(g.id) } }) { Text("Leave", color = s.danger) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
}
