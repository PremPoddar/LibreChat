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
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() = AppGraph.init(ApplicationProvider.getApplicationContext())

    private fun shoot(name: String, appearance: Appearance, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { LibreTheme(appearance) { content() } }
        repeat(8) { Thread.sleep(100); rule.mainClock.advanceTimeBy(250) }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File("build/screens").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val light = Appearance(mode = Appearance.Mode.LIGHT)
    private val dark = Appearance(mode = Appearance.Mode.DARK)

    private fun msg(id: String, mine: Boolean, text: String, status: MsgStatus = MsgStatus.READ, replyTo: String? = null, deleted: Boolean = false, kind: MsgKind = MsgKind.TEXT) =
        ChatMessage(id, "c", if (mine) "me" else "asha", mine, kind, text, 1_000L, status, replyTo = replyTo, deleted = deleted)

    @Composable
    private fun Bubbles() {
        val names = mapOf("asha" to "Asha")
        val first = msg("1", false, "Are you at the station yet?")
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Bubble(msg("0", false, "Asha joined the group", kind = MsgKind.SYSTEM), true, ConvKind.GROUP, names, null, {}, {})
            Bubble(first, true, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("2", true, "Two minutes away, save me a seat", replyTo = "1"), true, ConvKind.GROUP, names, first, {}, {})
            Bubble(msg("3", true, "Sending…", status = MsgStatus.SENDING), false, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("4", true, "Delivered", status = MsgStatus.DELIVERED), false, ConvKind.GROUP, names, null, {}, {})
            Bubble(msg("5", false, "", deleted = true), true, ConvKind.GROUP, names, null, {}, {})
        }
    }

    @Test fun bubblesLight() = shoot("bubbles-light", light) { Bubbles() }
    @Test fun bubblesDark() = shoot("bubbles-dark", dark) { Bubbles() }
    @Test fun onboardingDark() = shoot("onboarding-dark", dark) { OnboardingScreen({}) }
    @Test fun appearanceDark() = shoot("appearance-dark", dark) { AppearanceScreen(dark, LIBRE_BRAND, {}, {}) }
}
