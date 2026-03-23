package com.msp1974.vacompanion.sensors

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.os.ParcelUuid
import android.util.Base64
import androidx.core.app.ActivityCompat
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.timer

typealias BleAdvertisementCallback = (JsonObject) -> Unit

data class BleDeviceInfo(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long,
    val serviceUuids: List<String>,
    val manufacturerId: Int?,
)

class BleScanner(private val context: Context) {

    private val config = APPConfig.getInstance(context)
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    @Volatile private var isScanning = false
    @Volatile private var restartPending = false
    var onAdvertisement: BleAdvertisementCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val nearbyDevices = mutableMapOf<String, BleDeviceInfo>()
    private val DEVICE_TIMEOUT_MS = 30_000L

    private val pendingAdvertisements = mutableMapOf<String, JsonObject>()
    private var batchTimer: Timer? = null
    private var watchdogTimer: Timer? = null

    // Rate limiting: Android max 5 startScan per 30 seconds
    private val scanStartTimes = ArrayDeque<Long>()
    private val MAX_SCANS_PER_WINDOW = 5
    private val WINDOW_MS = 30_000L

    // Silent throttle detection
    @Volatile private var totalResultsSinceStart = 0
    private var scanStartedAt = 0L

    fun getNearbyDevices(): List<BleDeviceInfo> {
        val now = System.currentTimeMillis()
        synchronized(nearbyDevices) {
            nearbyDevices.entries.removeIf { now - it.value.lastSeen > DEVICE_TIMEOUT_MS }
            return nearbyDevices.values.sortedByDescending { it.rssi }
        }
    }

    // ── Scan callback ──────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val rssi = result.rssi
                val device = result.device
                val record = result.scanRecord
                val address = device.address
                val nameRaw = record?.deviceName ?: ""

                Timber.d("BLE adv rx: $address rssi=$rssi name='$nameRaw' threshold=${config.bleRssiThreshold}")

                if (rssi < config.bleRssiThreshold) {
                    Timber.d("BLE adv filtered (rssi $rssi < ${config.bleRssiThreshold}): $address")
                    return
                }

                // Count only results that pass RSSI filter → used for silent throttle detection
                totalResultsSinceStart++
                Timber.d("BLE adv accepted: $address rssi=$rssi total=$totalResultsSinceStart")

                val name = nameRaw.ifEmpty {
                    try {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) device.name ?: "" else ""
                    } catch (e: Exception) { "" }
                }

                val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                val manufacturerId = record?.manufacturerSpecificData?.let {
                    if (it.size() > 0) it.keyAt(0) else null
                }

                synchronized(nearbyDevices) {
                    nearbyDevices[address] = BleDeviceInfo(
                        address = address, name = name, rssi = rssi,
                        lastSeen = System.currentTimeMillis(),
                        serviceUuids = serviceUuids, manufacturerId = manufacturerId,
                    )
                }

                val data = buildJsonObject {
                    put("address", address)
                    put("rssi", rssi)
                    put("local_name", name)
                    putJsonArray("service_uuids") { serviceUuids.forEach { add(JsonPrimitive(it)) } }
                    put("manufacturer_data", buildJsonObject {
                        record?.manufacturerSpecificData?.let { sparse ->
                            for (i in 0 until sparse.size()) {
                                val key = sparse.keyAt(i)
                                val value = sparse.valueAt(i)
                                put(key.toString(), JsonPrimitive(Base64.encodeToString(value, Base64.NO_WRAP)))
                            }
                        }
                    })
                    put("service_data", buildJsonObject {
                        record?.serviceData?.forEach { (uuid, bytes) ->
                            put(uuid.uuid.toString(), JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                        }
                    })
                    put("tx_power", record?.txPowerLevel ?: -1)
                }

                synchronized(pendingAdvertisements) {
                    val isNew = !pendingAdvertisements.containsKey(address)
                    pendingAdvertisements[address] = data
                    if (isNew) Timber.d("BLE adv queued (new): $address name='$name' rssi=$rssi")
                }
            } catch (e: Exception) {
                Timber.e("BLE onScanResult error: $e")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                5 -> "OUT_OF_HARDWARE_RESOURCES"
                6 -> "SCANNING_TOO_FREQUENTLY - wait 30s before retry"
                else -> "UNKNOWN($errorCode)"
            }
            Timber.e("BLE scan FAILED: $reason")
            isScanning = false
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start() {
        if (isScanning) { Timber.d("BLE start() called but already scanning"); return }
        if (!hasScanPermission()) { Timber.w("BLE start() failed: BLUETOOTH_SCAN not granted"); return }

        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: run { Timber.w("BLE start() failed: no adapter"); return }

        val adapterEnabled = try { adapter.isEnabled }
            catch (e: SecurityException) { Timber.w("BLE start() failed: BLUETOOTH_CONNECT not granted"); return }
        if (!adapterEnabled) { Timber.w("BLE start() failed: Bluetooth is disabled"); return }

        val scanner = adapter.bluetoothLeScanner ?: run { Timber.w("BLE start() failed: bluetoothLeScanner null"); return }
        bluetoothLeScanner = scanner

        Timber.i("BLE start() - scanner ready, mode=${config.bleScanMode}, rssi=${config.bleRssiThreshold}")
        doStartScan()
    }

    fun stop() {
        watchdogTimer?.cancel(); watchdogTimer = null
        batchTimer?.cancel(); batchTimer = null
        stopScanHW()
        synchronized(pendingAdvertisements) { pendingAdvertisements.clear() }
        isScanning = false
        wakeLock?.let { if (it.isHeld) { it.release(); Timber.d("BLE wake lock released") } }
        wakeLock = null
        Timber.d("BLE scanning stopped")
    }

    fun applySettings() {
        if (restartPending) {
            Timber.d("BLE applySettings() - restart already pending, skipping duplicate")
            return
        }
        restartPending = true
        Timber.d("BLE applySettings() - will restart in 5s (debounce)")
        watchdogTimer?.cancel(); watchdogTimer = null
        watchdogTimer = Timer().also {
            it.schedule(object : TimerTask() {
                override fun run() {
                    restartPending = false
                    Timber.i("BLE applySettings() debounce fired - restarting")
                    doApplySettings()
                }
            }, 5000L)
        }
    }

    // ── Internal scan management ───────────────────────────────────────────────

    private fun stopScanHW() {
        if (!isScanning) return
        try {
            if (hasScanPermission()) bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) { Timber.e("BLE stopScan error: $e") }
        isScanning = false
    }

    private fun doApplySettings() {
        batchTimer?.cancel(); batchTimer = null
        stopScanHW()

        // Re-acquire scanner
        if (bluetoothLeScanner == null) {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothLeScanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (bluetoothLeScanner == null) { Timber.w("BLE doApplySettings: cannot re-acquire scanner"); return }
        }
        doStartScan()
    }

    @Synchronized
    private fun doStartScan() {
        if (isScanning) {
            Timber.d("BLE doStartScan: already scanning, stopping first")
            stopScanHW()
            Thread.sleep(500)
        }
        if (!hasScanPermission()) { Timber.w("BLE doStartScan: no permission"); return }
        val scanner = bluetoothLeScanner ?: run { Timber.w("BLE doStartScan: scanner is null"); return }

        // Android requires Location Services to be ON for BLE scanning
        // Even with ACCESS_FINE_LOCATION permission, scanning returns 0 results if Location is OFF
        if (!isLocationEnabled()) {
            Timber.e("BLE doStartScan BLOCKED: Location Services are DISABLED! " +
                     "Android requires Location to be ON for BLE scanning. " +
                     "Please enable Location in system settings.")
            return
        }

        // Rate limit check
        val now = System.currentTimeMillis()
        scanStartTimes.removeAll { now - it > WINDOW_MS }
        if (scanStartTimes.size >= MAX_SCANS_PER_WINDOW) {
            val oldestInWindow = scanStartTimes.first()
            val waitMs = WINDOW_MS - (now - oldestInWindow) + 1000
            Timber.w("BLE rate limit: ${scanStartTimes.size} scans in 30s window, waiting ${waitMs}ms")
            Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, waitMs)
            return
        }
        scanStartTimes.addLast(now)

        // Force LOW_LATENCY for continuous scanning - BALANCED gets downgraded to
        // OPPORTUNISTIC in background which causes the "scan once every 30min" behaviour
        val scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val filters: List<ScanFilter>? = config.bleUuidFilter.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { uuid ->
                try { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid.trim())).build() }
                catch (e: Exception) { null }
            }
            ?.takeIf { it.isNotEmpty() }

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            totalResultsSinceStart = 0
            scanStartedAt = now
            // Diagnostic: confirm scanner is registered with system
            Timber.i("BLE doStartScan: startScan() called OK. Scanner=$scanner, isScanning=$isScanning")

            batchTimer?.cancel()
            var tick = 0
            batchTimer = timer(name = "BleBatch", period = config.bleBatchIntervalMs) {
                flushBatch()
                tick++

                // Heartbeat every 30s
                val ticksPer30s = (30_000L / config.bleBatchIntervalMs).toInt().coerceAtLeast(1)
                if (tick % ticksPer30s == 0) {
                    val elapsedSec = (System.currentTimeMillis() - scanStartedAt) / 1000
                    val now2 = System.currentTimeMillis()
                    val activeNearby = synchronized(nearbyDevices) {
                        nearbyDevices.entries.removeIf { now2 - it.value.lastSeen > DEVICE_TIMEOUT_MS }
                        nearbyDevices.size
                    }
                    Timber.i("BLE heartbeat: scanning=$isScanning, nearby=$activeNearby, totalResults=$totalResultsSinceStart, rssiThreshold=${config.bleRssiThreshold}, elapsed=${elapsedSec}s")

                    // Watchdog: no results for 5 min → force restart even if not throttled
                    if (elapsedSec > 0 && elapsedSec % 300 == 0L && totalResultsSinceStart == 0) {
                        Timber.w("BLE watchdog: no results for ${elapsedSec}s, forcing restart")
                        batchTimer?.cancel(); batchTimer = null
                        stopScanHW()
                        bluetoothLeScanner = null
                        val mgr2 = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                        bluetoothLeScanner = mgr2?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                        if (bluetoothLeScanner != null) {
                            Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, 3000L)
                        }
                        return@timer
                    }

                    // Silent throttle: scanning but ZERO results after 30s → hard restart
                    if (elapsedSec >= 30 && totalResultsSinceStart == 0) {
                        Timber.w("BLE SILENT THROTTLE detected (${elapsedSec}s, 0 results)! Full restart in 5s...")
                        batchTimer?.cancel(); batchTimer = null
                        // Stop existing scan
                        try {
                            if (hasScanPermission()) bluetoothLeScanner?.stopScan(scanCallback)
                        } catch (e: Exception) {}
                        isScanning = false
                        bluetoothLeScanner = null
                        // Wait 5s then get fresh scanner object and restart
                        Timer().schedule(object : TimerTask() {
                            override fun run() {
                                val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                                bluetoothLeScanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                                if (bluetoothLeScanner != null) {
                                    Timber.i("BLE throttle recovery: new scanner obtained, starting...")
                                    doStartScan()
                                } else {
                                    Timber.e("BLE throttle recovery failed: cannot get scanner")
                                }
                            }
                        }, 5000L)
                    }
                }

                // Preventive restart every 25 min to avoid 30-min system throttle
                val ticksPer25min = (25 * 60_000L / config.bleBatchIntervalMs).toInt().coerceAtLeast(1)
                if (tick % ticksPer25min == 0) {
                    Timber.i("BLE preventive 25-min restart")
                    batchTimer?.cancel(); batchTimer = null
                    stopScanHW()
                    Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, 3000L)
                }
            }

            Timber.i("BLE scanning STARTED - mode=$scanMode, rssi=${config.bleRssiThreshold}, batch=${config.bleBatchIntervalMs}ms, scansInWindow=${scanStartTimes.size}")
            // Acquire partial wake lock to keep CPU running for BLE callbacks
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaca:BleScanWakeLock")
                @Suppress("WakelockTimeout")
                wakeLock?.acquire() // indefinite - released in stop()
                Timber.d("BLE wake lock acquired")
            }
        } catch (e: SecurityException) {
            Timber.e("BLE doStartScan SecurityException: $e")
            isScanning = false
        } catch (e: Exception) {
            Timber.e("BLE doStartScan failed: $e")
            isScanning = false
        }
    }

    private fun flushBatch() {
        val batch: List<JsonObject>
        synchronized(pendingAdvertisements) {
            if (pendingAdvertisements.isEmpty()) return
            batch = pendingAdvertisements.values.toList()
            pendingAdvertisements.clear()
        }
        if (onAdvertisement == null) {
            Timber.w("BLE batch: onAdvertisement NULL - not connected to HA! Dropping ${batch.size} adv(s)")
            return
        }
        Timber.i("BLE batch flush: sending ${batch.size} adv(s) to HA")
        batch.forEach { adv -> onAdvertisement?.invoke(adv) }
    }

    // ── BT state receiver ──────────────────────────────────────────────────────

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Timber.i("BLE: Bluetooth ON - restarting scanner")
                    if (config.bleProxyEnabled) {
                        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        bluetoothLeScanner = mgr?.adapter?.bluetoothLeScanner
                        Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, 2000L)
                    }
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    Timber.i("BLE: Bluetooth turning off")
                    batchTimer?.cancel(); batchTimer = null
                    isScanning = false
                    bluetoothLeScanner = null
                }
            }
        }
    }
    private var btReceiverRegistered = false

    fun registerBtReceiver() {
        if (btReceiverRegistered) return
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        btReceiverRegistered = true
        Timber.d("BLE: BT state receiver registered")
    }

    fun unregisterBtReceiver() {
        if (!btReceiverRegistered) return
        try { context.unregisterReceiver(btStateReceiver) } catch (e: Exception) {}
        btReceiverRegistered = false
    }

    private fun hasScanPermission(): Boolean =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isLocationEnabled ?: true  // assume OK if can't check
    }
}
