package com.prempoddar.librechat.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Shows LibreChat on the home screen as "Calculator" with a calculator icon, or as itself, by
 * switching which of the two launcher aliases in the manifest is enabled. The launcher can take a
 * few seconds to catch up, and some put the new icon in the app drawer rather than on the home screen.
 */
object Disguise {
    private fun own(context: Context) = ComponentName(context, "com.prempoddar.librechat.Launcher")
    private fun calculator(context: Context) = ComponentName(context, "com.prempoddar.librechat.Calculator")

    fun on(context: Context) =
        context.packageManager.getComponentEnabledSetting(calculator(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(context: Context, on: Boolean) {
        val pm = context.packageManager
        fun state(enabled: Boolean) = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // Enable the new one first, so there's never a moment with no icon at all.
        pm.setComponentEnabledSetting(if (on) calculator(context) else own(context), state(true), PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(if (on) own(context) else calculator(context), state(false), PackageManager.DONT_KILL_APP)
    }
}
