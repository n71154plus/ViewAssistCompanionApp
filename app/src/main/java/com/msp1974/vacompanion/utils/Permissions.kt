package com.msp1974.vacompanion.utils

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.DEVICE_POLICY_SERVICE
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.VACADeviceAdminReceiver
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

class Permissions(val context: Context) {

    companion object {
        const val CAMERA = Manifest.permission.CAMERA
        const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
        const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
        @RequiresApi(Build.VERSION_CODES.S)
        const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
        @RequiresApi(Build.VERSION_CODES.S)
        const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val ACCESS_FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
        @RequiresApi(Build.VERSION_CODES.Q)
        const val ACCESS_BACKGROUND_LOCATION = Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }

    fun hasCorePermissions(): Boolean {
        val permissions = mutableListOf(RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(POST_NOTIFICATIONS)
        }

        for (permission in permissions) {
            if (!hasPermission(permission)) {
                return false
            }
        }
        return true
    }

    fun hasUsageAccessOptional(): Boolean = hasUsageAccess()

    fun hasOptionalPermissions(): Boolean {
        val permissions = mutableListOf(WRITE_EXTERNAL_STORAGE)
        if (DeviceCapabilitiesManager(context).hasFrontCamera()) {
            permissions.add(CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(BLUETOOTH_SCAN)
            permissions.add(BLUETOOTH_CONNECT)
        }
        // ACCESS_FINE_LOCATION is required for BLE scanning on all API levels
        permissions.add(ACCESS_FINE_LOCATION)
        // ACCESS_BACKGROUND_LOCATION is required on Android 10-11 for background BLE scanning.
        // Android 12+ uses BLUETOOTH_SCAN + connectedDevice foreground service type instead.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            permissions.add(ACCESS_BACKGROUND_LOCATION)
        }

        for (permission in permissions) {
            if (!hasPermission(permission)) {
                return false
            }
        }

        if (!hasWriteSettingsPermission()) {
            return false
        }

        if (!hasNotificationAccessPolicyPermission()) {
            return false
        }

        if (!isDeviceAdmin()) {
            return false
        }

        return true
    }

    fun hasAllPermissions(): Boolean {
        return hasCorePermissions() && hasOptionalPermissions()
    }

    fun hasPermission(permission: String): Boolean {
        val result = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        Timber.d("Permission $permission = $result")
        return result

    }


    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    fun hasWriteSettingsPermission(): Boolean {
        val result = Settings.System.canWrite(context)
        Timber.d("Write settings permission = $result")
        return result
    }

    fun hasNotificationAccessPolicyPermission(): Boolean {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val config = APPConfig.getInstance(context)
        var result = false

        if (!config.canSetNotificationPolicyAccess) {
            result = true
        } else {
            result = notificationManager.isNotificationPolicyAccessGranted
        }
        Timber.d("Notification access policy permission = $result")
        return result
    }

    fun isDeviceAdmin(): Boolean {
        val dpm: DevicePolicyManager = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val mDeviceAdmin = ComponentName(context, VACADeviceAdminReceiver::class.java)
        return dpm.isAdminActive(mDeviceAdmin)
    }
}