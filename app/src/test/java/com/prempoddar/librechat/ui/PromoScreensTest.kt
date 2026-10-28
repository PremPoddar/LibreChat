package com.prempoddar.librechat.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.android.MeshStatus
import com.prempoddar.librechat.android.Neighbour
import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.data.ChatDatabase
import com.prempoddar.librechat.data.toEntity
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.ConvIds
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.engine.Group
import com.prempoddar.librechat.engine.MsgKind
import com.prempoddar.librechat.engine.MsgStatus
import com.prempoddar.librechat.engine.Person
import com.prempoddar.librechat.engine.PersonState
import com.prempoddar.librechat.ui.chat.ChatScreen
import com.prempoddar.librechat.ui.design.Appearance
import com.prempoddar.librechat.ui.home.HomeScreen
import com.prempoddar.librechat.ui.theme.LibreTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The real home, chat and nearby screens filled with made-up people and messages, for the store
 * listing and the promo video. The encrypted database is swapped for an in-memory one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PromoScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dark = Appearance(mode = Appearance.Mode.DARK)
    private val light = Appearance(mode = Appearance.Mode.LIGHT)

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppGraph.init(app)
        if (AppGraph.testDatabase == null) AppGraph.testDatabase = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java).allowMainThreadQueries().build()
        if (!seeded) runBlocking {
            seeded = true
            AppGraph.settings.update { it.copy(name = "Prem") }
            AppGraph.mesh.ensureStarted()
            val me = AppGraph.messenger.me
            val dao = AppGraph.dao
            listOf(asha, ben, cy, leo, maya).forEach { dao.putPerson(it.toEntity()) }
            fun m(id: String, conv: String, from: String, text: String, minutesAgo: Long, status: MsgStatus = MsgStatus.READ, kind: MsgKind = MsgKind.TEXT, replyTo: String? = null, reactions: Map<String, List<String>> = emptyMap()) =
                ChatMessage(id, conv, from, from == me, kind, text, now - minutesAgo * 60_000, if (from == me) status else MsgStatus.SEEN, replyTo = replyTo, reactions = reactions)
            val group = ConvIds.group(gid)
            dao.putGroup(Group(gid, "Festival crew", me, listOf(me, asha.peerId, ben.peerId, cy.peerId, leo.peerId), 3).toEntity())
            val chats = listOf(
                Conversation(group, ConvKind.GROUP, "Festival crew", pinned = true, lastActivity = now - 60_000, unread = 3, preview = "Leo: Legend 🎉"),
                Conversation(ConvIds.direct(asha.peerId), ConvKind.DIRECT, "Asha", lastActivity = now - 4 * 60_000, unread = 1, preview = "Found the car! Level 2 🚗"),
                Conversation(ConvIds.PUBLIC, ConvKind.PUBLIC, "Public", lastActivity = now - 9 * 60_000, unread = 12, preview = "Cy: Water refill point by gate C is open"),
                Conversation(ConvIds.direct(ben.peerId), ConvKind.DIRECT, "Ben", lastActivity = now - 26 * 60_000, preview = "Voice note"),
                Conversation(ConvIds.direct(cy.peerId), ConvKind.DIRECT, "Cy", lastActivity = now - 52 * 60_000, timerSeconds = 86_400, preview = "Disappearing messages are on"),
                Conversation(ConvIds.direct(maya.peerId), ConvKind.DIRECT, "Maya", lastActivity = now - 190 * 60_000, preview = "See you Sunday!"),
            )
            chats.forEach { dao.putConversation(it.toEntity()) }
            listOf(
                m("g0", group, me, "Leo joined the group", 40, kind = MsgKind.SYSTEM),
                m("g1", group, asha.peerId, "Lost you all near the main stage 😅", 14),
                m("g2", group, me, "I'm by the food trucks, zero signal", 13),
                m("g3", group, ben.peerId, "Same, but this still gets through!", 12),
                m("g4", group, cy.peerId, "Meet at the big tent at 9?", 6, reactions = mapOf("👍" to listOf(asha.peerId, ben.peerId))),
                m("g5", group, me, "See you there 👍", 5, replyTo = "g4"),
                m("g6", group, me, "Bringing snacks", 2, status = MsgStatus.DELIVERED),
                m("g7", group, leo.peerId, "Legend 🎉", 1),
                m("a1", ConvIds.direct(asha.peerId), me, "Where did we park?", 9),
                m("a2", ConvIds.direct(asha.peerId), asha.peerId, "No idea, my phone died for a bit 😬", 8),
                m("a3", ConvIds.direct(asha.peerId), me, "Mine says you're two phones away, I'll wait by the gate", 6),
                m("a4", ConvIds.direct(asha.peerId), asha.peerId, "Found the car! Level 2 🚗", 4),
                m("a5", ConvIds.direct(asha.peerId), me, "On my way", 3, status = MsgStatus.READ),
            ).forEach { dao.putMessage(it.toEntity()) }
        }
        // Asha and Ben are in Bluetooth range; Cy and Leo are reached through the mesh.
        @Suppress("UNCHECKED_CAST")
        val status = AppGraph.mesh.javaClass.getDeclaredField("_status").apply { isAccessible = true }.get(AppGraph.mesh) as MutableStateFlow<MeshStatus>
        status.value = MeshStatus(running = true, neighbours = mapOf(
            asha.peerId to Neighbour(asha.peerId, -58, false), ben.peerId to Neighbour(ben.peerId, -71, false),
        ))
    }


    private fun shoot(name: String, appearance: Appearance, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { LibreTheme(appearance) { content() } }
        repeat(10) { Thread.sleep(150); rule.mainClock.advanceTimeBy(250) }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/screens").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun home() = HomeScreen({}, {}, {}, {}, {}, {}, {}, {})

    @Test fun chatsDark() = shoot("promo-chats-dark", dark) { home() }
    @Test fun chatsLight() = shoot("promo-chats-light", light) { home() }
    @Test fun groupDark() = shoot("promo-group-dark", dark) { ChatScreen(ConvIds.group(gid), {}, {}, {}) }
    @Test fun directLight() = shoot("promo-direct-light", light) { ChatScreen(ConvIds.direct(asha.peerId), {}, {}, {}) }
    @Test fun nearbyDark() = shoot("promo-nearby-dark", dark) { com.prempoddar.librechat.ui.home.NearbyTab {} }
    @Test fun directDark() = shoot("promo-direct-dark", dark) { ChatScreen(ConvIds.direct(asha.peerId), {}, {}, {}) }

    private companion object {
        var seeded = false
        val now = System.currentTimeMillis()

        private fun person(name: String, hops: Int, minutesAgo: Long, state: PersonState = PersonState.ACCEPTED, verified: Boolean = false): Person {
            val identity = Identity.generate()
            return Person(identity.peerId.value, identity.publicIdentity().toBytes(), name, lastSeen = now - minutesAgo * 60_000, hops = hops, state = state, verified = verified)
        }

        private val asha = person("Asha", 0, 0, verified = true)
        private val ben = person("Ben", 0, 0)
        private val cy = person("Cy", 2, 0)
        private val leo = person("Leo", 1, 0)
        private val maya = person("Maya", 0, 180)
        private val gid = "5f2a9c41d07e33b8"
    }
}
