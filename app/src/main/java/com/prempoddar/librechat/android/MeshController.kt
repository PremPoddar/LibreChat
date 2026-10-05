package com.prempoddar.librechat.android

import android.content.Context
import com.prempoddar.librechat.engine.Messenger
import com.prempoddar.librechat.mesh.LinkSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Neighbour(val peerId: String, val rssi: Int?, val wifi: Boolean)

data class MeshStatus(
    val running: Boolean = false,
    val bluetoothOff: Boolean = false,
    val missingPermission: Boolean = false,
    val wifiAware: Boolean = false,
    val neighbours: Map<String, Neighbour> = emptyMap(),
)

/**
 * Runs the mesh while anything needs it (the app on screen, or the background service): starts
 * the transports, feeds packets to the messenger in arrival order, and announces/ticks on a timer.
 */
class MeshController(private val context: Context, val messenger: Messenger) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val holders = mutableSetOf<String>()
    private val startLock = Mutex()
    private var started = false
    private var ble: BleTransport? = null
    private var aware: AwareTransport? = null
    private var loops: Job? = null
    private val inbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    /** The conversation on screen, so it doesn't also raise a notification. */
    @Volatile var openConversation: String? = null

    init {
        scope.launch {
            for ((link, bytes) in inbound) runCatching { messenger.onPacket(link, bytes) }
        }
    }

    /** Makes sure keys exist; safe to call more than once. */
    suspend fun ensureStarted() = startLock.withLock {
        if (!started) {
            messenger.start()
            started = true
        }
    }

    @Synchronized fun acquire(holder: String) {
        holders += holder
        if (holders.size == 1) scope.launch { startMesh() }
    }

    @Synchronized fun release(holder: String) {
        if (holders.remove(holder) && holders.isEmpty()) scope.launch { stopMesh() }
    }

    /** Called after permissions are granted or Bluetooth is switched on. */
    fun restart() {
        scope.launch {
            stopMesh()
            if (synchronized(this@MeshController) { holders.isNotEmpty() }) startMesh()
        }
    }

    private suspend fun startMesh() {
        ensureStarted()
        if (ble != null) return
        if (!Permissions.granted(context, Permissions.bluetooth)) {
            _status.value = MeshStatus(missingPermission = true)
            return
        }
        val me = messenger.me
        val b = BleTransport(context, scope, me, ::linkUp, ::linkDown, ::packet)
        if (!b.isEnabled) {
            _status.value = MeshStatus(bluetoothOff = true)
            return
        }
        ble = b
        b.start()
        if (AwareTransport.supported(context) && Permissions.granted(context, Permissions.wifi)) {
            aware = AwareTransport(context, scope, me, ::linkUp, ::linkDown, ::packet).also { it.start() }
        }
        messenger.wifiAware = aware?.isSupported == true
        _status.value = MeshStatus(running = true, wifiAware = messenger.wifiAware)
        loops = scope.launch {
            var n = 0
            while (isActive) {
                runCatching { messenger.announce() }
                if (n++ % 2 == 0) runCatching { messenger.tick() }
                refresh()
                delay(ANNOUNCE_MS)
            }
        }
    }

    private suspend fun stopMesh() {
        loops?.cancel(); loops = null
        ble?.stop(); ble = null
        aware?.stop(); aware = null
        _status.value = MeshStatus()
    }

    private fun linkUp(link: String) {
        scope.launch {
            messenger.onLinkUp(link, if (link.startsWith("aware:")) LinkSpeed.WIFI else LinkSpeed.BLE)
            delay(1_500) // their announcement usually follows within a moment
            refresh()
        }
    }

    private fun linkDown(link: String) {
        scope.launch {
            messenger.onLinkDown(link)
            refresh()
        }
    }

    private fun packet(link: String, bytes: ByteArray) {
        inbound.trySend(link to bytes)
    }

    private fun route(link: String, bytes: ByteArray) {
        if (link.startsWith("aware:")) aware?.send(link, bytes) else ble?.send(link, bytes)
    }

    private fun refresh() {
        val b = ble ?: return
        val map = messenger.neighbours().mapValues { (peer, links) ->
            val rssi = links.firstNotNullOfOrNull { b.linkAddress(it)?.let { addr -> b.rssi[addr] } }
            Neighbour(peer, rssi, links.any { it.startsWith("aware:") })
        }
        _status.value = _status.value.copy(neighbours = map)
    }

    /** For the Messenger's constructor: where its packets go. */
    fun outlet(): (String, ByteArray) -> Unit = ::route

    companion object {
        const val ANNOUNCE_MS = 15_000L
    }
}
