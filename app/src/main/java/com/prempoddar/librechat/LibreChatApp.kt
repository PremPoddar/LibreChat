package com.prempoddar.librechat

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.prempoddar.librechat.android.MeshService
import com.prempoddar.librechat.android.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class LibreChatApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var inForeground = false
        private set

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.files.cleanUp()

        // The mesh runs while LibreChat is on screen…
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                scope.launch { if (AppGraph.settings.current().onboarded) AppGraph.mesh.acquire("ui") }
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                AppGraph.mesh.release("ui")
            }
        })

        // …and in the background service when the user chose to stay in it.
        scope.launch {
            AppGraph.settings.settings.combine(AppGraph.mesh.status) { s, _ -> s.onboarded && s.stayInMesh }
                .distinctUntilChanged()
                .collect { stay ->
                    if (stay && Permissions.granted(this@LibreChatApp, Permissions.bluetooth)) MeshService.start(this@LibreChatApp)
                    else MeshService.stop(this@LibreChatApp)
                }
        }

        scope.launch {
            AppGraph.mesh.ensureStarted()
            AppGraph.messenger.events.collect { e ->
                if (e !is com.prempoddar.librechat.engine.MessengerEvent.NewMessage) return@collect
                val s = AppGraph.settings.current()
                val open = inForeground && AppGraph.mesh.openConversation == e.conversation.id
                val muted = e.conversation.mutedUntil > System.currentTimeMillis()
                if (s.notifications && !open && !muted) AppGraph.notifier.show(e)
            }
        }
    }
}
