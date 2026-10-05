package com.prempoddar.librechat.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.prempoddar.librechat.ui.theme.LocalSignal

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(Tab("Chats", Icons.Outlined.ChatBubbleOutline), Tab("Nearby", Icons.Outlined.Radar), Tab("You", Icons.Outlined.Person))

@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    onNewGroup: () -> Unit,
    onSearch: () -> Unit,
    onBlocked: () -> Unit,
    onSecurity: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val s = LocalSignal.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = s.phosphor),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> ChatsTab(onOpenChat, onNewGroup, onSearch, onNearby = { tab = 1 })
                1 -> NearbyTab(onOpenPerson)
                else -> YouTab(onOpenChat, onBlocked, onSecurity)
            }
        }
    }
}
