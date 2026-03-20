package com.msp1974.vacompanion.sensors

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import android.util.Base64

typealias BleAdvertisementCallback = (JsonObject) -> Unit

class BleScanner(private val context: Context) {

    private val config = APPConfig.getInstance(context)
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    var onAdvertisement: BleAdvertisementCallback? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!config.bleProxyEnabled) return
            try {
                val device = result.device
                val record = result.scanRecord
                val data = buildJsonObject {
                    put("address", device.address)
                    put("rssi", result.rssi)
                    put("local_name", record?.deviceName ?: "")
                    putJsonArray("service_uuids") {
                        record?.serviceUuids?.forEach { add(it.uuid.toString()) }
                    }
                    put("manufacturer_data", buildJsonObject {
                        record?.manufacturerSpecificData?.let { sparse ->
                            for (i in 0 until sparse.size()) {
                                val key = sparse.keyAt(i)
                                val value = sparse.valueAt(i)
                                put(key.toString(), Base64.encodeToString(value, Base64.NO_WRAP))
                            }
                        }
                    })
                    put("service_data", buildJsonObject {
                        record?.serviceData?.forEach { (uuid, bytes) ->
                            put(uuid.uuid.toString(), Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    })
                    put("tx_power", record?.txPowerLevel ?: -1)
                }
                onAdvertisement?.invoke(data)
            } catch (e: Exception) {
                Timber.e("BLE scan result error: $e")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: $errorCode")
            isScanning = false
        }
    }

    fun start() {
        if (isScanning) return
        if (!hasPermission()) { Timber.w("BLE scan permission not granted"); return }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = bluetoothManager?.adapter ?: run {
            Timber.w("Bluetooth not available"); return
        }
        if (!adapter.isEnabled) { Timber.w("Bluetooth not enabled"); return }

        bluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            Timber.w("BLE scanner not available"); return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        bluetoothLeScanner?.startScan(null, settings, scanCallback)
        isScanning = true
        Timber.d("BLE scanning started")
    }

    fun stop() {
        if (!isScanning) return
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            Timber.e("BLE stop scan error: $e")
        }
        isScanning = false
        Timber.d("BLE scanning stopped")
    }

    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
