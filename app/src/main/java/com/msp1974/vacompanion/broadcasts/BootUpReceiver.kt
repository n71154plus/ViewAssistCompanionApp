package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.msp1974.vacompanion.service.VAForegroundService
import timber.log.Timber

class BootUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
                action == "android.intent.action.QUICKBOOT_POWERON"
        if (!isBootAction) return

        Timber.d("Received boot intent: $action")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val startOnBoot = sharedPreferences.getBoolean("startOnBoot", false)
        if (startOnBoot) {
            Timber.d("Starting foreground service on boot")
            // Start the Service (not Activity) — background Activity launches are restricted on API 29+.
            // The service's activity watchdog will bring up MainActivity once the service is running.
            val serviceIntent = Intent(context, VAForegroundService::class.java).apply {
                setAction(VAForegroundService.Actions.START.toString())
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}