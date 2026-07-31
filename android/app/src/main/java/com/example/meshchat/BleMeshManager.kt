package com.example.meshchat

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Core BLE mesh flooding engine.
 *
 * Protocol (packet layout, sent as a single GATT characteristic write):
 *   [0..15]  message UUID (16 bytes)      -> used for dedup / loop prevention
 *   [16]     TTL (1 byte, 0-255)          -> decremented on each relay hop
 *   [17..24] timestamp millis (8 bytes)   -> for display / ordering
 *   [25..]   UTF-8 text payload           -> the actual message
 *
 * Every device is BOTH:
 *   - a GATT server (peripheral) that other phones write incoming messages into
 *   - a GATT client (central) that scans for peers and writes outgoing/relayed
 *     messages into their server characteristic
 *
 * Flooding: when a message is received (or created locally), if its UUID hasn't
 * been seen before, it's shown to the user and re-broadcast to every currently
 * connected peer with TTL-1, as long as TTL > 0. Seen UUIDs are cached so the
 * same message is never processed or relayed twice by the same device.
 */
class BleMeshManager(
    private val context: Context,
    private val listener: MeshListener
) {
    interface MeshListener {
        fun onMessageReceived(text: String, timestamp: Long, hopRelayed: Boolean)
        fun onStatusChanged(status: String)
        fun onPeerCountChanged(count: Int)
    }

    companion object {
        private const val TAG = "BleMeshManager"

        // Randomly generated app-specific UUIDs. Keep these identical on iOS build
        // so Android and iPhone devices can discover and talk to each other.
        val SERVICE_UUID: UUID = UUID.fromString("5a1e0001-92c4-4c53-a3f1-2e7d0f6b9c10")
        val MESSAGE_CHAR_UUID: UUID = UUID.fromString("5a1e0002-92c4-4c53-a3f1-2e7d0f6b9c10")

        const val DEFAULT_TTL = 5
        const val MAX_SEEN_CACHE = 500
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    // Connected peers we can write into, keyed by device address
    private val connectedPeers = ConcurrentHashMap<String, BluetoothGatt>()
    // Devices already being connected to, to avoid duplicate connect attempts
    private val connectingAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    // Dedup cache: message UUID string -> seen. LinkedHashMap gives us simple FIFO eviction.
    private val seenMessages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > MAX_SEEN_CACHE
            }
        }
    )

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatusChanged("Bluetooth is off")
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        startGattServer()
        startAdvertising()
        startScanning()
        listener.onStatusChanged("Mesh active")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
        connectedPeers.values.forEach { it.disconnect(); it.close() }
        connectedPeers.clear()
        gattServer?.close()
        gattServer = null
        listener.onStatusChanged("Mesh stopped")
    }

    // ---------------- GATT SERVER (peripheral role: receives writes from peers) ----------------

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Server: peer connected ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Server: peer disconnected ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_CHAR_UUID) {
                handleIncomingPacket(value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // ---------------- ADVERTISING (so peers can discover this device) ----------------

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            listener.onStatusChanged("Advertise failed: $errorCode")
        }
    }

    // ---------------- SCANNING (find peers, connect as client) ----------------

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (connectedPeers.containsKey(address) || connectingAddresses.contains(address)) return
            connectingAddresses.add(address)
            device.connectGatt(context, false, gattClientCallback(address))
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatusChanged("Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun gattClientCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedPeers.remove(address)
                connectingAddresses.remove(address)
                gatt.close()
                listener.onPeerCountChanged(connectedPeers.size)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            connectingAddresses.remove(address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(MESSAGE_CHAR_UUID)
                if (characteristic != null) {
                    connectedPeers[address] = gatt
                    listener.onPeerCountChanged(connectedPeers.size)
                }
            }
        }
    }

    // ---------------- MESSAGE BUILD / SEND / RELAY ----------------

    /** Called when the user hits Send. Creates a fresh message and floods it. */
    fun sendMessage(text: String) {
        val id = UUID.randomUUID()
        val timestamp = System.currentTimeMillis()
        markSeen(id)
        listener.onMessageReceived(text, timestamp, hopRelayed = false)
        broadcastPacket(buildPacket(id, DEFAULT_TTL, timestamp, text))
    }

    private fun handleIncomingPacket(bytes: ByteArray) {
        if (bytes.size < 25) return // malformed / too short
        val buffer = ByteBuffer.wrap(bytes)
        val msb = buffer.long
        val lsb = buffer.long
        val id = UUID(msb, lsb)
        val ttl = buffer.get().toInt() and 0xFF
        val timestamp = buffer.long
        val textBytes = ByteArray(bytes.size - 25)
        buffer.get(textBytes)
        val text = String(textBytes, Charsets.UTF_8)

        if (isSeen(id)) return // already processed this message, drop to prevent loops
        markSeen(id)

        listener.onMessageReceived(text, timestamp, hopRelayed = true)

        if (ttl > 0) {
            broadcastPacket(buildPacket(id, ttl - 1, timestamp, text))
        }
    }

    private fun buildPacket(id: UUID, ttl: Int, timestamp: Long, text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(25 + textBytes.size)
        buffer.putLong(id.mostSignificantBits)
        buffer.putLong(id.leastSignificantBits)
        buffer.put(ttl.toByte())
        buffer.putLong(timestamp)
        buffer.put(textBytes)
        return buffer.array()
    }

    @SuppressLint("MissingPermission")
    private fun broadcastPacket(packet: ByteArray) {
        for ((_, gatt) in connectedPeers) {
            val service = gatt.getService(SERVICE_UUID) ?: continue
            val characteristic = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: continue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = packet
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun isSeen(id: UUID): Boolean = seenMessages.containsKey(id.toString())
    private fun markSeen(id: UUID) { seenMessages[id.toString()] = true }
}
