package com.msp1974.vacompanion.utils

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import timber.log.Timber

data class RecentAppInfo(
    val packageName: String,
    val label: String,
    val lastUsed: Long,       // epoch ms
    val useCount: Int,        // usage count in last 30 days
    val category: String,
)

class RecentAppsManager(private val context: Context) {

    fun hasUsagePermission(): Boolean {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30L * 24 * 60 * 60 * 1000, now)
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) { false }
    }

    /**
     * Returns the most recently used launchable apps.
     * @param count max number of apps to return
     * @param excludeSelf exclude VACA itself
     */
    fun getRecentApps(count: Int, excludeSelf: Boolean = true): List<RecentAppInfo> {
        val pm = context.packageManager

        // Get the set of launchable packages
        val launchablePackages = getLaunchablePackages(pm)
        if (launchablePackages.isEmpty()) return emptyList()

        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

            // Query usage stats over the last 30 days
            val statsMap = usm.queryAndAggregateUsageStats(thirtyDaysAgo, now)
            if (statsMap.isNullOrEmpty()) return emptyList()

            statsMap.values
                .filter { stats ->
                    stats.lastTimeUsed > 0 &&
                    stats.packageName in launchablePackages &&
                    (!excludeSelf || stats.packageName != context.packageName)
                }
                .sortedByDescending { it.lastTimeUsed }
                .take(count)
                .mapNotNull { stats ->
                    try {
                        val ai = pm.getApplicationInfo(stats.packageName, 0)
                        val label = pm.getApplicationLabel(ai).toString()
                        val category = getCategory(ai)
                        val useCount = stats.totalTimeInForeground.let {
                            // Estimate count: total foreground time / avg session (60s)
                            (it / 60_000).coerceAtLeast(1).toInt()
                        }
                        RecentAppInfo(
                            packageName = stats.packageName,
                            label = label,
                            lastUsed = stats.lastTimeUsed,
                            useCount = useCount,
                            category = category,
                        )
                    } catch (e: PackageManager.NameNotFoundException) { null }
                    catch (e: Exception) { Timber.e("RecentApps error for ${stats.packageName}: $e"); null }
                }
        } catch (e: SecurityException) {
            Timber.w("Usage stats permission not granted")
            emptyList()
        } catch (e: Exception) {
            Timber.e("getRecentApps error: $e")
            emptyList()
        }
    }

    /**
     * Returns top frequently used apps sorted by use count (descending).
     */
    fun getFrequentApps(count: Int, excludeSelf: Boolean = true): List<RecentAppInfo> {
        val pm = context.packageManager
        val launchablePackages = getLaunchablePackages(pm)

        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000
            val statsMap = usm.queryAndAggregateUsageStats(thirtyDaysAgo, now)
            if (statsMap.isNullOrEmpty()) return emptyList()

            statsMap.values
                .filter { stats ->
                    stats.totalTimeInForeground > 0 &&
                    stats.packageName in launchablePackages &&
                    (!excludeSelf || stats.packageName != context.packageName)
                }
                .sortedByDescending { it.totalTimeInForeground }
                .take(count)
                .mapNotNull { stats ->
                    try {
                        val ai = pm.getApplicationInfo(stats.packageName, 0)
                        RecentAppInfo(
                            packageName = stats.packageName,
                            label = pm.getApplicationLabel(ai).toString(),
                            lastUsed = stats.lastTimeUsed,
                            useCount = (stats.totalTimeInForeground / 60_000).coerceAtLeast(1).toInt(),
                            category = getCategory(ai),
                        )
                    } catch (e: Exception) { null }
                }
        } catch (e: Exception) {
            Timber.e("getFrequentApps error: $e")
            emptyList()
        }
    }

    private fun getLaunchablePackages(pm: PackageManager): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) { emptySet() }
    }

    private fun getCategory(ai: android.content.pm.ApplicationInfo): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return when (ai.category) {
                android.content.pm.ApplicationInfo.CATEGORY_GAME -> "game"
                android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "audio"
                android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "video"
                android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "image"
                android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "social"
                android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "news"
                android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "maps"
                android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
                android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
                else -> "other"
            }
        }
        val pkg = ai.packageName.lowercase()
        return when {
            listOf("game","play","arcade","puzzle").any { pkg.contains(it) } -> "game"
            listOf("music","spotify","youtube","video","player","media").any { pkg.contains(it) } -> "media"
            listOf("map","navigation","gps","waze","uber").any { pkg.contains(it) } -> "maps"
            listOf("social","facebook","twitter","instagram","whatsapp","telegram","line").any { pkg.contains(it) } -> "social"
            listOf("news","reader","rss").any { pkg.contains(it) } -> "news"
            else -> "other"
        }
    }
}
