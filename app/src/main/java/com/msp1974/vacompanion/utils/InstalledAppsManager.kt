package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber

data class AppInfo(
    val packageName: String,
    val label: String,
    val category: String,
    val iconUrl: String
)

class InstalledAppsManager(private val context: Context) {

    private var cache: List<AppInfo>? = null

    fun getAllApps(deviceIp: String, iconServerPort: Int = 8080): List<AppInfo> {
        cache?.let { return it }
        val result = buildAppList(deviceIp, iconServerPort)
        cache = result
        return result
    }

    fun invalidateCache() {
        cache = null
    }

    private fun buildAppList(deviceIp: String, iconServerPort: Int): List<AppInfo> {
        val pm = context.packageManager
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .distinctBy { it.packageName }
                .mapNotNull { activityInfo ->
                    try {
                        val appInfo = pm.getApplicationInfo(activityInfo.packageName, 0)
                        AppInfo(
                            packageName = activityInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            category = getCategory(appInfo),
                            iconUrl = "http://$deviceIp:$iconServerPort/icon?pkg=${activityInfo.packageName}"
                        )
                    } catch (e: PackageManager.NameNotFoundException) { null }
                    catch (e: Exception) {
                        Timber.e("InstalledAppsManager error for ${activityInfo.packageName}: $e")
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            Timber.e("InstalledAppsManager.buildAppList error: $e")
            emptyList()
        }
    }

    private fun getCategory(ai: ApplicationInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return when (ai.category) {
                ApplicationInfo.CATEGORY_GAME -> "game"
                ApplicationInfo.CATEGORY_AUDIO -> "audio"
                ApplicationInfo.CATEGORY_VIDEO -> "video"
                ApplicationInfo.CATEGORY_IMAGE -> "image"
                ApplicationInfo.CATEGORY_SOCIAL -> "social"
                ApplicationInfo.CATEGORY_NEWS -> "news"
                ApplicationInfo.CATEGORY_MAPS -> "maps"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
                else -> "other"
            }
        }
        val pkg = ai.packageName.lowercase()
        return when {
            listOf("game", "arcade", "puzzle").any { pkg.contains(it) } -> "game"
            listOf("music", "spotify", "youtube", "video", "player", "media").any { pkg.contains(it) } -> "media"
            listOf("map", "navigation", "gps", "waze", "uber").any { pkg.contains(it) } -> "maps"
            listOf("social", "facebook", "twitter", "instagram", "whatsapp", "telegram").any { pkg.contains(it) } -> "social"
            listOf("news", "reader", "rss").any { pkg.contains(it) } -> "news"
            else -> "other"
        }
    }
}
