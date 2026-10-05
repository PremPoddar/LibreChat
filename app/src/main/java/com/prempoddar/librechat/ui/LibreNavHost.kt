package com.prempoddar.librechat.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.prempoddar.librechat.ui.chat.ChatScreen
import com.prempoddar.librechat.ui.groups.GroupInfoScreen
import com.prempoddar.librechat.ui.groups.NewGroupScreen
import com.prempoddar.librechat.ui.home.HomeScreen
import com.prempoddar.librechat.ui.onboarding.OnboardingScreen
import com.prempoddar.librechat.ui.person.PersonScreen
import com.prempoddar.librechat.ui.search.SearchScreen
import com.prempoddar.librechat.ui.settings.BlockedScreen
import com.prempoddar.librechat.ui.settings.SecurityScreen

@Composable
fun LibreNavHost(onboarded: Boolean, openRequest: String?, onOpened: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openRequest, onboarded) {
        if (openRequest != null && onboarded) {
            nav.navigate("chat/$openRequest") { launchSingleTop = true }
            onOpened()
        }
    }
    val chat = { id: String -> nav.navigate("chat/$id") }
    val person = { id: String -> nav.navigate("person/$id") }
    NavHost(
        nav, startDestination = if (onboarded) "home" else "onboarding",
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable("onboarding") { OnboardingScreen(onDone = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } }) }
        composable("home") {
            HomeScreen(
                onOpenChat = chat, onOpenPerson = person,
                onNewGroup = { nav.navigate("newgroup") }, onSearch = { nav.navigate("search") },
                onBlocked = { nav.navigate("blocked") }, onSecurity = { nav.navigate("security") },
            )
        }
        composable("chat/{id}") { e ->
            ChatScreen(
                convId = e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() },
                onOpenPerson = person, onGroupInfo = { nav.navigate("group/$it") },
            )
        }
        composable("person/{id}") { e -> PersonScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onMessage = chat) }
        composable("newgroup") { NewGroupScreen(onBack = { nav.popBackStack() }, onCreated = { nav.popBackStack(); chat(it) }) }
        composable("group/{id}") { e -> GroupInfoScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onOpenPerson = person) }
        composable("search") { SearchScreen(onBack = { nav.popBackStack() }, onOpenChat = chat) }
        composable("blocked") { BlockedScreen(onBack = { nav.popBackStack() }) }
        composable("security") { SecurityScreen(onBack = { nav.popBackStack() }) }
    }
}
