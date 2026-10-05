package com.prempoddar.librechat.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prempoddar.librechat.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Rejoins the mesh after a restart if the user chose to stay in it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val s = AppGraph.settings.current()
            if (s.onboarded && s.stayInMesh && Permissions.granted(context, Permissions.bluetooth)) MeshService.start(context)
            pending.finish()
        }
    }
}
