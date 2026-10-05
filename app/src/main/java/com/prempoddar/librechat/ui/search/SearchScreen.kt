package com.prempoddar.librechat.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.ui.Data
import com.prempoddar.librechat.ui.ago
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlinx.coroutines.flow.flowOf

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by (if (q.trim().length >= 2) Data.search(q.trim()) else flowOf(emptyList())).collectAsStateWithLifecycle(emptyList())
    val convs by Data.conversations().collectAsStateWithLifecycle(emptyList())
    val titles = convs.associate { it.id to it.title }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = top)) {
        Row {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            TextField(
                q, { q = it }, placeholder = { Text("Search messages") }, singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(focus),
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background),
            )
        }
        LazyColumn {
            items(results, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().clickable { onOpenChat(m.convId) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row {
                        Text(titles[m.convId] ?: "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(ago(m.at).uppercase(), style = MonoSmall, color = LocalSignal.current.faint)
                    }
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (q.trim().length >= 2 && results.isEmpty()) item { Text("No messages match.", modifier = Modifier.padding(24.dp)) }
        }
    }
}
