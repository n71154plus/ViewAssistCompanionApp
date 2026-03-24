package com.msp1974.vacompanion.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Client Characteristic Configuration Descriptor — enables notifications/indications
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val CONNECT_TIMEOUT_MS   = 10_000L
private const val OPERATION_TIMEOUT_MS =  5_000L

// ── Public data models ─────────────────────────────────────────────────────────

data class BleGattCharacteristicInfo(
    val uuid: String,
    val properties: Int,
)

data class BleGattServiceInfo(
    val uuid: String,
    val characteristics: List<BleGattCharacteristicInfo>,
)

// ── Callback interface ─────────────────────────────────────────────────────────

interface BleGattCallback {
    fun onConnected(address: String, mtu: Int, services: List<BleGattServiceInfo>)
    fun onDisconnected(address: String)
    fun onReadResult(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray)
    fun onWriteResult(address: String, serviceUuid: String, characteristicUuid: String, success: Boolean)
    fun onNotification(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray)
    fun onError(address: String, operation: String, message: String)
    /** Called after every connect or disconnect with the current slot state. Default no-op. */
    fun onConnectionsUpdated(free: Int, limit: Int, allocated: List<String>) {}
}

// ── Internal types ─────────────────────────────────────────────────────────────

private data class GattOperation(
    val description: String,
    // Returns true if an async operation was started (queue waits for callback),
    // false if no async work was done (queue advances immediately).
    val action: (BluetoothGatt) -> Boolean,
)

private class DeviceConnection(val address: String) {
    var gatt: BluetoothGatt? = null
    val operationQueue: LinkedList<GattOperation> = LinkedList()
    @Volatile var processingOperation: Boolean = false
    @Volatile var connected: Boolean = false
    // "serviceUuid/charUuid" → characteristic instance for fast lookup
    val characteristicMap: MutableMap<String, BluetoothGattCharacteristic> = mutableMapOf()
    var timeoutTimer: Timer? = null
    // MTU negotiation: services are held here until onMtuChanged fires
    var mtu: Int = 23
    var pendingServices: List<BleGattServiceInfo>? = null
}

// ── BleGattManager ─────────────────────────────────────────────────────────────

class BleGattManager(private val context: Context) {

    private val connections = ConcurrentHashMap<String, DeviceConnection>()
    private var wakeLock: PowerManager.WakeLock? = null

    private val config = APPConfig.getInstance(context)

    // Multi-listener support: all registered callbacks receive every event
    private val callbackList = java.util.concurrent.CopyOnWriteArrayList<BleGattCallback>()
    private var _primaryCallback: BleGattCallback? = null

    // Backward-compat single-slot: replaces the previous primary callback
    var callback: BleGattCallback?
        get() = _primaryCallback
        set(value) {
            _primaryCallback?.let { callbackList.remove(it) }
            _primaryCallback = value
            value?.let { if (!callbackList.contains(it)) callbackList.add(it) }
        }

    fun addCallback(cb: BleGattCallback) { if (!callbackList.contains(cb)) callbackList.add(cb) }
    fun removeCallback(cb: BleGattCallback) { callbackList.remove(cb) }
    private fun dispatch(action: BleGattCallback.() -> Unit) { callbackList.forEach { it.action() } }

    /** Broadcast current slot state to all callbacks. */
    private fun dispatchConnectionsUpdated() {
        val limit = config.bleMaxConnections
        val allocated = connections.keys.toList()
        val free = (limit - allocated.size).coerceAtLeast(0)
        dispatch { onConnectionsUpdated(free, limit, allocated) }
    }

    /** Trigger an immediate connections-state broadcast (call after registering a callback). */
    fun broadcastCurrentState() = dispatchConnectionsUpdated()

    // ── Public API ─────────────────────────────────────────────────────────────

    fun connect(address: String) {
        if (!hasConnectPermission()) {
            Timber.w("BleGatt connect($address): BLUETOOTH_CONNECT not granted")
            dispatch { onError(address, "connect", "BLUETOOTH_CONNECT permission not granted") }
            return
        }
        if (connections.containsKey(address)) {
            Timber.d("BleGatt connect($address): already connected or connecting")
            return
        }
        val maxConnections = config.bleMaxConnections
        if (connections.size >= maxConnections) {
            Timber.w("BleGatt connect($address): max connections ($maxConnections) reached")
            dispatch { onError(address, "connect", "Max connections ($maxConnections) reached") }
            return
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Timber.w("BleGatt connect($address): Bluetooth not available")
            dispatch { onError(address, "connect", "Bluetooth not available") }
            return
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Timber.e("BleGatt connect($address): invalid address")
            dispatch { onError(address, "connect", "Invalid device address") }
            return
        }

        val conn = DeviceConnection(address)
        connections[address] = conn
        acquireWakeLockIfNeeded()

        startTimeout(conn, "connect") {
            Timber.w("BleGatt connect($address): timeout")
            handleDisconnect(address)
            dispatch { onError(address, "connect", "Connection timeout") }
        }

        try {
            conn.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, buildGattCallback(address), BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, buildGattCallback(address))
            }
            Timber.i("BleGatt connect($address): connecting...")
        } catch (e: SecurityException) {
            Timber.e("BleGatt connect($address): SecurityException — ${e.message}")
            connections.remove(address)
            dispatch { onError(address, "connect", "Security exception: ${e.message}") }
        }
    }

    fun disconnect(address: String) {
        if (!hasConnectPermission()) return
        val conn = connections[address] ?: run {
            Timber.d("BleGatt disconnect($address): not connected")
            return
        }
        Timber.i("BleGatt disconnect($address)")
        try {
            conn.gatt?.disconnect()
        } catch (e: SecurityException) {
            Timber.e("BleGatt disconnect($address): SecurityException — ${e.message}")
            handleDisconnect(address)
        }
    }

    fun readCharacteristic(address: String, serviceUuid: String, characteristicUuid: String) {
        val conn = connections[address] ?: run {
            dispatch { onError(address, "read", "Device not connected") }
            return
        }
        enqueueOperation(conn, "read $characteristicUuid") { gatt ->
            val char = conn.characteristicMap[charKey(serviceUuid, characteristicUuid)] ?: run {
                dispatch { onError(address, "read", "Characteristic not found: $characteristicUuid") }
                return@enqueueOperation false
            }
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                dispatch { onError(address, "read", "Characteristic not readable: $characteristicUuid") }
                return@enqueueOperation false
            }
            try {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(char)
            } catch (e: SecurityException) {
                dispatch { onError(address, "read", "Security exception: ${e.message}") }
                false
            }
        }
    }

    fun writeCharacteristic(
        address: String,
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        withResponse: Boolean = true,
    ) {
        val conn = connections[address] ?: run {
            dispatch { onError(address, "write", "Device not connected") }
            return
        }
        val writeType = if (withResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        enqueueOperation(conn, "write $characteristicUuid") { gatt ->
            val char = conn.characteristicMap[charKey(serviceUuid, characteristicUuid)] ?: run {
                dispatch { onError(address, "write", "Characteristic not found: $characteristicUuid") }
                return@enqueueOperation false
            }
            val requiredProp = if (withResponse)
                BluetoothGattCharacteristic.PROPERTY_WRITE
            else
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            if (char.properties and requiredProp == 0) {
                dispatch { onError(address, "write", "Characteristic does not support this write type") }
                return@enqueueOperation false
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, value, writeType) == 0 // 0 = BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = writeType
                    @Suppress("DEPRECATION")
                    char.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
            } catch (e: SecurityException) {
                dispatch { onError(address, "write", "Security exception: ${e.message}") }
                false
            }
        }
    }

    fun setNotification(
        address: String,
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean,
    ) {
        val conn = connections[address] ?: run {
            dispatch { onError(address, "notify", "Device not connected") }
            return
        }
        val opName = if (enable) "subscribe" else "unsubscribe"

        enqueueOperation(conn, "$opName $characteristicUuid") { gatt ->
            val char = conn.characteristicMap[charKey(serviceUuid, characteristicUuid)] ?: run {
                dispatch { onError(address, opName, "Characteristic not found: $characteristicUuid") }
                return@enqueueOperation false
            }
            val hasNotify   = char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY   != 0
            val hasIndicate = char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!hasNotify && !hasIndicate) {
                dispatch { onError(address, opName, "Characteristic does not support notifications") }
                return@enqueueOperation false
            }

            try {
                gatt.setCharacteristicNotification(char, enable)

                val descriptor = char.getDescriptor(CCCD_UUID) ?: run {
                    // No CCCD — local registration only, advance queue immediately
                    Timber.d("BleGatt $opName($characteristicUuid): no CCCD, local-only")
                    return@enqueueOperation false
                }

                val cccdValue = when {
                    !enable     -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    hasIndicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else        -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, cccdValue) == 0 // 0 = BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = cccdValue
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } catch (e: SecurityException) {
                dispatch { onError(address, opName, "Security exception: ${e.message}") }
                false
            }
        }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
        // Safety release: if all connections are gone already (e.g. callback never fired)
        if (connections.isEmpty()) {
            releaseWakeLock()
        }
    }

    fun getConnectedAddresses(): List<String> = connections.keys.toList()

    // ── GATT callback ──────────────────────────────────────────────────────────

    private fun buildGattCallback(address: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val conn = connections[address] ?: return
            cancelTimeout(conn)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("BleGatt connected($address) status=$status")
                    conn.connected = true
                    try {
                        startTimeout(conn, "discoverServices") {
                            Timber.w("BleGatt discoverServices($address): timeout")
                            handleDisconnect(address)
                            dispatch { onError(address, "discover", "Service discovery timeout") }
                        }
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Timber.e("BleGatt discoverServices($address): SecurityException")
                        handleDisconnect(address)
                        dispatch { onError(address, "discover", "Security exception") }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("BleGatt disconnected($address) status=$status")
                    val wasConnected = conn.connected
                    handleDisconnect(address)
                    if (wasConnected) {
                        dispatch { onDisconnected(address) }
                    } else {
                        // Failed before fully connecting
                        dispatch { onError(address, "connect", "Connection failed: GATT status $status") }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val conn = connections[address] ?: return
            cancelTimeout(conn)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("BleGatt onServicesDiscovered($address): failed status=$status")
                handleDisconnect(address)
                dispatch { onError(address, "discover", "Service discovery failed: status $status") }
                return
            }

            val serviceInfoList = gatt.services.map { service ->
                val charInfoList = service.characteristics.map { char ->
                    conn.characteristicMap[charKey(service.uuid.toString(), char.uuid.toString())] = char
                    BleGattCharacteristicInfo(uuid = char.uuid.toString(), properties = char.properties)
                }
                BleGattServiceInfo(uuid = service.uuid.toString(), characteristics = charInfoList)
            }
            Timber.i("BleGatt services discovered($address): ${serviceInfoList.size} service(s)")

            // Hold services and request MTU negotiation before reporting onConnected.
            // HA uses the MTU to size BleakGATTCharacteristic.max_write_without_response.
            conn.pendingServices = serviceInfoList
            try {
                startTimeout(conn, "mtu") {
                    // MTU timeout — fire onConnected with the default MTU
                    Timber.w("BleGatt MTU request timeout for $address, using default MTU ${conn.mtu}")
                    val services = conn.pendingServices ?: return@startTimeout
                    conn.pendingServices = null
                    dispatch { onConnected(address, conn.mtu, services) }
                    dispatchConnectionsUpdated()
                }
                val requested = gatt.requestMtu(512)
                if (!requested) {
                    // requestMtu returned false immediately — fire without MTU negotiation
                    cancelTimeout(conn)
                    Timber.w("BleGatt requestMtu($address) returned false, using default MTU ${conn.mtu}")
                    conn.pendingServices = null
                    dispatch { onConnected(address, conn.mtu, serviceInfoList) }
                    dispatchConnectionsUpdated()
                }
            } catch (e: SecurityException) {
                cancelTimeout(conn)
                Timber.e("BleGatt requestMtu($address): SecurityException — ${e.message}")
                conn.pendingServices = null
                dispatch { onConnected(address, conn.mtu, serviceInfoList) }
                dispatchConnectionsUpdated()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val conn = connections[address] ?: return
            cancelTimeout(conn)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                conn.mtu = mtu
                Timber.i("BleGatt MTU negotiated for $address: $mtu bytes")
            } else {
                Timber.w("BleGatt MTU negotiation failed for $address (status=$status), using ${conn.mtu}")
            }

            val services = conn.pendingServices ?: return
            conn.pendingServices = null
            dispatch { onConnected(address, conn.mtu, services) }
            dispatchConnectionsUpdated()
        }

        // API < 33 path
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled by the new override
            val conn = connections[address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dispatch { onReadResult(address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), characteristic.value ?: ByteArray(0)) }
            } else {
                dispatch { onError(address, "read", "Read failed: status $status") }
            }
            processNextOperation(conn)
        }

        // API 33+ path
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val conn = connections[address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dispatch { onReadResult(address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), value) }
            } else {
                dispatch { onError(address, "read", "Read failed: status $status") }
            }
            processNextOperation(conn)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val conn = connections[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleGatt write($address/${characteristic.uuid}): failed status=$status")
            }
            dispatch { onWriteResult(address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), status == BluetoothGatt.GATT_SUCCESS) }
            processNextOperation(conn)
        }

        // API < 33 path
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            dispatch { onNotification(address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), characteristic.value ?: ByteArray(0)) }
        }

        // API 33+ path
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatch { onNotification(address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), value) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val conn = connections[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("BleGatt descriptor write($address/${descriptor.characteristic.uuid}): failed status=$status")
            }
            processNextOperation(conn)
        }
    }

    // ── Operation queue ────────────────────────────────────────────────────────

    private fun enqueueOperation(conn: DeviceConnection, description: String, action: (BluetoothGatt) -> Boolean) {
        synchronized(conn.operationQueue) {
            conn.operationQueue.add(GattOperation(description, action))
        }
        if (!conn.processingOperation) {
            processNextOperation(conn)
        }
    }

    private fun processNextOperation(conn: DeviceConnection) {
        cancelTimeout(conn)
        val gatt = conn.gatt ?: return

        val op: GattOperation?
        synchronized(conn.operationQueue) {
            conn.processingOperation = false
            op = conn.operationQueue.poll()
        }
        if (op == null) return

        conn.processingOperation = true
        Timber.d("BleGatt [${conn.address}] op: ${op.description}")

        startTimeout(conn, op.description) {
            Timber.w("BleGatt op timeout: '${op.description}' on ${conn.address}")
            processNextOperation(conn)
        }

        val asyncStarted = op.action(gatt)
        if (!asyncStarted) {
            // Synchronous or error path — advance queue immediately
            cancelTimeout(conn)
            conn.processingOperation = false
            processNextOperation(conn)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(address: String) {
        val conn = connections.remove(address) ?: return
        cancelTimeout(conn)
        conn.connected = false
        synchronized(conn.operationQueue) { conn.operationQueue.clear() }
        conn.processingOperation = false
        try { conn.gatt?.close() } catch (_: Exception) {}
        conn.gatt = null
        conn.characteristicMap.clear()
        if (connections.isEmpty()) {
            releaseWakeLock()
        }
        // Notify listeners that a slot has been freed
        dispatchConnectionsUpdated()
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "vaca:BleGattWakeLock",
            )
            @Suppress("WakelockTimeout")
            wakeLock?.acquire() // released when all connections are closed
            Timber.d("BleGatt wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) { it.release(); Timber.d("BleGatt wake lock released") } }
        wakeLock = null
    }

    private fun startTimeout(conn: DeviceConnection, operation: String, onTimeout: () -> Unit) {
        cancelTimeout(conn)
        val delayMs = if (operation == "connect" || operation == "discoverServices")
            CONNECT_TIMEOUT_MS else OPERATION_TIMEOUT_MS
        conn.timeoutTimer = Timer().also {
            it.schedule(object : TimerTask() { override fun run() = onTimeout() }, delayMs)
        }
    }

    private fun cancelTimeout(conn: DeviceConnection) {
        conn.timeoutTimer?.cancel()
        conn.timeoutTimer = null
    }

    private fun charKey(serviceUuid: String, characteristicUuid: String) =
        "${serviceUuid.lowercase()}/${characteristicUuid.lowercase()}"

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // BLUETOOTH_CONNECT permission only exists on API 31+
        }
}
