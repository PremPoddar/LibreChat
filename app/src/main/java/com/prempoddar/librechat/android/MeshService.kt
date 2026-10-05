package com.prempoddar.librechat.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.MainActivity
import com.prempoddar.librechat.R
import kotlinx.coroutines.launch

/** Keeps this phone in the mesh — receiving and relaying — while LibreChat isn't on screen. */
class MeshService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        promote(0)
        AppGraph.mesh.acquire(HOLDER)
        lifecycleScope.launch {
            AppGraph.mesh.status.collect { promote(it.neighbours.size) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_LEAVE) {
            lifecycleScope.launch {
                AppGraph.settings.update { it.copy(stayInMesh = false) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppGraph.mesh.release(HOLDER)
        super.onDestroy()
    }

    private fun promote(nearby: Int) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val leave = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE)
        val text = when (nearby) {
            0 -> "Listening for people nearby"
            1 -> "Linked with 1 phone · relaying for the mesh"
            else -> "Linked with $nearby phones · relaying for the mesh"
        }
        val n = NotificationCompat.Builder(this, Notifier.MESH)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("In the mesh")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Leave mesh", leave)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, ID, n, type) }.onFailure { stopSelf() }
    }

    companion object {
        private const val ID = 7
        private const val HOLDER = "service"
        private const val ACTION_LEAVE = "com.prempoddar.librechat.LEAVE_MESH"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
