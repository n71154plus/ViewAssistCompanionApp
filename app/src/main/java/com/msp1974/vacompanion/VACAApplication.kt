package com.msp1974.vacompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.msp1974.vacompanion.utils.ActivityManager
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import timber.log.Timber
import timber.log.Timber.DebugTree

class VACAApplication: Application() {
    override fun onCreate() {
        super.onCreate()

        activityManager = ActivityManager(this)
        DeviceCapabilitiesManager.appContext = this

        Timber.plant(DebugTree())

        // Create the notification channel (required for Android 8.0 and above)
        val channel = NotificationChannel(
            "VACAForegroundServiceChannelId",
            "VACA Foreground Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        // service provided by Android Operating system to show notification outside of our app
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        lateinit var activityManager: ActivityManager
    }
}