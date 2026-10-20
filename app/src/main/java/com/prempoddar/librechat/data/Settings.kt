package com.prempoddar.librechat.data

import androidx.datastore.preferences.core.intPreferencesKey
import com.prempoddar.librechat.ui.design.Appearance
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val name: String = "",
    val appearance: Appearance = Appearance(),
    val stayInMesh: Boolean = true,
    val appLock: Boolean = false,
    val notifications: Boolean = true,
    val defaultTimer: Long = 0,
    val onboarded: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("name")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val accent = intPreferencesKey("accent")
        val black = booleanPreferencesKey("pure_black")
        val look = stringPreferencesKey("look")
        val mesh = booleanPreferencesKey("mesh")
        val lock = booleanPreferencesKey("lock")
        val notify = booleanPreferencesKey("notify")
        val timer = longPreferencesKey("timer")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current() = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = transform(p.toSettings())
            p[K.name] = n.name
            p[K.theme] = n.appearance.mode.name
            p[K.dynamic] = n.appearance.dynamic
            p[K.black] = n.appearance.pureBlack
            p[K.look] = n.appearance.extras()
            n.appearance.accent?.let { p[K.accent] = it } ?: p.remove(K.accent)
            p[K.mesh] = n.stayInMesh
            p[K.lock] = n.appLock
            p[K.notify] = n.notifications
            p[K.timer] = n.defaultTimer
            p[K.onboarded] = n.onboarded
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            name = this[K.name] ?: d.name,
            appearance = Appearance(
                mode = this[K.theme]?.let { runCatching { Appearance.Mode.valueOf(it) }.getOrNull() } ?: Appearance.Mode.SYSTEM,
                dynamic = this[K.dynamic] ?: false,
                accent = this[K.accent],
                pureBlack = this[K.black] ?: false,
            ).withExtras(this[K.look]),
            stayInMesh = this[K.mesh] ?: d.stayInMesh,
            appLock = this[K.lock] ?: d.appLock,
            notifications = this[K.notify] ?: d.notifications,
            defaultTimer = this[K.timer] ?: d.defaultTimer,
            onboarded = this[K.onboarded] ?: d.onboarded,
        )
    }
}
