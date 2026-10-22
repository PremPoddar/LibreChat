package com.prempoddar.librechat.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.MsgKind
import com.prempoddar.librechat.engine.MsgStatus
import com.prempoddar.librechat.ui.chat.Bubble
import com.prempoddar.librechat.ui.design.Appearance
import com.prempoddar.librechat.ui.design.AppearanceScreen
import com.prempoddar.librechat.ui.onboarding.OnboardingScreen
import com.prempoddar.librechat.ui.theme.LIBRE_BRAND
import com.prempoddar.librechat.ui.theme.LibreTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws real screens to build/screens to check light, dark and landscape without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
open class ScreensTest {
    /** Where the pictures go; the tablet runs below draw the same screens into their own folders. */
    protected open val out = "build/screens"

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() = AppGraph.init(ApplicationProvider.getApplicationContext())

    private fun shoot(name: String, appearance: Appearance, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { LibreTheme(appearance) { content() } }
        repeat(8) { Thread.sleep(100); rule.mainClock.advanceTimeBy(250) }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(out).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val light = Appearance(mode = Appearance.Mode.LIGHT)
    private val dark = Appearance(mode = Appearance.Mode.DARK)

    private fun msg(id: String, mine: Boolean, text: String, status: MsgStatus = MsgStatus.READ, replyTo: String? = null, deleted: Boolean = false, kind: MsgKind = MsgKind.TEXT, from: String = "asha") =
        ChatMessage(id, "c", if (mine) "me" else from, mine, kind, text, 1_000L, status, replyTo = replyTo, deleted = deleted)

    @Composable
    private fun Bubbles() {
        val names = mapOf("asha" to "Asha", "ben" to "Ben")
        val meet = msg("4", false, "Meet at the big tent at 9?")
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Bubble(msg("0", false, "Ben joined the group", kind = MsgKind.SYSTEM), true, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("1", false, "Lost you near the main stage \uD83D\uDE05"), true, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("2", true, "I'm by the food trucks, no signal at all"), true, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("3", false, "Same, but this still gets through!", from = "ben"), true, ConvKind.GROUP, names, null, {}, {})
            Bubble(meet, true, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("5", true, "See you there \uD83D\uDC4D", replyTo = "4"), true, ConvKind.GROUP, names, meet, {}, {})
            Bubble(msg("6", true, "Bringing snacks", status = MsgStatus.DELIVERED), false, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("7", false, "Legend \uD83C\uDF89", from = "ben"), true, ConvKind.GROUP, names, null, {}, {})
        }
    }

    @Test fun bubblesLight() = shoot("bubbles-light", light) { Bubbles() }
    @Test fun bubblesDark() = shoot("bubbles-dark", dark) { Bubbles() }
    @Test fun onboardingDark() = shoot("onboarding-dark", dark) { OnboardingScreen({}) }
    @Test fun appearanceDark() = shoot("appearance-dark", dark) { AppearanceScreen(dark, LIBRE_BRAND, {}, {}) }
}
