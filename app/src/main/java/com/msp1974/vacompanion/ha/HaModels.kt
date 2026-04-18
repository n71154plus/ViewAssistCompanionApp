package com.msp1974.vacompanion.ha

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject

// ── Entity state ──────────────────────────────────────────────────────────────

data class HaState(
    val entityId: String,
    val state: String,
    val attributes: JSONObject
) {
    val domain: String     get() = entityId.substringBefore(".")
    val objectId: String   get() = entityId.substringAfter(".")
    val friendlyName: String get() = attributes.optString("friendly_name", objectId)
    val isOn: Boolean      get() = state == "on"
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
}

// ── Room definition ───────────────────────────────────────────────────────────

data class HaRoom(
    val id: String,
    val nameZh: String,
    val icon: ImageVector,
    /** HA entity_id of the room's snapshot camera, null if none. */
    val cameraEntity: String? = null,
    /**
     * Must match Home Assistant **area_id** (Settings → Areas) when filtering by registry.
     * Defaults to [id] so existing room ids can align with HA area slugs.
     */
    val haAreaId: String = id
)

private fun cam(roomId: String) = "camera.${roomId}_still"

val HA_ROOMS = listOf(
    HaRoom("house",          "全室",  Icons.Default.Home,         null),
    HaRoom("livingroom",     "客廳",  Icons.Default.Weekend,      cam("livingroom")),
    HaRoom("restaurant",     "餐廳",  Icons.Default.Restaurant,   cam("restaurant")),
    HaRoom("bedroom",        "主臥",  Icons.Default.Bedtime,      cam("bedroom")),
    HaRoom("guestroom",      "客房",  Icons.Default.Hotel,        cam("guestroom")),
    HaRoom("studyroom",      "書房",  Icons.Default.Book,         cam("studyroom")),
    HaRoom("terrace",        "露台",  Icons.Default.LocalFlorist, cam("terrace")),
    HaRoom("entryway",       "玄關",  Icons.Default.MeetingRoom,  cam("entryway")),
    HaRoom("bedroom_toilet", "主臥廁", Icons.Default.Bathtub,     null),
    HaRoom("toilet",         "廁所",  Icons.Default.Wc,           null),
    HaRoom("balcony",        "陽台",  Icons.Default.Deck,         null),
    HaRoom("dressingroom",   "更衣室", Icons.Default.Style,       cam("dressingroom")),
)

// ── Filtering helpers ─────────────────────────────────────────────────────────

/**
 * Legacy grouping by `entity_id` object name: `roomId` or `roomId_*`.
 * Used for **Lights / Climate / Media** tabs ([byRoomForDomain]); independent of HA area registry.
 */
fun List<HaState>.forRoomLegacy(room: HaRoom): List<HaState> =
    filter { s ->
        val obj = s.objectId
        obj == room.id || obj.startsWith("${room.id}_")
    }.sortedWith(compareBy({ domainOrder(it.domain) }, { it.friendlyName }))

/**
 * **房間卡片與房間內彈層**專用：只使用有效 area
 * （見 [HaApiClient.getEntityEffectiveAreaMap]，entity 或 device 區域）。
 * 不再退回 `roomId_*` 前綴比對。
 */
private fun HaState.matchesHaArea(room: HaRoom, areaByEntity: Map<String, String?>): Boolean {
    val assigned = areaByEntity[entityId]?.takeIf { it.isNotEmpty() } ?: return false
    return assigned == room.haAreaId
}

/** 房間頁專用（非 domain 分頁）。 */
fun List<HaState>.forRoom(room: HaRoom, areaByEntity: Map<String, String?> = emptyMap()): List<HaState> =
    filter { it.matchesHaArea(room, areaByEntity) }
        .sortedWith(compareBy({ domainOrder(it.domain) }, { it.friendlyName }))

/** Controllable domains (not sensors / cameras). */
fun List<HaState>.controllableForRoom(room: HaRoom, areaByEntity: Map<String, String?> = emptyMap()): List<HaState> =
    forRoom(room, areaByEntity).filter { it.domain !in SENSOR_DOMAINS && !it.isUnavailable }

/** Read-only sensor entities for a room. */
fun List<HaState>.sensorsForRoom(room: HaRoom, areaByEntity: Map<String, String?> = emptyMap()): List<HaState> =
    forRoom(room, areaByEntity).filter { it.domain in SENSOR_DOMAINS && !it.isUnavailable }

/** Count actively running devices in a room. */
fun List<HaState>.activeCount(room: HaRoom, areaByEntity: Map<String, String?> = emptyMap()): Int =
    forRoom(room, areaByEntity).count { s ->
        !s.isUnavailable && when (s.domain) {
            "light", "switch", "fan", "input_boolean" -> s.isOn
            "cover"                                   -> s.state != "closed"
            "media_player"                            -> s.state == "playing"
            "climate"                                 -> s.state != "off"
            "vacuum"                                  -> s.state !in setOf("docked", "idle", "error")
            else                                      -> false
        }
    }

/** All entities of a domain across all rooms, grouped by room (object_id 前綴，與 HA area 無關). */
fun List<HaState>.byRoomForDomain(domain: String): List<Pair<HaRoom, List<HaState>>> =
    HA_ROOMS.mapNotNull { room ->
        val entities = forRoomLegacy(room).filter { it.domain == domain && !it.isUnavailable }
        if (entities.isNotEmpty()) room to entities else null
    }

/** Scene + script entities suitable for shortcut chips. */
fun List<HaState>.sceneShortcuts(): List<HaState> =
    filter { it.domain in listOf("scene", "script") && !it.isUnavailable }
        .sortedBy { it.friendlyName }

val SENSOR_DOMAINS = setOf("sensor", "binary_sensor", "camera")

val CONTROLLABLE_DOMAINS = setOf(
    "light", "climate", "cover", "media_player",
    "switch", "fan", "vacuum", "input_boolean"
)

private fun domainOrder(domain: String) = when (domain) {
    "light"         -> 0
    "climate"       -> 1
    "cover"         -> 2
    "media_player"  -> 3
    "switch"        -> 4
    "fan"           -> 5
    "vacuum"        -> 6
    "input_boolean" -> 7
    "sensor"        -> 8
    "binary_sensor" -> 9
    else            -> 10
}
