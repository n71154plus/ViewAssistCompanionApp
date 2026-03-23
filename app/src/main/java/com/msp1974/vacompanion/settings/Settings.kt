package com.msp1974.vacompanion.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Build.UNKNOWN
import android.provider.Settings.Secure
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.google.android.gms.common.util.ClientLibraryUtils.getPackageInfo
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventNotifier
import com.msp1974.vacompanion.utils.Logger
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

enum class BackgroundTaskStatus {
    NOT_STARTED,
    STARTING,
    STARTED,
}

enum class PageLoadingStage {
    NOT_STARTED,
    STARTED,
    AUTHORISING,
    AUTHORISED,
    LOADED,
    AUTH_FAILED,
}

class APPConfig(val context: Context) {
    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val log = Logger()
    var eventBroadcaster: EventNotifier
    private var prefListener: Unit

    init {
        prefListener = sharedPrefs.registerOnSharedPreferenceChangeListener { prefs, key ->
            onSharedPreferenceChangedListener(prefs, key)
        }
        eventBroadcaster = EventNotifier()
    }

    // Constant values
    val name = NAME
    val version = getPackageInfo(context, context.packageName)?.versionName.toString()
    val serverPort = SERVER_PORT

    // Versions
    var integrationVersion: String = "0.0.0"
    var minRequiredApkVersion: String = version


    // In memory only settings
    var initSettings: Boolean = false
    var homeAssistantConnectedIP: String = ""
    var homeAssistantHTTPPort: Int = DEFAULT_HA_HTTP_PORT
    var homeAssistantURL: String = ""
    var homeAssistantDashboard: String = ""

    var sampleRate: Int = 16000
    var audioChannels: Int = 1
    var audioWidth: Int = 2

    //var connectionCount: Int = 0
    var atomicConnectionCount: AtomicInteger = AtomicInteger(0)
    var currentActivity: String = ""
    var backgroundTaskRunning: Boolean = false
    var backgroundTaskStatus: BackgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED
    var isRunning: Boolean = false

    var hasRecordAudioPermission: Boolean = false
    var hasPostNotificationPermission: Boolean = false
    var hasWriteExternalStoragePermission: Boolean = false
    var hasCameraPermission: Boolean = false

    var ignoreSSLErrors: Boolean = alwaysIgnoreSSLErrors

    //In memory settings with change notification
    var useAdvancedGain: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordEngine: String by Delegates.observable("openwakeword") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWord: String by Delegates.observable(DEFAULT_WAKE_WORD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordSound: String by Delegates.observable(DEFAULT_WAKE_WORD_SOUND) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var rawProximitySensorThreshold: Int by Delegates.observable(DEFAULT_RAW_PROXIMITY_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var wakeWordThreshold: Float by Delegates.observable(DEFAULT_WAKE_WORD_THRESHOLD) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var continueConversation: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var notificationVolume: Int by Delegates.observable(DEFAULT_NOTIFICATION_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var musicVolume: Int by Delegates.observable(DEFAULT_MUSIC_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var duckingVolume: Int by Delegates.observable(DEFAULT_DUCKING_VOLUME) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var isMuted: Boolean by Delegates.observable(DEFAULT_MUTE) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var micGain: Int by Delegates.observable(DEFAULT_MIC_GAIN) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenBrightness: Float by Delegates.observable(DEFAULT_SCREEN_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAutoBrightness: Boolean by Delegates.observable(DEFAULT_SCREEN_AUTO_BRIGHTNESS) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var swipeRefresh: Boolean by Delegates.observable(DEFAULT_SWIPE_REFRESH) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenAlwaysOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var doNotDisturb: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var darkMode: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var diagnosticsEnabled: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var pairedDeviceID: String by Delegates.observable(pairedDeviceId) { property, oldValue, newValue ->
        pairedDeviceId = newValue
        onValueChangedListener(property, oldValue, newValue)
    }

    var zoomLevel: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnWakeWord: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnBump: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnProximity: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOnMotion: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOn: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableNetworkRecovery: Boolean by Delegates.observable(true) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var enableMotionDetection: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var motionDetectionSensitivity: Int by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var currentPath: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastMotion: String by Delegates.observable("") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var lastActivity: Long by Delegates.observable(0) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenTimeout: Int by Delegates.observable(3000) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var bumpSensitivity: Float by Delegates.observable(0.1f) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenSaver: Boolean by Delegates.observable(false) { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }

    var screenOrientationMode: String by Delegates.observable("auto") { property, oldValue, newValue ->
        onValueChangedListener(property, oldValue, newValue)
    }





    // SharedPreferences
    var canSetScreenWritePermission: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_screen_write_permission", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_screen_write_permission", value) }

    var canSetNotificationPolicyAccess: Boolean
        get() = this.sharedPrefs.getBoolean("can_set_notification_policy_access", true)
        set(value) = this.sharedPrefs.edit { putBoolean("can_set_notification_policy_access", value) }

    var startOnBoot: Boolean
        get() = this.sharedPrefs.getBoolean("startOnBoot", false)
        set(value) = this.sharedPrefs.edit { putBoolean("startOnBoot", value) }

    var uuid: String
        get() = this.sharedPrefs.getString("uuid", getUUID()) ?: ""
        set(value) = this.sharedPrefs.edit { putString("uuid", value) }

    var accessToken: String
        get() = this.sharedPrefs.getString("auth_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("auth_token", value) }

    var refreshToken: String
        get() = this.sharedPrefs.getString("refresh_token", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("refresh_token", value) }

    var tokenExpiry: Long
        get() = this.sharedPrefs.getLong("token_expiry", 0)
        set(value) = this.sharedPrefs.edit { putLong("token_expiry", value) }

    private var pairedDeviceId: String
        get() = this.sharedPrefs.getString("paired_device_id", "") ?: ""
        set(value) = this.sharedPrefs.edit { putString("paired_device_id", value) }

    var alwaysIgnoreSSLErrors: Boolean
        get() = this.sharedPrefs.getBoolean("always_ignore_ssl_errors", false)
        set(value) = this.sharedPrefs.edit { putBoolean("always_ignore_ssl_errors", value) }

    var httpServerEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("http_server_enabled", true)
        set(value) {
            this.sharedPrefs.edit { putBoolean("http_server_enabled", value) }
            eventBroadcaster.notifyEvent(Event("httpServerEnabled", !value, value))
        }

    var iconServerEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("icon_server_enabled", true)
        set(value) {
            this.sharedPrefs.edit { putBoolean("icon_server_enabled", value) }
            eventBroadcaster.notifyEvent(Event("iconServerEnabled", !value, value))
        }

    var mjpegStreamEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("mjpeg_stream_enabled", false)
        set(value) {
            this.sharedPrefs.edit { putBoolean("mjpeg_stream_enabled", value) }
            eventBroadcaster.notifyEvent(Event("mjpegStreamEnabled", !value, value))
        }

    var mjpegFps: Int
        get() = this.sharedPrefs.getInt("mjpeg_fps", MJPEG_DEFAULT_FPS)
        set(value) {
            this.sharedPrefs.edit { putInt("mjpeg_fps", value) }
            eventBroadcaster.notifyEvent(Event("mjpegFps", mjpegFps, value))
        }

    var mjpegQuality: Int
        get() = this.sharedPrefs.getInt("mjpeg_quality", 70)
        set(value) {
            this.sharedPrefs.edit { putInt("mjpeg_quality", value) }
            eventBroadcaster.notifyEvent(Event("mjpegQuality", mjpegQuality, value))
        }

    // Extra rotation applied on top of sensor orientation correction (0/90/180/270)

    var bleProxyEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("ble_proxy_enabled", false)
        set(value) {
            this.sharedPrefs.edit { putBoolean("ble_proxy_enabled", value) }
            eventBroadcaster.notifyEvent(Event("bleProxyEnabled", !value, value))
        }

    var bleScanMode: Int
        get() = this.sharedPrefs.getInt("ble_scan_mode", BLE_DEFAULT_SCAN_MODE)
        set(value) {
            this.sharedPrefs.edit { putInt("ble_scan_mode", value) }
            eventBroadcaster.notifyEvent(Event("bleScanMode", bleScanMode, value))
        }

    var bleRssiThreshold: Int
        get() = this.sharedPrefs.getInt("ble_rssi_threshold", BLE_DEFAULT_RSSI_THRESHOLD)
        set(value) {
            this.sharedPrefs.edit { putInt("ble_rssi_threshold", value) }
            eventBroadcaster.notifyEvent(Event("bleRssiThreshold", bleRssiThreshold, value))
        }

    var bleBatchIntervalMs: Long
        get() = this.sharedPrefs.getLong("ble_batch_interval_ms", BLE_BATCH_INTERVAL_MS)
        set(value) {
            this.sharedPrefs.edit { putLong("ble_batch_interval_ms", value) }
            eventBroadcaster.notifyEvent(Event("bleBatchIntervalMs", bleBatchIntervalMs, value))
        }

    var bleUuidFilter: String
        get() = this.sharedPrefs.getString("ble_uuid_filter", "") ?: ""
        set(value) {
            this.sharedPrefs.edit { putString("ble_uuid_filter", value) }
            eventBroadcaster.notifyEvent(Event("bleUuidFilter", bleUuidFilter, value))
        }

    var recentAppsCount: Int
        get() = this.sharedPrefs.getInt("recent_apps_count", 10)
        set(value) {
            this.sharedPrefs.edit { putInt("recent_apps_count", value) }
            eventBroadcaster.notifyEvent(Event("recentAppsCount", recentAppsCount, value))
        }

    var frequentAppsCount: Int
        get() = this.sharedPrefs.getInt("frequent_apps_count", 10)
        set(value) {
            this.sharedPrefs.edit { putInt("frequent_apps_count", value) }
            eventBroadcaster.notifyEvent(Event("frequentAppsCount", frequentAppsCount, value))
        }

    var recentAppsEnabled: Boolean
        get() = this.sharedPrefs.getBoolean("recent_apps_enabled", false)
        set(value) {
            this.sharedPrefs.edit { putBoolean("recent_apps_enabled", value) }
            eventBroadcaster.notifyEvent(Event("recentAppsEnabled", !value, value))
        }

    fun processSettings(settingString: String) {
        initSettings = true
        val settings = JSONObject(settingString)
        if (settings.has("ha_port")) {
            homeAssistantHTTPPort = settings["ha_port"] as Int
        }
        if (settings.has("ha_url")) {
            homeAssistantURL = settings["ha_url"] as String
        }
        if (settings.has("ha_dashboard")) {
            homeAssistantDashboard = settings["ha_dashboard"] as String
        }
        if (settings.has("advanced_gain")) {
            useAdvancedGain = settings["advanced_gain"] as Boolean
        }
        if (settings.has("wake_word_engine")) {
            wakeWordEngine = settings["wake_word_engine"] as String
        }
        if (settings.has("wake_word")) {
            wakeWord = settings["wake_word"] as String
        }
        if (settings.has("wake_word_sound")) {
            wakeWordSound = settings["wake_word_sound"] as String
        }
        if (settings.has("wake_word_threshold")) {
            wakeWordThreshold = settings.getInt("wake_word_threshold").toFloat() / 10
        }
        if (settings.has("raw_proximity_threshold")) {
            rawProximitySensorThreshold = settings.getInt("raw_proximity_threshold")
        }
        if (settings.has("continue_conversation")) {
            continueConversation = settings["continue_conversation"] as Boolean
        }
        if (settings.has("notification_volume")) {
            notificationVolume = settings.getInt("notification_volume")
        }
        if (settings.has("music_volume")) {
            musicVolume = settings.getInt("music_volume")
        }
        if (settings.has("ducking_volume")) {
            duckingVolume = settings.getInt("ducking_volume")
        }
        if (settings.has("mic_gain")) {
            micGain = settings.getInt("mic_gain")
        }
        if (settings.has("mute")) {
            isMuted = settings["mute"] as Boolean
        }
        if (settings.has("screen_brightness")) {
            screenBrightness = settings.getInt("screen_brightness").toFloat() / 100
        }
        if (settings.has("screen_auto_brightness")) {
            screenAutoBrightness = settings.getBoolean("screen_auto_brightness")
        }
        if (settings.has("swipe_refresh")) {
            swipeRefresh = settings.getBoolean("swipe_refresh")
        }
        if (settings.has("screen_always_on")) {
            screenAlwaysOn = settings.getBoolean("screen_always_on")
        }
        if (settings.has("do_not_disturb")) {
            doNotDisturb = settings.getBoolean("do_not_disturb")
        }
        if (settings.has("dark_mode")) {
            darkMode = settings.getBoolean("dark_mode")
        }
        if (settings.has("diagnostics_enabled")) {
            diagnosticsEnabled = settings.getBoolean("diagnostics_enabled")
        }
        if (settings.has("integration_version")) {
            integrationVersion = settings.getString("integration_version")
        }
        if (settings.has("min_required_apk_version")) {
            minRequiredApkVersion = settings.getString("min_required_apk_version")
        }
        if (settings.has("zoom_level")) {
            zoomLevel = settings.getInt("zoom_level")
        }
        if (settings.has("screen_on_wake_word")) {
            screenOnWakeWord = settings.getBoolean("screen_on_wake_word")
        }
        if (settings.has("screen_on_bump")) {
            screenOnBump = settings.getBoolean("screen_on_bump")
        }
        if (settings.has("screen_on_proximity")) {
            screenOnProximity = settings.getBoolean("screen_on_proximity")
        }
        if (settings.has("screen_on_motion")) {
            screenOnMotion = settings.getBoolean("screen_on_motion")
        }
        if (settings.has("screen_on")) {
            screenOn = settings.getBoolean("screen_on")
        }
        if (settings.has("enable_network_recovery")) {
            enableNetworkRecovery = settings.getBoolean("enable_network_recovery")
        }
        if (settings.has("enable_motion_detection")) {
            enableMotionDetection = settings.getBoolean("enable_motion_detection")
        }
        if (settings.has("motion_detection_sensitivity")) {
            motionDetectionSensitivity = settings.getInt("motion_detection_sensitivity")
        }
        if (settings.has("screen_timeout")) {
            screenTimeout = settings.getInt("screen_timeout") * 1000
        }
        if (settings.has("bump_sensitivity")) {
            bumpSensitivity = settings.getInt("bump_sensitivity").toFloat() / 10
        }
        if (settings.has("screen_saver")) {
            screenSaver = settings.getBoolean("screen_saver")
        }
        if (settings.has("screen_orientation_mode")) {
            screenOrientationMode = settings.getString("screen_orientation_mode")
        }
        if (settings.has("http_server_enabled")) {
            httpServerEnabled = settings.getBoolean("http_server_enabled")
        }
        if (settings.has("icon_server_enabled")) {
            iconServerEnabled = settings.getBoolean("icon_server_enabled")
        }
        if (settings.has("mjpeg_stream_enabled")) {
            mjpegStreamEnabled = settings.getBoolean("mjpeg_stream_enabled")
        }
        if (settings.has("mjpeg_fps")) {
            mjpegFps = settings.getInt("mjpeg_fps")
        }
        if (settings.has("mjpeg_quality")) {
            mjpegQuality = settings.getInt("mjpeg_quality")
        }
        if (settings.has("ble_proxy_enabled")) {
            bleProxyEnabled = settings.getBoolean("ble_proxy_enabled")
        }
        if (settings.has("ble_scan_mode")) {
            bleScanMode = settings.getInt("ble_scan_mode")
        }
        if (settings.has("ble_rssi_threshold")) {
            bleRssiThreshold = settings.getInt("ble_rssi_threshold")
        }
        if (settings.has("ble_batch_interval_ms")) {
            bleBatchIntervalMs = settings.getLong("ble_batch_interval_ms")
        }
        if (settings.has("ble_uuid_filter")) {
            bleUuidFilter = settings.getString("ble_uuid_filter")
        }
        if (settings.has("recent_apps_count")) {
            recentAppsCount = settings.getInt("recent_apps_count")
        }
        if (settings.has("frequent_apps_count")) {
            frequentAppsCount = settings.getInt("frequent_apps_count")
        }
        if (settings.has("recent_apps_enabled")) {
            recentAppsEnabled = settings.getBoolean("recent_apps_enabled")
        }

        Firebase.crashlytics.log("Settings update")
        // Signal that a full settings batch has been applied
        eventBroadcaster.notifyEvent(Event("settingsApplied", "", ""))
    }

    @SuppressLint("HardwareIds")
    private fun getUUID(): String {
        if (Build.SERIAL != UNKNOWN) {
            if (Build.MANUFACTURER.lowercase() != "google") {
                return "${Build.MANUFACTURER}-${Build.SERIAL}".lowercase()
            } else {
                return "${Build.SERIAL}".lowercase()
            }
        }
        val aId = Secure.getString(context.applicationContext.contentResolver, Secure.ANDROID_ID)
        if (aId != null) {
            return aId.slice(0..8)
        }
        val uid = UUID.randomUUID().toString()
        return uid.slice(0..8)

    }

    fun onSharedPreferenceChangedListener(prefs: SharedPreferences, key: String?) {
        log.d("SharedPreference changed: $key")
        val event = Event(key.toString(), "", "")
        Firebase.crashlytics.log("${key.toString()} changed")
        eventBroadcaster.notifyEvent(event)
    }

    fun onValueChangedListener(property: KProperty<*>, oldValue: Any, newValue: Any) {
        if (oldValue != newValue) {
            val event = Event(property.name, oldValue, newValue)
            Firebase.crashlytics.log("${property.name} changed from $oldValue to $newValue")
            eventBroadcaster.notifyEvent(event)
        }
    }

    companion object {
        const val NAME = "VACA"
        const val SERVER_PORT = 10800
        const val DEFAULT_HA_HTTP_PORT = 8123
        const val DEFAULT_RAW_PROXIMITY_THRESHOLD = 300
        const val DEFAULT_WAKE_WORD = "hey_jarvis"
        const val DEFAULT_WAKE_WORD_SOUND = "none"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.6f
        const val DEFAULT_NOTIFICATION_VOLUME = 10
        const val DEFAULT_MUSIC_VOLUME = 10
        const val DEFAULT_SCREEN_BRIGHTNESS = 0.5f
        const val DEFAULT_SCREEN_AUTO_BRIGHTNESS = true
        const val DEFAULT_SWIPE_REFRESH = true
        const val DEFAULT_DUCKING_VOLUME = 2
        const val DEFAULT_MUTE = false
        const val DEFAULT_MIC_GAIN = 0
        const val GITHUB_API_URL = "https://api.github.com/repos/msp1974/ViewAssist_Companion_App/releases"
        const val HTTP_SERVER_PORT = 8080
        const val MJPEG_DEFAULT_FPS = 10
        const val BLE_BATCH_INTERVAL_MS = 500L
        const val BLE_DEFAULT_RSSI_THRESHOLD = -100
        const val BLE_DEFAULT_SCAN_MODE = 2

        @Volatile
        private var instance: APPConfig? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: APPConfig(context).also { instance = it }
            }
    }
}