package com.msp1974.vacompanion.sensors

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber

/**
 * Receives BLE scan results delivered via PendingIntent by the system.
 * Fires even when the app process has been killed, making it the safety-net
 * for continuous scanning. It does two things:
 *  1. Routes results to the active BleScanner.onAdvertisement callback (if alive).
 *  2. Restarts VAForegroundService when it is no longer running.
 */
class BleScanReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BLE_SCAN_RESULT = "com.msp1974.vacompanion.BLE_SCAN_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BLE_SCAN_RESULT) return

        val config = APPConfig.getInstance(context)
        if (!config.bleProxyEnabled) return

        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            ) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: emptyList()
        }

        Timber.i("BleScanReceiver: received ${results.size} result(s) via PendingIntent")

        val callback = BleScanner.instance?.onAdvertisement
        val threshold = config.bleRssiThreshold

        results.forEach { result ->
            try {
                val rssi = result.rssi
                if (rssi < threshold) return@forEach

                val device = result.device
                val record = result.scanRecord
                val address = device.address
                val name = record?.deviceName ?: ""
                val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
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
                    put("tx_power", txPower)
                }

                if (callback != null) {
                    callback.invoke(data)
                } else {
                    Timber.w("BleScanReceiver: adv from $address — no active callback (service restarting?)")
                }

                // Update the scanner's nearby map and result counter so the heartbeat log
                // reflects PendingIntent hits during screen-off (callback is suppressed then).
                BleScanner.instance?.recordPiResult(BleDeviceInfo(
                    address = address, name = name, rssi = rssi,
                    lastSeen = System.currentTimeMillis(),
                    serviceUuids = serviceUuids,
                    manufacturerId = manufacturerDataMap.keys.firstOrNull(),
                    txPower = txPower,
                    manufacturerData = manufacturerDataMap,
                    serviceData = serviceDataMap,
                ))
            } catch (e: Exception) {
                Timber.e("BleScanReceiver: error processing result: $e")
            }
        }

        // If the foreground service is gone, restart it.
        // Receiving a BLE PendingIntent result is an allowed exemption from Android 12+
        // background start restrictions (system-delivered callback context).
        if (!config.backgroundTaskRunning) {
            Timber.i("BleScanReceiver: ForegroundService not running — restarting")
            try {
                val serviceIntent = Intent(context, VAForegroundService::class.java).apply {
                    action = VAForegroundService.Actions.START.toString()
                }
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Timber.e("BleScanReceiver: failed to restart ForegroundService: $e")
            }
        }
    }
}
