package com.prempoddar.librechat

import android.app.Application
import com.prempoddar.librechat.android.MeshController
import com.prempoddar.librechat.android.Notifier
import com.prempoddar.librechat.data.ChatDatabase
import com.prempoddar.librechat.data.EncryptedFiles
import com.prempoddar.librechat.data.RoomStore
import com.prempoddar.librechat.data.SettingsRepository
import com.prempoddar.librechat.engine.Messenger

/** The app's long-lived objects, wired by hand. */
object AppGraph {
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    val settings by lazy { SettingsRepository(app) }
    val database by lazy { ChatDatabase.open(app) }
    val dao by lazy { database.dao() }
    val files by lazy { EncryptedFiles(app) }
    val store by lazy { RoomStore(dao) { settings.current().name.ifBlank { "LibreChat user" } } }
    val notifier by lazy { Notifier(app) }
    val mesh: MeshController by lazy {
        lateinit var controller: MeshController
        val messenger = Messenger(store, files, out = { link, bytes -> controller.outlet()(link, bytes) })
        controller = MeshController(app, messenger)
        controller
    }
    val messenger: Messenger get() = mesh.messenger

    /** Work that must finish even if the screen that started it closes, like sending a photo. */
    val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
}
