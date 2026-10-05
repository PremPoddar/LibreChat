package com.prempoddar.librechat.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.util.Log
import com.prempoddar.librechat.crypto.Bytes
import com.prempoddar.librechat.crypto.Bytes.hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Aware links between nearby phones that both support it. Phones find each other through
 * Aware discovery, the lower peer id asks for a data path, and the result becomes one more mesh
 * link — a fast one, which the router prefers for direct messages and media.
 * Everything sent over it is already end-to-end encrypted.
 */
@SuppressLint("MissingPermission") // started only after NEARBY_WIFI_DEVICES / location is granted
class AwareTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myPeerId: String,
    private val onLinkUp: (String) -> Unit,
    private val onLinkDown: (String) -> Unit,
    private val onPacket: (String, ByteArray) -> Unit,
) {
    private class Link(val id: String, val socket: Socket, val outbox: Channel<ByteArray> = Channel(Channel.UNLIMITED)) {
        var reader: Job? = null
        var writer: Job? = null
        var network: ConnectivityManager.NetworkCallback? = null
    }

    private val aware = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && aware?.isAvailable == true

    fun start() {
        if (!isSupported) return
        aware.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                val info = Bytes.unhex(myPeerId)
                s.publish(PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info).build(), publishCallback, null)
                s.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info).build(), subscribeCallback, null)
            }

            override fun onAttachFailed() = Log.w(TAG, "Wi-Fi Aware attach failed").let { }
        }, null)
    }

    fun stop() {
        links.values.forEach { close(it) }
        runCatching { publish?.close(); subscribe?.close(); session?.close() }
        publish = null; subscribe = null; session = null
    }

    fun send(link: String, packet: ByteArray) {
        links[link]?.outbox?.trySend(packet)
    }

    private fun linkId(peer: String) = "aware:$peer"

    // The higher id waits: it opens a server socket when asked and answers with its port.
    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            publish = s
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val peer = message.hex().takeIf { it.length == 16 } ?: return
            if (links.containsKey(linkId(peer)) || !connecting.add(peer)) return
            scope.launch(Dispatchers.IO) { accept(peer, peerHandle) }
        }
    }

    // The lower id asks for the data path once it discovers someone.
    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
            subscribe = s
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: MutableList<ByteArray>?) {
            val peer = serviceSpecificInfo.hex().takeIf { it.length == 16 } ?: return
            if (peer >= myPeerId || links.containsKey(linkId(peer)) || !connecting.add(peer)) return
            subscribe?.sendMessage(peerHandle, 0, Bytes.unhex(myPeerId))
            scope.launch(Dispatchers.IO) { dial(peer, peerHandle) }
        }
    }

    private fun accept(peer: String, handle: PeerHandle) {
        val s = publish ?: return run { connecting.remove(peer) }
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return run { connecting.remove(peer) }
        val spec = WifiAwareNetworkSpecifier.Builder(s, handle).setPskPassphrase(PASSPHRASE).setPort(server.localPort).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val self = this
                scope.launch(Dispatchers.IO) {
                    val socket = runCatching { server.soTimeout = 15_000; server.accept() }.getOrNull()
                    server.close()
                    if (socket == null) connecting.remove(peer) else open(peer, socket, self)
                }
            }

            override fun onUnavailable() {
                server.close(); connecting.remove(peer)
            }

            override fun onLost(network: Network) = links[linkId(peer)]?.let { close(it) } ?: Unit
        }
        request(spec, callback)
    }

    private fun dial(peer: String, handle: PeerHandle) {
        val s = subscribe ?: return run { connecting.remove(peer) }
        val spec = WifiAwareNetworkSpecifier.Builder(s, handle).setPskPassphrase(PASSPHRASE).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (links.containsKey(linkId(peer))) return
                val self = this
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val ip = info.peerIpv6Addr as? Inet6Address ?: return
                val port = info.port.takeIf { it > 0 } ?: return
                scope.launch(Dispatchers.IO) {
                    val socket = runCatching {
                        network.socketFactory.createSocket().apply { connect(InetSocketAddress(ip, port), 10_000) }
                    }.getOrNull()
                    if (socket == null) connecting.remove(peer) else open(peer, socket, self)
                }
            }

            override fun onUnavailable() {
                connecting.remove(peer)
            }

            override fun onLost(network: Network) = links[linkId(peer)]?.let { close(it) } ?: Unit
        }
        request(spec, callback)
    }

    private fun request(spec: WifiAwareNetworkSpecifier, callback: ConnectivityManager.NetworkCallback) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        runCatching { connectivity.requestNetwork(request, callback, CONNECT_TIMEOUT_MS) }
    }

    private fun open(peer: String, socket: Socket, callback: ConnectivityManager.NetworkCallback) {
        val link = Link(linkId(peer), socket).apply { network = callback }
        links[link.id] = link
        connecting.remove(peer)
        // Length-prefixed packets in both directions.
        link.writer = scope.launch(Dispatchers.IO) {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            runCatching {
                for (p in link.outbox) {
                    out.writeInt(p.size); out.write(p); out.flush()
                }
            }
            close(link)
        }
        link.reader = scope.launch(Dispatchers.IO) {
            val input = DataInputStream(socket.getInputStream().buffered())
            runCatching {
                while (true) {
                    val n = input.readInt()
                    if (n !in 1..MAX_PACKET) break
                    val p = ByteArray(n).also { input.readFully(it) }
                    onPacket(link.id, p)
                }
            }
            close(link)
        }
        onLinkUp(link.id)
    }

    private fun close(link: Link) {
        if (links.remove(link.id) == null) return
        link.outbox.close()
        runCatching { link.socket.close() }
        link.network?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        onLinkDown(link.id)
    }

    companion object {
        private const val TAG = "LibreChat"
        private const val SERVICE = "librechat"
        // Contents are already end-to-end encrypted; Aware's own encryption is an extra layer.
        private const val PASSPHRASE = "librechat-mesh-v1"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val MAX_PACKET = 4 * 1024 * 1024

        fun supported(context: Context) =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }
}
