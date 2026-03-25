package com.msp1974.vacompanion.sensors

import android.Manifest
import android.app.PendingIntent
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
import android.os.Build
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
    val txPower: Int = -1,
    val manufacturerData: Map<Int, String> = emptyMap(),   // company id -> hex string
    val serviceData: Map<String, String> = emptyMap(),      // uuid -> hex string
)

class BleScanner(private val context: Context) {

    companion object {
        /** Set while this scanner is active; used by BleScanReceiver to route PI-delivered results. */
        @Volatile var instance: BleScanner? = null
    }

    private val config = APPConfig.getInstance(context)
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    @Volatile private var isScanning = false
    @Volatile private var restartPending = false
    var onAdvertisement: BleAdvertisementCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingIntentScan: PendingIntent? = null

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

    // Screen state: LOW_LATENCY when on, LOW_POWER when off
    @Volatile private var isScreenOff = false

    // Screen-off watchdog: tracks results at last heartbeat to detect scan stoppage
    @Volatile private var resultsAtLastHeartbeat = 0

    /**
     * Called by BleScanReceiver to record a PendingIntent-delivered scan result.
     * Updates nearbyDevices and the result counter so the heartbeat reflects
     * screen-off hits (the ScanCallback is suppressed by Android when screen is off).
     */
    fun recordPiResult(info: BleDeviceInfo) {
        totalResultsSinceStart++
        synchronized(nearbyDevices) {
            nearbyDevices[info.address] = info
        }
    }

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
                        // BLUETOOTH_CONNECT only exists on API 31+; on API 26-30 BLUETOOTH (normal
                        // permission) covers device.name access so no runtime check is needed
                        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                                PackageManager.PERMISSION_GRANTED
                        if (allowed) device.name ?: "" else ""
                    } catch (e: Exception) { "" }
                }

                val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                val manufacturerId = record?.manufacturerSpecificData?.let {
                    if (it.size() > 0) it.keyAt(0) else null
                }
                val txPower = record?.txPowerLevel ?: -1
                val manufacturerDataMap = mutableMapOf<Int, String>().also { map ->
                    record?.manufacturerSpecificData?.let { sparse ->
                        for (i in 0 until sparse.size()) {
                            map[sparse.keyAt(i)] = sparse.valueAt(i).joinToString("") { b -> "%02x".format(b) }
                        }
                    }
                }
                val serviceDataMap = mutableMapOf<String, String>().also { map ->
                    record?.serviceData?.forEach { (uuid, bytes) ->
                        map[uuid.uuid.toString()] = bytes.joinToString("") { b -> "%02x".format(b) }
                    }
                }

                synchronized(nearbyDevices) {
                    nearbyDevices[address] = BleDeviceInfo(
                        address = address, name = name, rssi = rssi,
                        lastSeen = System.currentTimeMillis(),
                        serviceUuids = serviceUuids, manufacturerId = manufacturerId,
                        txPower = txPower,
                        manufacturerData = manufacturerDataMap,
                        serviceData = serviceDataMap,
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
                6 -> "SCANNING_TOO_FREQUENTLY"
                else -> "UNKNOWN($errorCode)"
            }
            Timber.e("BLE scan FAILED: $reason (code=$errorCode)")
            isScanning = false

            val retryDelayMs: Long? = when (errorCode) {
                // State desync — clear and retry immediately
                SCAN_FAILED_ALREADY_STARTED -> 1_000L
                // BT stack glitch — give the stack a moment to settle
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> 3_000L
                SCAN_FAILED_INTERNAL_ERROR -> 3_000L
                // Hardware resource exhaustion — wait longer
                5 -> 5_000L
                // Exceeded 5-scans/30s limit — wait just past the 30s window
                6 -> 31_000L
                // FEATURE_UNSUPPORTED — not recoverable, don't retry
                SCAN_FAILED_FEATURE_UNSUPPORTED -> null
                else -> 5_000L
            }

            if (retryDelayMs != null) {
                Timber.w("BLE onScanFailed: scheduling retry in ${retryDelayMs}ms")
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val newScanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                        if (newScanner != null) {
                            bluetoothLeScanner = newScanner
                            doStartScan()
                        } else {
                            Timber.e("BLE onScanFailed retry: adapter unavailable, giving up")
                        }
                    }
                }, retryDelayMs)
            } else {
                Timber.e("BLE scan error $reason is not recoverable — no retry")
            }
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

        instance = this
        Timber.i("BLE start() - scanner ready, mode=${config.bleScanMode}, rssi=${config.bleRssiThreshold}")
        doStartScan()
    }

    fun stop() {
        watchdogTimer?.cancel(); watchdogTimer = null
        batchTimer?.cancel(); batchTimer = null
        stopPendingIntentScan()
        stopScanHW()
        synchronized(pendingAdvertisements) { pendingAdvertisements.clear() }
        isScanning = false
        instance = null
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
        stopPendingIntentScan()
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

        // Always use LOW_LATENCY for the callback scan — LOW_POWER drastically reduces
        // callback frequency and yields near-zero results even when screen is off.
        // Background survival is handled by the empty ScanFilter + PendingIntent fallback.
        val scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val filters = buildScanFilters()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            totalResultsSinceStart = 0
            resultsAtLastHeartbeat = 0
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
                    if (isScreenOff) {
                        val delta = totalResultsSinceStart - resultsAtLastHeartbeat
                        Timber.i("BLE heartbeat [screen-off]: scanning=$isScanning, nearby=$activeNearby, totalResults=$totalResultsSinceStart, delta=$delta, location=${isLocationEnabled()}, elapsed=${elapsedSec}s")

                        // Screen-off watchdog: Android 8+ silently stops BLE scan callbacks
                        // ~30s after the screen turns off. Detect this via delta==0 and
                        // restart with a FRESH BluetoothLeScanner instance — reusing the old
                        // scanner object after the system has throttled it yields zero results.
                        if (delta == 0 && elapsedSec >= 30) {
                            Timber.w("BLE screen-off watchdog: no new results in last 30s (total=$totalResultsSinceStart), restarting with fresh scanner")
                            batchTimer?.cancel(); batchTimer = null
                            stopScanHW()
                            bluetoothLeScanner = null
                            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                            bluetoothLeScanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                            if (bluetoothLeScanner != null) {
                                Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, 1_000L)
                            } else {
                                Timber.e("BLE screen-off watchdog: adapter unavailable, cannot restart")
                            }
                            return@timer
                        }
                        resultsAtLastHeartbeat = totalResultsSinceStart
                    } else {
                        Timber.i("BLE heartbeat: scanning=$isScanning, nearby=$activeNearby, totalResults=$totalResultsSinceStart, rssiThreshold=${config.bleRssiThreshold}, elapsed=${elapsedSec}s")
                    }

                    // Watchdog / silent-throttle restarts are only valid when the screen is ON.
                    // During screen-off, the ScanCallback is suppressed by Android regardless
                    // of scan mode — totalResults will always be 0 even if the PendingIntent
                    // scan is healthy. Restarting would waste the 5-scans/30s budget and
                    // eventually trigger SCANNING_TOO_FREQUENTLY, breaking scanning entirely.
                    if (!isScreenOff) {

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

                    } // end if (!isScreenOff)
                } // end if (tick % ticksPer30s == 0)

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

        if (isScanning) startPendingIntentScan()
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
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
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
                Intent.ACTION_SCREEN_OFF -> {
                    if (config.bleProxyEnabled) {
                        isScreenOff = true
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                        val locationOn = isLocationEnabled()
                        Timber.i("BLE: Screen OFF — scan continues (batteryExempt=$batteryExempt, location=$locationOn)")
                        if (!batteryExempt) {
                            Timber.w("BLE: battery optimization active — screen-off scanning may be blocked. " +
                                "Fix: Settings > Apps > [App] > Battery > Unrestricted")
                        }
                        if (!locationOn) {
                            Timber.w("BLE: Location Services are OFF — BLE callbacks will be suppressed. " +
                                "This device may disable Location when the screen turns off.")
                        }
                        // Do NOT stop the scan. Stopping and restarting while screen is off
                        // wastes scan budget (5/30s limit) and a restarted scan delivers no
                        // results anyway — the OS blocks delivery after screen-off. Keeping the
                        // existing scan registration alive is the best we can do.
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Upgrade back to LOW_LATENCY now that the screen is on.
                    // Also acquire a fresh scanner object in case the old one became stale.
                    if (config.bleProxyEnabled) {
                        Timber.i("BLE: Screen ON — switching back to LOW_LATENCY scan mode")
                        isScreenOff = false
                        batchTimer?.cancel(); batchTimer = null
                        stopScanHW()
                        bluetoothLeScanner = null
                        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        bluetoothLeScanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
                        if (bluetoothLeScanner != null) {
                            Timer().schedule(object : TimerTask() { override fun run() { doStartScan() } }, 1000L)
                        } else {
                            Timber.e("BLE screen ON restart: adapter unavailable")
                        }
                    }
                }
            }
        }
    }
    private var btReceiverRegistered = false

    fun registerBtReceiver() {
        if (btReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(btStateReceiver, filter)
        btReceiverRegistered = true
        Timber.d("BLE: BT state + screen receiver registered")
    }

    fun unregisterBtReceiver() {
        if (!btReceiverRegistered) return
        try { context.unregisterReceiver(btStateReceiver) } catch (e: Exception) {}
        btReceiverRegistered = false
    }

    private fun buildScanFilters(): List<ScanFilter> {
        val uuidFilters = config.bleUuidFilter.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { uuid ->
                try { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid.trim())).build() }
                catch (e: Exception) { null }
            }
            ?.takeIf { it.isNotEmpty() }
        if (uuidFilters != null) return uuidFilters

        // Android 8+: null filter causes scan to stop ~30s after screen-off.
        // A filter that matches all devices signals to the OS that a filter is present,
        // allowing background scanning to continue.
        //
        // API 33+: use setDeviceAddress with a zero mask — any address ANDed with 0x00
        // always equals 0x00, so every device matches. This is a more explicit "match-all"
        // that some OEM BT stacks honour better than a truly empty ScanFilter.
        //
        // Below API 33: fall back to an empty ScanFilter (no criteria = match all).
        // Android 8+: the OS allows background scanning only when a "real" filter is present.
        // An empty ScanFilter() is treated as "no criteria" by some OEM BT stacks and ignored.
        //
        // Strategy: use two filters (OR logic) for maximum device coverage:
        //
        //  Filter 1 — service UUID with all-zero mask:
        //    (deviceUUID AND 0x0000…) == (0x0000… AND 0x0000…)  →  0 == 0  → always true
        //    This has REAL criteria (non-null UUID), so OEM stacks recognise it as a
        //    genuine filter. Matches every device that advertises ANY service UUID.
        //
        //  Filter 2 — empty filter:
        //    Fallback for devices that advertise NO service UUID (e.g. raw iBeacons).
        val zeroUuid  = ParcelUuid.fromString("00000000-0000-0000-0000-000000000000")
        val allMatchFilter = ScanFilter.Builder().setServiceUuid(zeroUuid, zeroUuid).build()
        return listOf(allMatchFilter, ScanFilter.Builder().build())
    }

    // ── PendingIntent scan ─────────────────────────────────────────────────────
    // Registered with the system so scan results are delivered to BleScanReceiver
    // even if the app process is killed. Acts as a safety net / recovery trigger.

    private fun startPendingIntentScan() {
        if (!hasScanPermission()) return
        val scanner = bluetoothLeScanner ?: return

        val intent = Intent(context, BleScanReceiver::class.java).apply {
            action = BleScanReceiver.ACTION_BLE_SCAN_RESULT
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        pendingIntentScan = pi

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(buildScanFilters(), settings, pi)
            Timber.i("BLE PendingIntent scan started (LOW_POWER fallback)")
        } catch (e: SecurityException) {
            Timber.e("BLE PI scan SecurityException: $e")
            pendingIntentScan = null
        } catch (e: Exception) {
            Timber.e("BLE PI scan failed: $e")
            pendingIntentScan = null
        }
    }

    private fun stopPendingIntentScan() {
        val pi = pendingIntentScan ?: return
        try {
            if (hasScanPermission()) bluetoothLeScanner?.stopScan(pi)
            Timber.i("BLE PendingIntent scan stopped")
        } catch (e: Exception) {
            Timber.e("BLE PI stopScan error: $e")
        }
        pendingIntentScan = null
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // API 26-30: BLUETOOTH is a normal permission (auto-granted when declared in manifest)
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isLocationEnabled ?: true  // assume OK if can't check
    }
}
