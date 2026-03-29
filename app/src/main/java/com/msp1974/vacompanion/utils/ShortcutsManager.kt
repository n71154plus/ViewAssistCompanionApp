package com.msp1974.vacompanion.utils

import com.msp1974.vacompanion.settings.APPConfig
import org.json.JSONArray

class ShortcutsManager(private val config: APPConfig) {

    fun getPinnedShortcuts(): List<String> {
        return try {
            val arr = JSONArray(config.pinnedShortcuts)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun pinShortcut(packageName: String) {
        val list = getPinnedShortcuts().toMutableList()
        if (!list.contains(packageName)) {
            list.add(packageName)
            save(list)
        }
    }

    fun unpinShortcut(packageName: String) {
        val list = getPinnedShortcuts().toMutableList()
        if (list.remove(packageName)) {
            save(list)
        }
    }

    fun reorderShortcuts(ordered: List<String>) {
        save(ordered)
    }

    fun isPinned(packageName: String): Boolean = getPinnedShortcuts().contains(packageName)

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        config.pinnedShortcuts = arr.toString()
    }
}
