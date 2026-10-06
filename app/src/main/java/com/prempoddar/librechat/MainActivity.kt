package com.prempoddar.librechat

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prempoddar.librechat.data.AppSettings
import com.prempoddar.librechat.ui.LibreNavHost
import com.prempoddar.librechat.ui.LockScreen
import com.prempoddar.librechat.ui.theme.LibreTheme

class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(true)
    private var hiddenAt = 0L
    /** A conversation to open, from a notification. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRequest.value = intent.getStringExtra(EXTRA_CONVERSATION)
        setContent {
            val settings: AppSettings? by AppGraph.settings.settings.collectAsStateWithLifecycle(null)
            val s = settings ?: return@setContent
            // With the app lock on, LibreChat also stays out of screenshots and the recents preview.
            LaunchedEffect(s.appLock) {
                if (s.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            LibreTheme(s.appearance) {
                if (s.appLock && locked.value) {
                    LockScreen(onUnlock = ::unlock)
                    LaunchedEffect(Unit) { unlock() }
                } else {
                    LibreNavHost(onboarded = s.onboarded, openRequest = openRequest.value, onOpened = { openRequest.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { openRequest.value = it }
    }

    override fun onStop() {
        super.onStop()
        hiddenAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (hiddenAt != 0L && SystemClock.elapsedRealtime() - hiddenAt > RELOCK_AFTER_MS) locked.value = true
    }

    private fun unlock() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked.value = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock LibreChat")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        runCatching { prompt.authenticate(info) }
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        private const val RELOCK_AFTER_MS = 30_000L
    }
}
