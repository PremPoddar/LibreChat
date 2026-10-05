package com.prempoddar.librechat.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.prempoddar.librechat.crypto.Bytes
import com.prempoddar.librechat.crypto.Bytes.hex
import com.prempoddar.librechat.protocol.Fragmenter
import com.prempoddar.librechat.protocol.Reassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Bluetooth Low Energy side of the mesh. Every phone is both a GATT server (advertising the
 * LibreChat service) and a client (scanning for it). The lower peer id connects, so two phones
 * normally share one link; the other side connects too if nothing happens for a while.
 * Packets are split into frames that fit the negotiated MTU and sent one at a time.
 */
@SuppressLint("MissingPermission") // callers check Bluetooth permissions before start()
class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val myPeerId: String,
    private val onLinkUp: (String) -> Unit,
    private val onLinkDown: (String) -> Unit,
    private val onPacket: (String, ByteArray) -> Unit,
) {
    private class Link(
        val id: String,
        val address: String,
        var mtu: Int = 23,
        val outbox: Channel<ByteArray> = Channel(Channel.UNLIMITED),
        val reassembler: Reassembler = Reassembler(),
        var pending: CompletableDeferred<Boolean>? = null,
        var gatt: BluetoothGatt? = null,
        var writer: Job? = null,
    )

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val frameIds = AtomicInteger()
    /** MTUs negotiated before a link exists, by Bluetooth address. */
    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val pendingMtu = ConcurrentHashMap<String, Int>()

    /** Signal strength of nearby LibreChat phones by Bluetooth address. */
    val rssi = ConcurrentHashMap<String, Int>()
    /** Peer id advertised by each Bluetooth address. */
    val peerOfAddress = ConcurrentHashMap<String, String>()

    fun linkAddress(link: String): String? = links[link]?.address

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        if (!isEnabled) return
        startServer()
        startAdvertising()
        startScanning()
    }

    fun stop() {
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        links.values.forEach { drop(it) }
        runCatching { server?.close() }
        server = null
    }

    fun send(link: String, packet: ByteArray) {
        val l = links[link] ?: return
        // Leave room for the ATT header (3 bytes).
        val frames = Fragmenter.split(frameIds.incrementAndGet() and 0xFFFF, packet, maxOf(l.mtu - 3, Fragmenter.HEADER + 16))
        frames.forEach { l.outbox.trySend(it) }
    }

    // Server (peripheral)

    private fun startServer() {
        val char = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        char.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(char) }
        characteristic = char
        server = manager.openGattServer(context, serverCallback)?.apply { addService(service) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) links.remove(serverLink(device))?.let { drop(it) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            links[serverLink(device)]?.mtu = mtu
            serverMtu[device.address] = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            // The client subscribing to notifications is when the link is ready both ways.
            if (descriptor.uuid == CCCD_UUID && value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                val id = serverLink(device)
                if (links.containsKey(id)) return
                val link = Link(id, device.address, mtu = serverMtu[device.address] ?: 23)
                links[id] = link
                link.writer = scope.launch { serverWriter(link, device) }
                onLinkUp(id)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val link = links[serverLink(device)] ?: return
            value?.let { frame -> link.reassembler.accept(frame)?.let { onPacket(link.id, it) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links[serverLink(device)]?.pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private suspend fun serverWriter(link: Link, device: BluetoothDevice) {
        for (frame in link.outbox) {
            val char = characteristic ?: break
            val done = CompletableDeferred<Boolean>().also { link.pending = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server?.notifyCharacteristicChanged(device, char, false, frame) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = frame
                @Suppress("DEPRECATION")
                server?.notifyCharacteristicChanged(device, char, false) == true
            }
            if (!started || withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } != true) delay(20)
        }
    }

    private fun serverLink(device: BluetoothDevice) = "ble:s:${device.address}"

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        // Our peer id rides in the scan response so others can decide who connects.
        val response = AdvertiseData.Builder().addManufacturerData(MANUFACTURER_ID, Bytes.unhex(myPeerId)).build()
        advertiser.startAdvertising(settings, data, response, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = Log.w(TAG, "advertising failed: $errorCode").let { }
    }

    // Client (central)

    private fun startScanning() {
        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scope.launch {
            // Forget signal readings for phones we haven't heard in a while.
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()
                firstSeen.entries.removeIf { now - it.value > 120_000 && links.values.none { l -> l.address == it.key } }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            rssi[address] = result.rssi
            val theirId = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)?.hex()
            if (theirId != null) peerOfAddress[address] = theirId
            val now = System.currentTimeMillis()
            val seen = firstSeen.getOrPut(address) { now }
            val linked = links.values.any { it.address == address }
            if (linked || address in connecting) return
            val myTurn = theirId == null || myPeerId < theirId || now - seen > FALLBACK_CONNECT_MS
            if (myTurn) connect(result.device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        connecting += device.address
        device.connectGatt(context, false, clientCallback(device), BluetoothDevice.TRANSPORT_LE)
    }

    private fun clientCallback(device: BluetoothDevice) = object : BluetoothGattCallback() {
        val id = "ble:c:${device.address}"

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.requestMtu(517)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connecting -= device.address
                    links.remove(id)?.let { drop(it) } ?: gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            links[id]?.mtu = mtu
            pendingMtu[device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            connecting -= device.address
            val link = Link(id, device.address, mtu = pendingMtu[device.address] ?: 23, gatt = gatt)
            links[id] = link
            link.writer = scope.launch { clientWriter(link, gatt) }
            onLinkUp(id)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            received(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }

        private fun received(frame: ByteArray?) {
            val link = links[id] ?: return
            frame?.let { link.reassembler.accept(it)?.let { packet -> onPacket(id, packet) } }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            links[id]?.pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private suspend fun clientWriter(link: Link, gatt: BluetoothGatt) {
        for (frame in link.outbox) {
            val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) ?: break
            val done = CompletableDeferred<Boolean>().also { link.pending = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = frame
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            if (!started || withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } != true) delay(20)
        }
    }

    private fun drop(link: Link) {
        link.writer?.cancel()
        link.outbox.close()
        link.gatt?.let { runCatching { it.disconnect(); it.close() } }
        onLinkDown(link.id)
    }

    companion object {
        private const val TAG = "LibreChat"
        val SERVICE_UUID: UUID = UUID.fromString("6c1b0f3e-7a52-4c8e-9d4f-2f6a1c3b5e71")
        val CHAR_UUID: UUID = UUID.fromString("6c1b0f3f-7a52-4c8e-9d4f-2f6a1c3b5e71")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Bluetooth SIG's id reserved for testing; carries only our 8-byte peer id. */
        const val MANUFACTURER_ID = 0xFFFF
        const val WRITE_TIMEOUT_MS = 2_000L
        const val FALLBACK_CONNECT_MS = 20_000L
    }
}
