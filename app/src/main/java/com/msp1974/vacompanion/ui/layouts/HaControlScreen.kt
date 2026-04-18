package com.msp1974.vacompanion.ui.layouts

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.msp1974.vacompanion.ha.*
import com.msp1974.vacompanion.ma.MaApiClient
import com.msp1974.vacompanion.ma.MaQueueItem
import com.msp1974.vacompanion.ma.MaSession
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

// ── Session ───────────────────────────────────────────────────────────────────

private val LocalSession   = compositionLocalOf<HaSession>  { error("No HaSession") }
private val LocalMaSession = compositionLocalOf<MaSession?> { null }

// ── Theme colours ─────────────────────────────────────────────────────────────

private val BgColor       = Color(0xFF0F0F0F)
private val SurfColor     = Color(0xFF1A1A22)
private val CardColor     = Color(0xFF1E1E2E)
private val CardColorOn   = Color(0xFF252540)
private val DividerColor  = Color(0xFF2A2A3A)
private val TextPri       = Color(0xFFEEEEEE)
private val TextSec       = Color(0xFF8888AA)

private val ColLight      = Color(0xFFFFD060)
private val ColClimate    = Color(0xFF60CFFF)
private val ColCover      = Color(0xFF8AE98A)
private val ColMedia      = Color(0xFFFF80AB)
private val ColVacuum     = Color(0xFFFFAA60)
private val ColToggle     = Color(0xFFAADDFF)

private fun entityListKey(section: String, index: Int, entity: HaState): String =
    "$section:${entity.entityId}:$index"

private fun List<HaState>.dedupeByEntityIdKeepLatest(): List<HaState> {
    if (size <= 1) return this
    val seen = HashSet<String>(size)
    val out = ArrayList<HaState>(size)
    // Traverse backward so newer duplicate entities win, then restore order.
    for (i in lastIndex downTo 0) {
        val state = this[i]
        if (seen.add(state.entityId)) out.add(state)
    }
    out.reverse()
    return out
}

// ── Nav tab definition ────────────────────────────────────────────────────────

private enum class HomeTab(val labelZh: String, val icon: ImageVector) {
    ROOMS  ("房間", Icons.Default.GridView),
    LIGHTS ("燈光", Icons.Default.Lightbulb),
    CLIMATE("空調", Icons.Default.Thermostat),
    COVER  ("窗簾", Icons.Default.Blinds),
    FAN    ("風扇", Icons.Default.Air),
    MUSIC  ("音樂", Icons.Default.LibraryMusic),
}

// ═════════════════════════════════════════════════════════════════════════════
//  MAIN ENTRY POINT
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaControlScreen(
    config: APPConfig,
    /** Increment from the parent after HA settings are saved to force session rebuild. */
    sessionVersion: Int = 0,
    /** Called when the user needs to open HA settings (e.g. from the error screen). */
    onHaSettingsClick: () -> Unit = {},
) {
    val session = remember(sessionVersion) {
        val url   = config.haDirectUrl.ifEmpty { AuthUtils.getHAUrl(config, withDashboardPath = false) }
        val token = config.haDirectToken.ifEmpty { config.accessToken }
        HaSession(baseUrl = url, token = token, ignoreSSL = config.ignoreSSLErrors)
    }

    val maSession = remember(sessionVersion, config.maUrl, config.maUsername, config.maPassword) {
        if (config.maUrl.isNotBlank())
            MaSession(config.maUrl, config.maUsername, config.maPassword)
        else null
    }

    var states     by remember { mutableStateOf<List<HaState>>(emptyList()) }
    /** 僅房間頁使用：entity_id → 有效 area_id；燈光/空調/媒體分頁不依此表。 */
    var entityAreaMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var isLoading  by remember { mutableStateOf(true) }
    var error      by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var currentTab by remember { mutableStateOf(HomeTab.ROOMS) }
    var selectedRoom by remember { mutableStateOf<HaRoom?>(null) }
    var showMaSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // WebSocket live stream; refresh button forces reconnect via refreshKey.
    LaunchedEffect(refreshKey, sessionVersion) {
        isLoading = true
        error     = null
        if (session.baseUrl.isBlank() || session.token.isBlank()) {
            error         = "尚未設定 Home Assistant 連線，請點擊浮動工具列的「HA設定」進行設定"
            entityAreaMap = emptyMap()
            isLoading     = false
            return@LaunchedEffect
        }
        entityAreaMap = HaApiClient.getEntityEffectiveAreaMap(session)
        HaApiClient.observeStates(session)
            .retryWhen { cause, attempt ->
                // Exponential backoff: 1s, 2s, 4s ... capped at 30s.
                val waitMs = minOf(30_000L, 1_000L shl attempt.coerceAtMost(10).toInt())
                error = "HA 即時連線中斷，${waitMs / 1000} 秒後重試：${cause.message ?: "unknown error"}"
                delay(waitMs)
                true
            }
            .catch { e ->
                error = "HA 即時連線中斷：${e.message ?: "unknown error"}"
                isLoading = false
            }
            .collect { liveStates ->
                states = liveStates
                if (liveStates.isEmpty()) error = "無法取得實體狀態，請確認 HA 連線"
                else error = null
                isLoading = false
            }
    }

    CompositionLocalProvider(
        LocalSession   provides session,
        LocalMaSession provides maSession
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgColor)
        ) {
            // ── Summary bar ───────────────────────────────────────────────
            SummaryBar(states, isLoading,
                onRefresh = {
                    HaApiClient.invalidateAllCameras()
                    refreshKey++
                },
                onSettingsClick = { showMaSettings = true }
            )

            // ── MA settings dialog ────────────────────────────────────────
            if (showMaSettings) {
                MaSettingsDialog(config = config, onDismiss = { showMaSettings = false })
            }

            // ── Domain tab row ────────────────────────────────────────────
            PrimaryTabRow(
                selectedTabIndex = currentTab.ordinal,
                containerColor   = SurfColor,
                contentColor     = TextPri,
            ) {
                HomeTab.entries.forEach { tab ->
                    Tab(
                        selected = currentTab == tab,
                        onClick  = { currentTab = tab },
                        icon     = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text     = { Text(tab.labelZh, fontSize = 12.sp) },
                        selectedContentColor   = ColLight,
                        unselectedContentColor = TextSec
                    )
                }
            }

            // ── Page content ──────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    error != null && states.isEmpty() -> ErrorView(error!!, onRetry = { refreshKey++ }, onSettings = onHaSettingsClick)
                    states.isEmpty() && isLoading     -> LoadingView()
                    else -> when (currentTab) {
                        HomeTab.ROOMS   -> RoomsPage(states, entityAreaMap, onRoomClick = { selectedRoom = it })
                        HomeTab.LIGHTS  -> DomainPage("light",        states, scope,
                            globalAction = {
                                scope.launch { HaApiClient.callService(session, "light", "turn_off", "all") }
                                refreshKey++
                            },
                            globalLabel = "全部關燈",
                            globalColor = ColLight
                        )
                        HomeTab.CLIMATE -> DomainPage("climate",      states, scope, null, null, null)
                        HomeTab.COVER   -> DomainPage("cover",        states, scope, null, null, null)
                        HomeTab.FAN     -> DomainPage("fan",          states, scope, null, null, null)
                        HomeTab.MUSIC   -> {
                            val ma = LocalMaSession.current
                            if (ma != null) {
                                MaLibraryScreen(ma)
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("請先在設定中配置 Music Assistant 連線", color = TextSec)
                                }
                            }
                        }
                    }
                }
                // Overlay error hint when state is stale
                if (error != null && states.isNotEmpty()) {
                    Surface(
                        color  = Color(0xCC1A1A2E),
                        shape  = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    ) {
                        Text(error!!, color = TextSec, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
        }

        // ── Room detail bottom sheet ──────────────────────────────────────────
        selectedRoom?.let { room ->
            ModalBottomSheet(
                onDismissRequest = { selectedRoom = null },
                containerColor   = Color(0xFF141420),
                shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                RoomDetailSheet(
                    room      = room,
                    states    = states.forRoom(room, entityAreaMap),
                    session   = session,
                    onRefresh = { refreshKey++ }
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SUMMARY BAR
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SummaryBar(
    states: List<HaState>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val temp   = states.find { it.entityId == "sensor.house_temperature" }?.state?.toDoubleOrNull()
    val humid  = states.find { it.entityId == "sensor.house_humidity" }?.state?.toDoubleOrNull()
    val lights = states.count { it.domain == "light" && it.isOn }
    val climates = states.count { it.domain == "climate" && it.state != "off" && !it.isUnavailable }
    val motions  = states.any { it.domain == "binary_sensor" && "moving" in it.objectId && it.isOn }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfColor)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Stats chips
        if (temp != null)
            StatChip(Icons.Default.Thermostat, "%.1f°C".format(temp), ColClimate)
        if (humid != null)
            StatChip(Icons.Default.WaterDrop, "%.0f%%".format(humid), ColClimate)
        StatChip(Icons.Default.Lightbulb, "$lights", if (lights > 0) ColLight else TextSec)
        StatChip(Icons.Default.AcUnit, "$climates", if (climates > 0) ColClimate else TextSec)
        if (motions)
            StatChip(Icons.AutoMirrored.Filled.DirectionsRun, "動", Color(0xFFFFAA50))

        Spacer(Modifier.weight(1f))

        // Refresh / loading
        if (isLoading) {
            CircularProgressIndicator(
                modifier   = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color       = ColLight
            )
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "重新整理",
                    tint = TextSec, modifier = Modifier.size(18.dp))
            }
        }

        // MA settings shortcut
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Settings, contentDescription = "MA 設定",
                tint = TextSec, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ROOMS PAGE
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun RoomsPage(
    states: List<HaState>,
    areaByEntity: Map<String, String?>,
    onRoomClick: (HaRoom) -> Unit
) {
    val session = LocalSession.current
    val dedupedStates = remember(states) { states.dedupeByEntityIdKeepLatest() }
    val scenes  = dedupedStates.sceneShortcuts()
    val scope   = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val columnsPerRow = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3
    val horizontalPadding = 10 * 2
    val horizontalSpacing = (columnsPerRow - 1) * 10
    val cellWidth = ((configuration.screenWidthDp - horizontalPadding - horizontalSpacing).coerceAtLeast(0) / columnsPerRow).dp
    val cardHeight = cellWidth * 0.75f // RoomCard uses aspectRatio(4f / 3f)
    val rows = (HA_ROOMS.size + columnsPerRow - 1) / columnsPerRow
    val gridHeight = (cardHeight * rows.toFloat()) + ((rows - 1).coerceAtLeast(0) * 10).dp + 16.dp

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Scene / script shortcuts row
        if (scenes.isNotEmpty()) {
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null,
                            tint = TextSec, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("場景 / 腳本", color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(scenes, key = { index, entity -> entityListKey("scenes", index, entity) }) { _, entity ->
                            SceneChip(entity, onClick = {
                                scope.launch {
                                    val svc = if (entity.domain == "scene") "turn_on" else "turn_on"
                                    HaApiClient.callService(session, entity.domain, svc, entity.entityId)
                                }
                            })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Room grid (2 adaptive columns)
        item {
            LazyVerticalGrid(
                columns               = GridCells.Fixed(columnsPerRow),
                contentPadding        = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement   = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // Fixed height — nested in LazyColumn, must not scroll
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
            ) {
                items(HA_ROOMS, key = { it.id }) { room ->
                    RoomCard(
                        room        = room,
                        activeCount = states.activeCount(room, areaByEntity),
                        roomStates  = states.forRoom(room, areaByEntity),
                        onClick     = { onRoomClick(room) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneChip(entity: HaState, onClick: () -> Unit) {
    var triggered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = if (triggered) ColLight.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.07f),
        modifier = Modifier.clickable {
            triggered = true
            onClick()
            scope.launch {
                kotlinx.coroutines.delay(1500)
                triggered = false
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val icon = if (entity.domain == "scene") Icons.Default.Palette else Icons.Default.PlayCircle
            Icon(icon, contentDescription = null,
                tint = if (triggered) ColLight else TextSec, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(entity.friendlyName, color = if (triggered) ColLight else TextPri, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ROOM CARD  (enhanced with camera thumbnail)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun RoomCard(
    room: HaRoom,
    activeCount: Int,
    roomStates: List<HaState>,
    onClick: () -> Unit
) {
    val session  = LocalSession.current
    val isActive = activeCount > 0

    // Camera thumbnail async load
    val cameraBitmap by produceState<Bitmap?>(null, room.cameraEntity, session.baseUrl) {
        room.cameraEntity?.let { entityId ->
            value = HaApiClient.getCameraBitmap(session, entityId)
        }
    }

    val cardBg by animateColorAsState(
        targetValue    = if (isActive) CardColorOn else CardColor,
        animationSpec  = tween(400),
        label          = "roomBg"
    )

    // Sensor readings for overlay
    val tempState  = roomStates.find { "temperature" in it.objectId && it.domain == "sensor" }
    val humidity   = roomStates.find { "humidity" in it.objectId && it.domain == "sensor" }
    val hasMotion  = roomStates.any { "moving" in it.objectId && it.domain == "binary_sensor" && it.isOn }

    Box(
        modifier = Modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .clickable(onClick = onClick)
    ) {
        // Camera image or icon background
        if (cameraBitmap != null) {
            Image(
                bitmap       = cameraBitmap!!.asImageBitmap(),
                contentDescription = room.nameZh,
                contentScale = ContentScale.Crop,
                modifier     = Modifier.fillMaxSize()
            )
            // Dark gradient overlay (bottom 50% for text legibility)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.3f to Color.Transparent,
                            1.0f to Color(0xDD0A0A15)
                        )
                    )
            )
        } else {
            // Icon placeholder centred in upper portion
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = room.icon,
                    contentDescription = null,
                    tint    = if (isActive) ColLight else TextSec,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Motion indicator dot
        if (hasMotion) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF8800))
            )
        }

        // Bottom info row
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text       = room.nameZh,
                color      = TextPri,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines   = 1
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                // Sensor readings
                if (tempState != null) {
                    MiniStat(Icons.Default.Thermostat, "${tempState.state}°", ColClimate)
                }
                if (humidity != null) {
                    MiniStat(Icons.Default.WaterDrop, "${humidity.state}%", ColClimate)
                }
                Spacer(Modifier.weight(1f))
                // Active count badge
                if (isActive) {
                    Surface(shape = CircleShape, color = ColLight.copy(alpha = 0.22f)) {
                        Text(
                            text  = "$activeCount",
                            color = ColLight,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
        Text(label, color = color, fontSize = 10.sp)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  DOMAIN PAGE  (Lights / Climate / Media)
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun DomainPage(
    domain: String,
    states: List<HaState>,
    scope: kotlinx.coroutines.CoroutineScope,
    globalAction: (() -> Unit)?,
    globalLabel: String?,
    globalColor: Color?
) {
    val session = LocalSession.current
    val dedupedStates = remember(states) { states.dedupeByEntityIdKeepLatest() }
    val grouped = dedupedStates.byRoomForDomain(domain)

    fun callService(svc: String, entityId: String, extra: Map<String, Any?> = emptyMap()) {
        scope.launch { HaApiClient.callService(session, domain, svc, entityId, extra) }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier       = Modifier.fillMaxSize()
    ) {
        // Global quick action
        if (globalAction != null && globalLabel != null && globalColor != null) {
            item {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedButton(
                        onClick = globalAction,
                        border  = androidx.compose.foundation.BorderStroke(1.dp, globalColor.copy(alpha = 0.4f)),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = globalColor),
                        shape   = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(globalLabel, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = DividerColor)
            }
        }

        if (grouped.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("沒有找到相關設備", color = TextSec)
                }
            }
        }

        grouped.forEach { (room, entities) ->
            // Room section header
            item(key = "header_${room.id}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfColor.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(room.icon, contentDescription = null, tint = TextSec, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(room.nameZh, color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = DividerColor)
            }

            itemsIndexed(
                entities,
                key = { index, entity -> entityListKey("room_${room.id}", index, entity) }
            ) { _, entity ->
                EntityControlRow(entity, onCallService = { svc, id, extra -> callService(svc, id, extra) })
                HorizontalDivider(color = DividerColor.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ROOM DETAIL SHEET
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun RoomDetailSheet(
    room: HaRoom,
    states: List<HaState>,
    session: HaSession,
    onRefresh: () -> Unit
) {
    val scope    = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val maxSheetContentHeight = (configuration.screenHeightDp * 0.78f).dp
    val dedupedStates = remember(states) { states.dedupeByEntityIdKeepLatest() }
    val sensors  = dedupedStates.filter { it.domain in SENSOR_DOMAINS && !it.isUnavailable }
    val devices  = dedupedStates.filter { it.domain !in SENSOR_DOMAINS && !it.isUnavailable }

    fun callService(domain: String, service: String, entityId: String, extra: Map<String, Any?> = emptyMap()) {
        scope.launch {
            HaApiClient.callService(session, domain, service, entityId, extra)
            delay(600)
            onRefresh()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Icon(room.icon, contentDescription = null, tint = ColLight, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(room.nameZh, color = TextPri, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Camera snapshot thumbnail in header (if available)
            room.cameraEntity?.let { entityId ->
                val bitmap by produceState<Bitmap?>(null, entityId) {
                    value = HaApiClient.getCameraBitmap(session, entityId)
                }
                bitmap?.let {
                    Image(
                        bitmap       = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier     = Modifier
                            .size(width = 80.dp, height = 54.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }
        }

        // Sensor chips
        if (sensors.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(sensors) { s -> SensorChip(s) }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Devices separator
        if (devices.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TextSec, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text("設備控制", color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            }
            HorizontalDivider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
        }

        // Entity controls
        LazyColumn(
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetContentHeight)
        ) {
            itemsIndexed(
                devices,
                key = { index, entity -> entityListKey("sheet_${room.id}", index, entity) }
            ) { _, entity ->
                EntityControlRow(entity) { svc, id, extra ->
                    callService(entity.domain, svc, id, extra)
                }
                HorizontalDivider(color = DividerColor.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SENSOR CHIP
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SensorChip(state: HaState) {
    val unit  = state.attributes.optString("unit_of_measurement", "")
    val label = "${state.state}${if (unit.isNotEmpty()) " $unit" else ""}"
    val icon  = sensorIcon(state)

    Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.08f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = ColClimate, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = TextPri, fontSize = 12.sp, maxLines = 1)
        }
    }
}

private fun sensorIcon(state: HaState): ImageVector {
    val name = state.friendlyName.lowercase()
    val unit = state.attributes.optString("unit_of_measurement", "").lowercase()
    return when {
        "temp" in name || "°" in unit          -> Icons.Default.Thermostat
        "humid" in name || "%" == unit         -> Icons.Default.WaterDrop
        "power" in name || "w" == unit         -> Icons.Default.Bolt
        "energy" in name || "kwh" in unit      -> Icons.Default.ElectricBolt
        "battery" in name                      -> Icons.Default.BatteryFull
        "door" in name || "window" in name     -> Icons.Default.Window
        "motion" in name || "moving" in name   -> Icons.AutoMirrored.Filled.DirectionsRun
        "co2" in name || "voc" in name         -> Icons.Default.Air
        else                                   -> Icons.Default.Sensors
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  ENTITY CONTROL ROW  dispatcher
// ═════════════════════════════════════════════════════════════════════════════

/** @param onCallService (service, entityId, extraParams) */
@Composable
private fun EntityControlRow(
    entity: HaState,
    onCallService: (String, String, Map<String, Any?>) -> Unit
) {
    when (entity.domain) {
        "light"                     -> LightRow(entity, onCallService)
        "climate"                   -> ClimateRow(entity, onCallService)
        "cover"                     -> CoverRow(entity, onCallService)
        "media_player"              -> MaMediaPlayerRow(entity, onCallService, LocalMaSession.current)
        "vacuum"                    -> VacuumRow(entity, onCallService)
        "switch", "fan", "input_boolean" -> ToggleRow(entity, onCallService)
        else                        -> GenericRow(entity)
    }
}

// ── Light ─────────────────────────────────────────────────────────────────────

@Composable
private fun LightRow(
    entity: HaState,
    call: (String, String, Map<String, Any?>) -> Unit
) {
    val brightness = entity.attributes.optInt("brightness", 0)
    var sliderPos by remember(entity.entityId, brightness, entity.isOn) {
        mutableStateOf(if (entity.isOn) (brightness / 255f).coerceIn(0f, 1f) else 0f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.Lightbulb, null,
            tint = if (entity.isOn) ColLight else TextSec, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entity.friendlyName, color = TextPri, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entity.isOn) {
                Slider(
                    value               = sliderPos,
                    onValueChange       = { sliderPos = it },
                    onValueChangeFinished = {
                        val bri = (sliderPos * 255).toInt().coerceIn(1, 255)
                        call("turn_on", entity.entityId, mapOf("brightness" to bri))
                    },
                    colors = SliderDefaults.colors(
                        thumbColor        = ColLight,
                        activeTrackColor  = ColLight,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked  = entity.isOn,
            onCheckedChange = { on -> call(if (on) "turn_on" else "turn_off", entity.entityId, emptyMap()) },
            colors   = SwitchDefaults.colors(checkedThumbColor = ColLight, checkedTrackColor = ColLight.copy(0.4f))
        )
    }
}

// ── Climate ───────────────────────────────────────────────────────────────────

@Composable
private fun ClimateRow(
    entity: HaState,
    call: (String, String, Map<String, Any?>) -> Unit
) {
    val currentTemp = entity.attributes.optDouble("current_temperature", Double.NaN)
    val targetTemp  = entity.attributes.optDouble("temperature", Double.NaN)
    val hvacMode    = entity.attributes.optString("hvac_mode", entity.state)
    val fanMode     = entity.attributes.optString("fan_mode", "")
    val hvacModes   = entity.attributes.optJSONArray("hvac_modes")
    val fanModes    = entity.attributes.optJSONArray("fan_modes")
    val hvacModeList = remember(entity.entityId, hvacModes?.toString()) {
        buildList {
            if (hvacModes != null) {
                for (i in 0 until hvacModes.length()) {
                    hvacModes.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    }
    val fanModeList = remember(entity.entityId, fanModes?.toString()) {
        buildList {
            if (fanModes != null) {
                for (i in 0 until fanModes.length()) {
                    fanModes.optString(i, "").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    }
    val isOff       = hvacMode == "off"
    var displayTarget by remember(entity.entityId, targetTemp) {
        mutableStateOf(if (!targetTemp.isNaN()) targetTemp else 24.0)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Thermostat, null,
                tint = if (!isOff) ColClimate else TextSec, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.friendlyName, color = TextPri, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (!currentTemp.isNaN())
                        Text("%.1f°".format(currentTemp), color = TextSec, fontSize = 11.sp)
                    if (!isOff) {
                        Text(" → %.1f°".format(displayTarget), color = ColClimate, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        HvacChip(hvacMode)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!isOff) {
                // Temp adjust ±0.5
                Row {
                    IconButton(onClick = {
                        displayTarget = (displayTarget - 0.5).coerceIn(16.0, 30.0)
                        call("set_temperature", entity.entityId, mapOf("temperature" to displayTarget))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, null, tint = ColClimate, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        displayTarget = (displayTarget + 0.5).coerceIn(16.0, 30.0)
                        call("set_temperature", entity.entityId, mapOf("temperature" to displayTarget))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, null, tint = ColClimate, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (hvacModeList.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                hvacModeList.forEach { mode ->
                    val isSelected = mode == hvacMode
                    Icon(
                        imageVector = hvacModeIcon(mode),
                        contentDescription = mode,
                        tint = if (isSelected) ColClimate else TextSec,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { call("set_hvac_mode", entity.entityId, mapOf("hvac_mode" to mode)) }
                    )
                }
            }
        }
        if (fanModeList.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("風速：${if (fanMode.isNotBlank()) fanMode else "-"}", color = TextSec, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                fanModeList.forEach { mode ->
                    val isSelected = mode == fanMode
                    CoverBtn(
                        label = fanModeLabel(mode),
                        color = if (isSelected) ColClimate else TextSec
                    ) {
                        call("set_fan_mode", entity.entityId, mapOf("fan_mode" to mode))
                    }
                }
            }
        }
    }
}

private fun hvacModeIcon(mode: String): ImageVector = when (mode) {
    "off" -> Icons.Default.PowerSettingsNew
    "cool" -> Icons.Default.AcUnit
    "heat" -> Icons.Default.LocalFireDepartment
    "heat_cool", "auto" -> Icons.Default.Autorenew
    "dry" -> Icons.Default.WaterDrop
    "fan_only" -> Icons.Default.Air
    else -> Icons.Default.Tune
}

private fun fanModeLabel(mode: String): String = when (mode.lowercase()) {
    "auto" -> "自動"
    "low", "silent", "quiet" -> "低"
    "medium", "mid" -> "中"
    "high", "strong", "turbo" -> "高"
    else -> mode
}

@Composable
private fun HvacChip(mode: String) {
    val (label, color) = when (mode) {
        "heat"           -> "暖"  to Color(0xFFFF9060)
        "cool"           -> "冷"  to ColClimate
        "heat_cool","auto" -> "自動" to Color(0xFFA0CFFF)
        "dry"            -> "除濕" to Color(0xFFD0FFD0)
        "fan_only"       -> "送風" to Color(0xFFCCCCFF)
        else             -> mode  to TextSec
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.18f)) {
        Text(label, color = color, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ── Cover ─────────────────────────────────────────────────────────────────────

private const val COVER_SUPPORT_OPEN_TILT = 16
private const val COVER_SUPPORT_CLOSE_TILT = 32
private const val COVER_SUPPORT_STOP_TILT = 64
private const val COVER_SUPPORT_SET_POSITION = 4
private const val COVER_SUPPORT_SET_TILT_POSITION = 128

@Composable
private fun CoverRow(entity: HaState, call: (String, String, Map<String, Any?>) -> Unit) {
    val position = entity.attributes.optInt("current_position", -1)
    val tiltPosition = entity.attributes.optInt("current_tilt_position", -1)
    val supportedFeatures = entity.attributes.optInt("supported_features", 0)
    val supportsSetPosition = (supportedFeatures and COVER_SUPPORT_SET_POSITION) != 0
    val supportsSetTiltPosition = (supportedFeatures and COVER_SUPPORT_SET_TILT_POSITION) != 0
    val supportsOpenTilt = (supportedFeatures and COVER_SUPPORT_OPEN_TILT) != 0
    val supportsCloseTilt = (supportedFeatures and COVER_SUPPORT_CLOSE_TILT) != 0
    val supportsStopTilt = (supportedFeatures and COVER_SUPPORT_STOP_TILT) != 0
    val hasTiltControl = supportsOpenTilt || supportsCloseTilt || supportsStopTilt || supportsSetTiltPosition
    val hasPositionSlider = supportsSetPosition && position >= 0
    val hasTiltSlider = supportsSetTiltPosition && tiltPosition >= 0
    val isOpen   = entity.state == "open" || entity.state == "opening"
    var positionSlider by remember(entity.entityId, position) {
        mutableStateOf(position.coerceIn(0, 100).toFloat())
    }
    var tiltSlider by remember(entity.entityId, tiltPosition) {
        mutableStateOf(tiltPosition.coerceIn(0, 100).toFloat())
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Blinds, null,
                tint = if (isOpen) ColCover else TextSec, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.friendlyName, color = TextPri, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = buildString {
                    if (position >= 0) append("位置 $position%")
                    if (tiltPosition >= 0) {
                        if (isNotEmpty()) append(" · ")
                        append("傾角 $tiltPosition%")
                    }
                }
                if (status.isNotEmpty()) Text(status, color = TextSec, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoverBtn("開", ColCover)   { call("open_cover",  entity.entityId, emptyMap()) }
                CoverBtn("停", TextSec)    { call("stop_cover",  entity.entityId, emptyMap()) }
                CoverBtn("關", Color(0xFFFF7070)) { call("close_cover", entity.entityId, emptyMap()) }
            }
        }
        if (hasTiltControl) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (supportsOpenTilt) {
                    CoverBtn("傾開", ColCover) { call("open_cover_tilt", entity.entityId, emptyMap()) }
                }
                if (supportsStopTilt) {
                    CoverBtn("傾停", TextSec) { call("stop_cover_tilt", entity.entityId, emptyMap()) }
                }
                if (supportsCloseTilt) {
                    CoverBtn("傾關", Color(0xFFFF7070)) { call("close_cover_tilt", entity.entityId, emptyMap()) }
                }
            }
        }
        if (hasPositionSlider) {
            Spacer(Modifier.height(8.dp))
            Text("位置 ${positionSlider.toInt()}%", color = TextSec, fontSize = 11.sp)
            Slider(
                value = positionSlider,
                onValueChange = { positionSlider = it },
                valueRange = 0f..100f,
                onValueChangeFinished = {
                    call(
                        "set_cover_position",
                        entity.entityId,
                        mapOf("position" to positionSlider.toInt().coerceIn(0, 100))
                    )
                },
                colors = SliderDefaults.colors(
                    thumbColor = ColCover,
                    activeTrackColor = ColCover,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp)
            )
        }
        if (hasTiltSlider) {
            Spacer(Modifier.height(6.dp))
            Text("傾角 ${tiltSlider.toInt()}%", color = TextSec, fontSize = 11.sp)
            Slider(
                value = tiltSlider,
                onValueChange = { tiltSlider = it },
                valueRange = 0f..100f,
                onValueChangeFinished = {
                    call(
                        "set_cover_tilt_position",
                        entity.entityId,
                        mapOf("tilt_position" to tiltSlider.toInt().coerceIn(0, 100))
                    )
                },
                colors = SliderDefaults.colors(
                    thumbColor = ColCover,
                    activeTrackColor = ColCover,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp)
            )
        }
    }
}

@Composable
private fun CoverBtn(label: String, color: Color, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f),
        modifier = Modifier.clickable(onClick = onClick)) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

// ── Time helper ───────────────────────────────────────────────────────────────

private fun formatTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Music Assistant media player ──────────────────────────────────────────────

@Composable
private fun MaMediaPlayerRow(
    entity: HaState,
    call: (String, String, Map<String, Any?>) -> Unit,
    maSession: MaSession?
) {
    val haSession    = LocalSession.current
    val isPlaying    = entity.state == "playing"
    val title        = entity.attributes.optString("media_title", "")
    val artist       = entity.attributes.optString("media_artist", "")
    val album        = entity.attributes.optString("media_album_name", "")
    val volume       = entity.attributes.optDouble("volume_level", -1.0)
    val shuffle      = entity.attributes.optBoolean("shuffle", false)
    val repeat       = entity.attributes.optString("repeat", "off")
    val position     = entity.attributes.optDouble("media_position", 0.0)
    val duration     = entity.attributes.optDouble("media_duration", 0.0)
    val entityPic    = entity.attributes.optString("entity_picture", "")
    val massPlayerId = entity.attributes.optString("mass_player_id", "")

    // Album art
    var albumArt by remember(entityPic) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entityPic) {
        albumArt = null
        if (entityPic.isNotEmpty()) {
            albumArt = HaApiClient.fetchImageBitmap(haSession, entityPic)
        }
    }

    // Queue items
    var queueItems by remember { mutableStateOf<List<MaQueueItem>>(emptyList()) }
    var showQueue  by remember { mutableStateOf(false) }
    LaunchedEffect(massPlayerId, entity.state) {
        if (maSession != null && massPlayerId.isNotEmpty()) {
            queueItems = MaApiClient.getQueueItems(maSession, massPlayerId)
        }
    }

    var volSlider by remember(entity.entityId, volume) {
        mutableStateOf(if (volume >= 0) volume.toFloat() else 0.3f)
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

        // ── Top row: art + info + shuffle/repeat ──────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {

            // Album art thumbnail
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardColorOn)
            ) {
                if (albumArt != null) {
                    Image(
                        bitmap       = albumArt!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier     = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote, null,
                        tint     = ColMedia.copy(alpha = 0.45f),
                        modifier = Modifier.align(Alignment.Center).size(28.dp)
                    )
                }
                // Playing pulse dot
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(8.dp)
                            .background(ColMedia, CircleShape)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Track info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity.friendlyName,
                    color    = TextSec, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (title.isNotEmpty()) title else "---",
                    color      = TextPri, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1, overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotEmpty() || album.isNotEmpty()) {
                    Text(
                        buildString {
                            if (artist.isNotEmpty()) append(artist)
                            if (album.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(album) }
                        },
                        color    = TextSec, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Shuffle + Repeat toggles
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick  = { call("shuffle_set", entity.entityId, mapOf("shuffle" to !shuffle)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Shuffle, null,
                        tint     = if (shuffle) ColMedia else TextSec,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val next = when (repeat) { "off" -> "one"; "one" -> "all"; else -> "off" }
                        call("repeat_set", entity.entityId, mapOf("repeat" to next))
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (repeat == "one") Icons.Default.RepeatOne else Icons.Default.Repeat,
                        null,
                        tint     = if (repeat != "off") ColMedia else TextSec,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Progress bar ──────────────────────────────────────────────────────
        if (duration > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(position.toInt()), color = TextSec, fontSize = 10.sp)
                LinearProgressIndicator(
                    progress = { (position / duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color      = ColMedia,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
                Text(formatTime(duration.toInt()), color = TextSec, fontSize = 10.sp)
            }
        }

        // ── Playback + volume row ─────────────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier.fillMaxWidth()
        ) {
            MediaBtn(Icons.Default.SkipPrevious) {
                call("media_previous_track", entity.entityId, emptyMap())
            }
            MediaBtn(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, ColMedia) {
                call(if (isPlaying) "media_pause" else "media_play", entity.entityId, emptyMap())
            }
            MediaBtn(Icons.Default.SkipNext) {
                call("media_next_track", entity.entityId, emptyMap())
            }

            if (volume >= 0) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null,
                    tint = TextSec, modifier = Modifier.size(14.dp))
                Slider(
                    value               = volSlider,
                    onValueChange       = { volSlider = it },
                    onValueChangeFinished = {
                        call("volume_set", entity.entityId, mapOf("volume_level" to volSlider.toDouble()))
                    },
                    colors   = SliderDefaults.colors(
                        thumbColor       = ColMedia,
                        activeTrackColor = ColMedia,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.weight(1f).height(28.dp)
                )
            }

            // Queue toggle (only shown when items are available)
            if (queueItems.isNotEmpty()) {
                IconButton(onClick = { showQueue = !showQueue }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.QueueMusic, null,
                        tint     = if (showQueue) ColMedia else TextSec,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Upcoming queue ────────────────────────────────────────────────────
        if (showQueue && queueItems.isNotEmpty()) {
            HorizontalDivider(
                color    = DividerColor,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            queueItems.take(5).forEachIndexed { idx, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.padding(vertical = 3.dp)
                ) {
                    Text(
                        "${idx + 1}", color = TextSec, fontSize = 11.sp,
                        modifier   = Modifier.width(18.dp),
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, color = TextPri, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.artist.isNotEmpty())
                            Text(item.artist, color = TextSec, fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.duration > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(item.duration), color = TextSec, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ── Media player (generic / non-MA fallback) ──────────────────────────────────

@Composable
private fun MediaPlayerRow(entity: HaState, call: (String, String, Map<String, Any?>) -> Unit) {
    val isPlaying = entity.state == "playing"
    val title     = entity.attributes.optString("media_title", "")
    val artist    = entity.attributes.optString("media_artist", "")
    val volume    = entity.attributes.optDouble("volume_level", -1.0)
    var volSlider by remember(entity.entityId, volume) {
        mutableStateOf(if (volume >= 0) volume.toFloat() else 0.3f)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null,
                tint = if (isPlaying) ColMedia else TextSec, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.friendlyName, color = TextPri, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (artist.isNotEmpty()) append(artist)
                    if (title.isNotEmpty()) { if (isNotEmpty()) append(" – "); append(title) }
                }
                if (sub.isNotEmpty())
                    Text(sub, color = TextSec, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            MediaBtn(Icons.Default.SkipPrevious) { call("media_previous_track", entity.entityId, emptyMap()) }
            MediaBtn(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, ColMedia) {
                call(if (isPlaying) "media_pause" else "media_play", entity.entityId, emptyMap())
            }
            MediaBtn(Icons.Default.SkipNext)     { call("media_next_track",     entity.entityId, emptyMap()) }
            if (volume >= 0) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = TextSec, modifier = Modifier.size(16.dp))
                Slider(
                    value               = volSlider,
                    onValueChange       = { volSlider = it },
                    onValueChangeFinished = {
                        call("volume_set", entity.entityId, mapOf("volume_level" to volSlider.toDouble()))
                    },
                    colors = SliderDefaults.colors(thumbColor = ColMedia, activeTrackColor = ColMedia,
                        inactiveTrackColor = Color.White.copy(0.15f)),
                    modifier = Modifier.weight(1f).height(28.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaBtn(icon: ImageVector, tint: Color = TextSec, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ── Vacuum ────────────────────────────────────────────────────────────────────

@Composable
private fun VacuumRow(entity: HaState, call: (String, String, Map<String, Any?>) -> Unit) {
    val battery = entity.attributes.optInt("battery_level", -1)
    val isCleaning = entity.state == "cleaning"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Icon(Icons.Default.CleaningServices, null,
            tint = if (isCleaning) ColVacuum else TextSec, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entity.friendlyName, color = TextPri, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = buildString {
                append(vacuumStateZh(entity.state))
                if (battery >= 0) append(" · 🔋$battery%")
            }
            Text(status, color = TextSec, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CoverBtn(if (isCleaning) "暫停" else "啟動", ColVacuum) {
                call(if (isCleaning) "pause" else "start", entity.entityId, emptyMap())
            }
            CoverBtn("回座", TextSec) { call("return_to_base", entity.entityId, emptyMap()) }
        }
    }
}

private fun vacuumStateZh(state: String) = when (state) {
    "cleaning"  -> "清掃中"
    "docked"    -> "已歸座"
    "idle"      -> "閒置"
    "paused"    -> "已暫停"
    "returning" -> "返回中"
    "error"     -> "錯誤"
    else        -> state
}

// ── Toggle (switch / fan / input_boolean) ─────────────────────────────────────

@Composable
private fun ToggleRow(entity: HaState, call: (String, String, Map<String, Any?>) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        val icon = when (entity.domain) {
            "fan"           -> Icons.Default.Air
            "input_boolean" -> Icons.Default.ToggleOn
            else            -> Icons.Default.PowerSettingsNew
        }
        Icon(icon, null, tint = if (entity.isOn) ColToggle else TextSec, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(entity.friendlyName, color = TextPri, fontSize = 13.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(
            checked  = entity.isOn,
            onCheckedChange = { on -> call(if (on) "turn_on" else "turn_off", entity.entityId, emptyMap()) },
            colors   = SwitchDefaults.colors(checkedThumbColor = ColToggle, checkedTrackColor = ColToggle.copy(0.4f))
        )
    }
}

// ── Generic fallback ──────────────────────────────────────────────────────────

@Composable
private fun GenericRow(entity: HaState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Default.DeviceHub, null, tint = TextSec, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(entity.friendlyName, color = TextPri, fontSize = 13.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entity.state, color = TextSec, fontSize = 12.sp)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  LOADING / ERROR
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ColLight)
            Spacer(Modifier.height(16.dp))
            Text("載入中…", color = TextSec)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onSettings: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Default.CloudOff, null, tint = TextSec, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextSec, textAlign = TextAlign.Center, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRetry,
                    colors  = ButtonDefaults.buttonColors(containerColor = ColLight.copy(0.15f), contentColor = ColLight)
                ) { Text("重試") }
                Button(
                    onClick = onSettings,
                    colors  = ButtonDefaults.buttonColors(containerColor = ColMedia.copy(0.15f), contentColor = ColMedia)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("HA設定")
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  HA SETTINGS DIALOG
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Dialog for configuring the direct HA connection (independent of satellite).
 * [onSave] is called after saving so the caller can bump sessionVersion.
 */
@Composable
internal fun HaSettingsDialog(
    config: APPConfig,
    onDismiss: () -> Unit,
    onSave: () -> Unit = {},
) {
    var url   by remember { mutableStateOf(config.haDirectUrl) }
    var token by remember { mutableStateOf(config.haDirectToken) }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = SurfColor,
        titleContentColor = TextPri,
        textContentColor  = TextSec,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Home, null, tint = ColLight, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Home Assistant 直連設定", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor       = TextPri,
                unfocusedTextColor     = TextPri,
                focusedLabelColor      = ColLight,
                unfocusedLabelColor    = TextSec,
                focusedBorderColor     = ColLight,
                unfocusedBorderColor   = TextSec,
                cursorColor            = TextPri,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("HA 伺服器 URL") },
                    placeholder   = { Text("http://192.168.0.x:8123", color = TextSec) },
                    singleLine    = true,
                    colors        = fieldColors,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value               = token,
                    onValueChange       = { token = it },
                    label               = { Text("長期存取權杖 (LLAT)") },
                    singleLine          = true,
                    visualTransformation = if (showToken) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "隱藏" else "顯示",
                                tint = TextSec
                            )
                        }
                    },
                    colors   = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "在 HA → 使用者設定 → 長期存取權杖 中建立",
                    color = TextSec, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                config.haDirectUrl   = url.trim()
                config.haDirectToken = token.trim()
                onSave()
                onDismiss()
            }) {
                Text("儲存", color = ColLight, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSec)
            }
        }
    )
}

// ═════════════════════════════════════════════════════════════════════════════
//  MA SETTINGS DIALOG
// ═════════════════════════════════════════════════════════════════════════════

@Composable
internal fun MaSettingsDialog(config: APPConfig, onDismiss: () -> Unit) {
    var url      by remember { mutableStateOf(config.maUrl) }
    var username by remember { mutableStateOf(config.maUsername) }
    var password by remember { mutableStateOf(config.maPassword) }
    var showPwd  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfColor,
        titleContentColor = TextPri,
        textContentColor  = TextSec,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, null, tint = ColMedia, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Music Assistant 設定", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedTextColor       = TextPri,
                unfocusedTextColor     = TextPri,
                focusedLabelColor      = ColMedia,
                unfocusedLabelColor    = TextSec,
                focusedBorderColor     = ColMedia,
                unfocusedBorderColor   = TextSec,
                cursorColor            = TextPri,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("主機 URL") },
                    placeholder   = { Text("http://192.168.0.x:8095", color = TextSec) },
                    singleLine    = true,
                    colors        = fieldColors,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text("使用者名稱") },
                    singleLine    = true,
                    colors        = fieldColors,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value               = password,
                    onValueChange       = { password = it },
                    label               = { Text("密碼") },
                    singleLine          = true,
                    visualTransformation = if (showPwd) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPwd = !showPwd }) {
                            Icon(
                                if (showPwd) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPwd) "隱藏密碼" else "顯示密碼",
                                tint = TextSec
                            )
                        }
                    },
                    colors   = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    config.maUrl      = url.trim()
                    config.maUsername = username.trim()
                    config.maPassword = password
                    MaApiClient.invalidateToken()
                    onDismiss()
                }
            ) {
                Text("儲存", color = ColMedia, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSec)
            }
        }
    )
}
