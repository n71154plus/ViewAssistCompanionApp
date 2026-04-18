package com.msp1974.vacompanion.ui.layouts

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.ma.MaApiClient
import com.msp1974.vacompanion.ma.MaBrowseItem
import com.msp1974.vacompanion.ma.MaLyrics
import com.msp1974.vacompanion.ma.MaPlayer
import com.msp1974.vacompanion.ma.MaQueueItem
import com.msp1974.vacompanion.ma.MaQueueSnapshot
import com.msp1974.vacompanion.ma.MaSearchResults
import com.msp1974.vacompanion.ma.MaSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Shared colours (match HaControlScreen dark theme) ────────────────────────

private val MaBg        = Color(0xFF0F0F0F)
private val MaSurf      = Color(0xFF1A1A22)
private val MaCard      = Color(0xFF1E1E2E)
private val MaCardOn    = Color(0xFF252540)
private val MaDivider   = Color(0xFF2A2A3A)
private val MaTextPri   = Color(0xFFEEEEEE)
private val MaTextSec   = Color(0xFF8888AA)
private val MaAccent    = Color(0xFFFF80AB)   // ColMedia
private val MaGold      = Color(0xFFFFD060)   // ColLight

// ── Navigation state ──────────────────────────────────────────────────────────

private data class BrowseNode(
    val path: String,
    val title: String,
    val mediaType: String = "folder",
    val uri: String = "",
    val itemId: String = "",
    val provider: String = ""
)

private enum class PlaylistSortMode {
    Default, Title, Artist, Duration
}

// ═════════════════════════════════════════════════════════════════════════════
//  MAIN SCREEN
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaLibraryScreen(maSession: MaSession) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Navigation ────────────────────────────────────────────────────────────
    val navStack: SnapshotStateList<BrowseNode> =
        remember { mutableStateListOf(BrowseNode("", "Music Assistant")) }
    val currentNode = navStack.last()

    // ── Search ────────────────────────────────────────────────────────────────
    var searchQuery   by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<MaSearchResults?>(null) }
    val isSearching   = searchQuery.isNotEmpty()

    // ── Browse ────────────────────────────────────────────────────────────────
    var browseItems by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(false) }

    // ── Players ───────────────────────────────────────────────────────────────
    var players          by remember { mutableStateOf<List<MaPlayer>>(emptyList()) }
    var selectedPlayerId by remember { mutableStateOf<String?>(null) }
    var showPlayerDrop   by remember { mutableStateOf(false) }
    var queueSnapshot    by remember { mutableStateOf<MaQueueSnapshot?>(null) }
    var nowLyrics        by remember { mutableStateOf(MaLyrics()) }
    var currentLyricsUri by remember { mutableStateOf<String?>(null) }
    var refreshTick      by remember { mutableIntStateOf(0) }
    var showPlayerPanel  by remember { mutableStateOf(true) }

    // ── Play sheet ────────────────────────────────────────────────────────────
    var playItem by remember { mutableStateOf<MaBrowseItem?>(null) }
    var playlistQuery by remember { mutableStateOf("") }
    var playlistSortMode by remember { mutableStateOf(PlaylistSortMode.Default) }
    var playlistAscending by remember { mutableStateOf(true) }
    var playlistCompact by remember { mutableStateOf(false) }

    // ── Load browse items when nav changes ────────────────────────────────────
    LaunchedEffect(currentNode.path, currentNode.mediaType, currentNode.itemId, currentNode.provider) {
        if (!isSearching) {
            isLoading = true
            val providerCandidates = buildList {
                val uriProvider = currentNode.provider.trim()
                val pathProvider = currentNode.path.substringBefore("://", "").trim()
                if (uriProvider.isNotEmpty()) add(uriProvider)
                if (pathProvider.isNotEmpty()) add(pathProvider)
                add("library")
                add("builtin")
            }.distinct()

            suspend fun tryPlaylistTracks(): List<MaBrowseItem> {
                for (candidate in providerCandidates) {
                    val items = MaApiClient.getPlaylistTracks(
                        session = maSession,
                        itemId = currentNode.itemId,
                        providerInstanceIdOrDomain = candidate
                    )
                    if (items.isNotEmpty()) return items
                }
                return emptyList()
            }

            suspend fun tryAlbumTracks(): List<MaBrowseItem> {
                for (candidate in providerCandidates) {
                    val items = MaApiClient.getAlbumTracks(
                        session = maSession,
                        itemId = currentNode.itemId,
                        providerInstanceIdOrDomain = candidate
                    )
                    if (items.isNotEmpty()) return items
                }
                return emptyList()
            }

            suspend fun tryArtistTracks(): List<MaBrowseItem> {
                for (candidate in providerCandidates) {
                    val items = MaApiClient.getArtistTracks(
                        session = maSession,
                        itemId = currentNode.itemId,
                        providerInstanceIdOrDomain = candidate
                    )
                    if (items.isNotEmpty()) return items
                }
                return emptyList()
            }

            suspend fun tryTrackItem(): List<MaBrowseItem> {
                for (candidate in providerCandidates) {
                    val track = MaApiClient.getTrackAsBrowseItem(
                        session = maSession,
                        itemId = currentNode.itemId,
                        providerInstanceIdOrDomain = candidate
                    )
                    if (track != null) return listOf(track)
                }
                return emptyList()
            }

            browseItems = when {
                currentNode.mediaType == "playlist" && currentNode.itemId.isNotBlank() -> {
                    tryPlaylistTracks()
                }
                currentNode.mediaType == "album" && currentNode.itemId.isNotBlank() -> {
                    tryAlbumTracks()
                }
                currentNode.mediaType == "artist" && currentNode.itemId.isNotBlank() -> {
                    tryArtistTracks()
                }
                currentNode.mediaType == "track" && currentNode.itemId.isNotBlank() -> {
                    tryTrackItem()
                }
                else -> {
                    MaApiClient.browsePath(maSession, currentNode.path)
                }
            }
            isLoading = false
        }
    }

    // ── Load players once ─────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        players = MaApiClient.getPlayers(maSession)
        selectedPlayerId = players.firstOrNull { it.state == "playing" }?.playerId
            ?: players.firstOrNull()?.playerId
    }

    suspend fun refreshPlayerState() {
        val refreshed = MaApiClient.getPlayers(maSession)
        if (refreshed.isNotEmpty()) {
            players = refreshed
            if (selectedPlayerId == null) {
                selectedPlayerId = refreshed.firstOrNull { it.state == "playing" }?.playerId
                    ?: refreshed.firstOrNull()?.playerId
            }
        }

        val pid = selectedPlayerId
        if (pid != null) {
            val snapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
            queueSnapshot = snapshot
            val current = snapshot?.items?.getOrNull(snapshot.currentIndex)
            val currentTrackUri = current?.mediaItemUri?.takeIf { it.isNotBlank() }
            when {
                currentTrackUri == null -> {
                    currentLyricsUri = null
                    nowLyrics = MaLyrics()
                }
                currentTrackUri != currentLyricsUri -> {
                    currentLyricsUri = currentTrackUri
                    nowLyrics = MaApiClient.fetchLyricsByUri(maSession, currentTrackUri)
                }
            }
        } else {
            queueSnapshot = null
            currentLyricsUri = null
            nowLyrics = MaLyrics()
        }
    }

    LaunchedEffect(selectedPlayerId, refreshTick) {
        refreshPlayerState()
    }

    // Event-driven updates via long-lived WebSocket.
    LaunchedEffect(maSession.baseUrl, maSession.username, maSession.password) {
        while (true) {
            val ok = MaApiClient.listenForEvents(maSession) { event ->
                if (MaApiClient.shouldRefreshForPlaybackEvent(event, selectedPlayerId)) {
                    scope.launch { refreshTick++ }
                }
            }
            if (!ok) {
                delay(5000)
            } else {
                // Stream ended unexpectedly: back off briefly and reconnect.
                delay(2000)
            }
        }
    }

    // Low-frequency fallback reconcile in case events are missed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30000)
            refreshTick++
        }
    }

    // ── Search with debounce ──────────────────────────────────────────────────
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            delay(450)
            if (searchQuery.length >= 2) {
                isLoading = true
                searchResults = MaApiClient.searchMusic(maSession, searchQuery)
                isLoading = false
            }
        } else {
            searchResults = null
        }
    }

    // ── Home page sections ────────────────────────────────────────────────────
    val isHome = navStack.size == 1 && navStack[0].path == "" && !isSearching

    // ── Navigate into item ────────────────────────────────────────────────────
    fun navigateTo(item: MaBrowseItem) {
        val path = if (item.isFolder) item.path else item.uri
        val provider = item.uri.substringBefore("://", missingDelimiterValue = "")
        navStack.add(
            BrowseNode(
                path = path,
                title = item.name,
                mediaType = item.mediaType,
                uri = item.uri,
                itemId = item.itemId,
                provider = provider
            )
        )
        playlistQuery = ""
        playlistSortMode = PlaylistSortMode.Default
        playlistAscending = true
        if (isSearching) searchQuery = ""
    }

    Scaffold(
        containerColor = MaBg,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(MaBg)
        ) {
        // ── Search bar ────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaSurf)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Back button when inside sub-folder
            if (navStack.size > 1 && !isSearching) {
                IconButton(
                    onClick = { navStack.removeAt(navStack.lastIndex) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                        tint = MaAccent, modifier = Modifier.size(20.dp))
                }
            }

            OutlinedTextField(
                value         = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder   = { Text("搜尋音樂…", color = MaTextSec, fontSize = 13.sp) },
                leadingIcon   = { Icon(Icons.Default.Search, null, tint = MaTextSec, modifier = Modifier.size(18.dp)) },
                trailingIcon  = if (searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, null, tint = MaTextSec, modifier = Modifier.size(16.dp))
                    }}
                } else null,
                singleLine   = true,
                colors       = OutlinedTextFieldDefaults.colors(
                    focusedTextColor     = MaTextPri,
                    unfocusedTextColor   = MaTextPri,
                    focusedBorderColor   = MaAccent,
                    unfocusedBorderColor = MaTextSec,
                    cursorColor          = MaTextPri,
                ),
                shape        = RoundedCornerShape(20.dp),
                modifier     = Modifier
                    .weight(1f)
                    .height(44.dp)
            )
        }

        // ── Breadcrumb + player selector bar ─────────────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaSurf)
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            // Breadcrumb (only in browse mode, depth > 1)
            if (!isSearching && navStack.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)) {
                    Text(
                        navStack.dropLast(1).last().title,
                        color = MaAccent, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { navStack.removeAt(navStack.lastIndex) }
                    )
                    Text(" / ", color = MaTextSec, fontSize = 11.sp)
                    Text(currentNode.title,
                        color = MaTextPri, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            // Player selector
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showPlayerDrop = true }
                ) {
                    Icon(Icons.Default.Speaker, null, tint = MaTextSec, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        players.find { it.playerId == selectedPlayerId }?.name ?: "選擇裝置",
                        color = MaTextPri, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaTextSec, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = showPlayerDrop,
                    onDismissRequest = { showPlayerDrop = false }
                ) {
                    players.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(8.dp)
                                            .background(
                                                if (p.state == "playing") MaAccent else MaTextSec,
                                                CircleShape
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(p.name, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                selectedPlayerId = p.playerId
                                showPlayerDrop = false
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaDivider)

        PlayerControlPanel(
            player = players.find { it.playerId == selectedPlayerId },
            allPlayers = players,
            queueSnapshot = queueSnapshot,
            lyrics = nowLyrics,
            expanded = showPlayerPanel,
            onToggleExpand = { showPlayerPanel = !showPlayerPanel },
            onControlAction = { action ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = when (action) {
                        "shuffle" -> {
                            val nextShuffle = !(queueSnapshot?.shuffleEnabled ?: false)
                            MaApiClient.setShuffleEnabled(maSession, pid, nextShuffle)
                        }
                        "repeat" -> {
                            val currentRepeat = queueSnapshot?.repeatMode ?: "off"
                            val nextRepeat = if (currentRepeat == "off") "all" else "off"
                            MaApiClient.setRepeatMode(maSession, pid, nextRepeat)
                        }
                        else -> MaApiClient.runQueueCommand(maSession, pid, action)
                    }
                    if (!ok) snackbarHostState.showSnackbar("控制失敗：$action")
                    queueSnapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
                }
            },
            onSeek = { second ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = MaApiClient.seek(maSession, pid, second)
                    if (!ok) snackbarHostState.showSnackbar("拖曳進度失敗")
                }
            },
            onSetVolume = { volume ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = MaApiClient.setVolume(maSession, pid, volume)
                    if (!ok) snackbarHostState.showSnackbar("調整音量失敗")
                }
            },
            onSetMemberVolume = { memberPlayerId, volume ->
                scope.launch {
                    val ok = MaApiClient.setVolume(maSession, memberPlayerId, volume)
                    if (!ok) snackbarHostState.showSnackbar("調整群組成員音量失敗")
                    players = MaApiClient.getPlayers(maSession)
                }
            },
            onQueuePlayNow = { item ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                if (item.mediaItemUri.isBlank()) return@PlayerControlPanel
                scope.launch {
                    MaApiClient.playMedia(maSession, pid, item.mediaItemUri, option = "play")
                    queueSnapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
                }
            },
            onQueueMove = { item, target ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = MaApiClient.moveQueueItem(maSession, pid, item.queueItemId, target)
                    if (!ok) snackbarHostState.showSnackbar("移動佇列失敗")
                    queueSnapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
                }
            },
            onQueueRemove = { item ->
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = MaApiClient.removeQueueItem(maSession, pid, item.queueItemId)
                    if (!ok) snackbarHostState.showSnackbar("移除佇列項目失敗")
                    queueSnapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
                }
            },
            onClearQueue = {
                val pid = selectedPlayerId ?: return@PlayerControlPanel
                scope.launch {
                    val ok = MaApiClient.clearQueue(maSession, pid)
                    if (!ok) snackbarHostState.showSnackbar("清空佇列失敗")
                    queueSnapshot = MaApiClient.getQueueSnapshot(maSession, pid, limit = 100)
                }
            }
        )

        // ── Content ───────────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && !isHome -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaAccent, modifier = Modifier.size(36.dp))
                    }
                }

                isSearching && searchQuery.length >= 2 -> {
                    val results = searchResults
                    if (results != null) {
                        if (results.isEmpty) {
                            EmptyView("找不到「$searchQuery」的結果")
                        } else {
                            SearchResultsView(
                                results   = results,
                                maSession = maSession,
                                onPlay    = { playItem = it },
                                onNavigate = { navigateTo(it) }
                            )
                        }
                    }
                }

                isHome -> {
                    MaHomeView(
                        maSession  = maSession,
                        onPlay     = { playItem = it },
                        onNavigate = { navigateTo(it) }
                    )
                }

                else -> {
                    if (browseItems.isEmpty() && !isLoading) {
                        EmptyView("此目錄沒有內容")
                    } else {
                        if (navStack.size > 1) {
                            LibraryDetailView(
                                playlistName = currentNode.title,
                                playlistUri = currentNode.uri.ifEmpty { currentNode.path },
                                collectionPlayable = currentNode.mediaType != "folder",
                                items = browseItems,
                                maSession = maSession,
                                selectedPlayerId = selectedPlayerId,
                                query = playlistQuery,
                                onQueryChange = { playlistQuery = it },
                                sortMode = playlistSortMode,
                                onSortModeChange = { playlistSortMode = it },
                                ascending = playlistAscending,
                                onToggleAscending = { playlistAscending = !playlistAscending },
                                compactMode = playlistCompact,
                                onToggleCompactMode = { playlistCompact = !playlistCompact },
                                onPlayItem = { playItem = it },
                                onNavigate = { navigateTo(it) },
                                onQueueAction = { option, target ->
                                    val pid = selectedPlayerId
                                    if (pid != null) {
                                        scope.launch {
                                            MaApiClient.playMedia(
                                                session = maSession,
                                                queueId = pid,
                                                uri = target,
                                                option = option
                                            )
                                        }
                                    }
                                },
                                onShowMessage = { message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            )
                        } else {
                            BrowseView(
                                items     = browseItems,
                                maSession = maSession,
                                onNavigate = { navigateTo(it) },
                                onPlay     = { playItem = it }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // ── Play bottom sheet ─────────────────────────────────────────────────────
    val item = playItem
    if (item != null) {
        PlayBottomSheet(
            item             = item,
            maSession        = maSession,
            players          = players,
            selectedPlayerId = selectedPlayerId,
            onPlayerSelect   = { selectedPlayerId = it },
            onDismiss        = { playItem = null },
            onPlay           = { queueId, option ->
                scope.launch {
                    MaApiClient.playMedia(maSession, queueId, item.uri, option)
                    playItem = null
                }
            }
        )
    }
}

@Composable
private fun LibraryDetailView(
    playlistName: String,
    playlistUri: String,
    collectionPlayable: Boolean,
    items: List<MaBrowseItem>,
    maSession: MaSession,
    selectedPlayerId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    sortMode: PlaylistSortMode,
    onSortModeChange: (PlaylistSortMode) -> Unit,
    ascending: Boolean,
    onToggleAscending: () -> Unit,
    compactMode: Boolean,
    onToggleCompactMode: () -> Unit,
    onPlayItem: (MaBrowseItem) -> Unit,
    onNavigate: (MaBrowseItem) -> Unit,
    onQueueAction: (option: String, targetUri: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val songItems = remember(items) {
        items.filter { it.mediaType == "track" || it.mediaType == "radio" }
    }
    val hasTracks = songItems.isNotEmpty()
    val effectiveSongs = if (songItems.isNotEmpty()) songItems else items
    val queryNormalized = query.trim().lowercase()
    val filteredSongs = remember(effectiveSongs, queryNormalized) {
        if (queryNormalized.isBlank()) effectiveSongs
        else effectiveSongs.filter {
            it.name.lowercase().contains(queryNormalized) ||
                it.artistName.lowercase().contains(queryNormalized) ||
                it.albumName.lowercase().contains(queryNormalized)
        }
    }
    val sortedSongs = remember(filteredSongs, sortMode, ascending) {
        val sorted = when (sortMode) {
            PlaylistSortMode.Default -> filteredSongs
            PlaylistSortMode.Title -> filteredSongs.sortedBy { it.name.lowercase() }
            PlaylistSortMode.Artist -> filteredSongs.sortedBy { it.artistName.lowercase() }
            PlaylistSortMode.Duration -> filteredSongs.sortedBy { it.duration }
        }
        if (ascending) sorted else sorted.reversed()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = playlistName,
                        color = MaTextPri,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    if (hasTracks) "歌曲 ${effectiveSongs.size}" else "內容 ${effectiveSongs.size}",
                                    fontSize = 11.sp
                                )
                            }
                        )
                        val totalSec = effectiveSongs.sumOf { it.duration }
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("總長 ${maFormatTime(totalSec)}", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(if (selectedPlayerId == null) "未選擇裝置" else "已選擇裝置", fontSize = 11.sp) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val canPlayCollection = selectedPlayerId != null && playlistUri.isNotBlank() && collectionPlayable
                        FilledTonalButton(
                            onClick = { onQueueAction("play", playlistUri) },
                            enabled = canPlayCollection,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全部播放", fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = { onQueueAction("next", playlistUri) },
                            enabled = canPlayCollection,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("下一首播放", fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = { onQueueAction("add", playlistUri) },
                            enabled = canPlayCollection,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AddToQueue, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("加入佇列", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("在清單中搜尋歌曲", fontSize = 12.sp, color = MaTextSec) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MaTextSec, modifier = Modifier.size(18.dp)) },
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, null, tint = MaTextSec, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else null,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaTextPri,
                        unfocusedTextColor = MaTextPri,
                        focusedBorderColor = MaAccent,
                        unfocusedBorderColor = MaDivider
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = compactMode,
                    onClick = onToggleCompactMode,
                    label = { Text(if (compactMode) "緊湊" else "標準", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            if (compactMode) Icons.Default.ViewAgenda else Icons.Default.ViewList,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(PlaylistSortMode.entries) { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { onSortModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    PlaylistSortMode.Default -> "預設排序"
                                    PlaylistSortMode.Title -> "歌名"
                                    PlaylistSortMode.Artist -> "歌手"
                                    PlaylistSortMode.Duration -> "長度"
                                },
                                fontSize = 11.sp
                            )
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = onToggleAscending,
                        label = { Text(if (ascending) "遞增" else "遞減", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        if (sortedSongs.isEmpty()) {
            item {
                EmptyView("此內容沒有符合條件的項目")
            }
            return@LazyColumn
        }

        itemsIndexed(sortedSongs) { index, track ->
            PlaylistTrackRow(
                item = track,
                trackNumber = index + 1,
                maSession = maSession,
                compactMode = compactMode,
                onNavigate = onNavigate,
                onPlayItem = onPlayItem,
                onQueueAction = onQueueAction,
                onShowMessage = onShowMessage
            )
            HorizontalDivider(color = MaDivider.copy(alpha = 0.5f), modifier = Modifier.padding(start = 68.dp))
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    item: MaBrowseItem,
    trackNumber: Int,
    maSession: MaSession,
    compactMode: Boolean,
    onNavigate: (MaBrowseItem) -> Unit,
    onPlayItem: (MaBrowseItem) -> Unit,
    onQueueAction: (option: String, targetUri: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val rowPadding = if (compactMode) 6.dp else 10.dp
    var showMoreMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = rowPadding)
            .combinedClickable(
                onClick = {
                if (item.mediaType in setOf("artist", "album", "playlist", "folder")) onNavigate(item)
                else onPlayItem(item)
                },
                onLongClick = { showMoreMenu = true }
            )
    ) {
        Text(
            text = "#$trackNumber",
            color = MaTextSec,
            fontSize = if (compactMode) 10.sp else 11.sp,
            modifier = Modifier.width(if (compactMode) 28.dp else 32.dp)
        )

        var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(item.imageUrl) {
            bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
        }
        Box(
            modifier = Modifier
                .size(if (compactMode) 36.dp else 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaCardOn)
        ) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    mediaTypeIcon(item.mediaType),
                    null,
                    tint = MaAccent.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).size(if (compactMode) 18.dp else 22.dp)
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = MaTextPri,
                fontSize = if (compactMode) 12.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (item.artistName.isNotEmpty()) append(item.artistName)
                if (item.albumName.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(item.albumName)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = MaTextSec,
                    fontSize = if (compactMode) 10.sp else 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.duration > 0) {
            Text(maFormatTime(item.duration), color = MaTextSec, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = { onQueueAction("next", item.uri) }, modifier = Modifier.size(if (compactMode) 28.dp else 32.dp)) {
            Icon(Icons.Default.SkipNext, null, tint = MaTextSec, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onQueueAction("add", item.uri) }, modifier = Modifier.size(if (compactMode) 28.dp else 32.dp)) {
            Icon(Icons.Default.QueueMusic, null, tint = MaTextSec, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onPlayItem(item) }, modifier = Modifier.size(if (compactMode) 30.dp else 34.dp)) {
            Icon(Icons.Default.PlayArrow, null, tint = MaAccent, modifier = Modifier.size(20.dp))
        }
    }

    DropdownMenu(
        expanded = showMoreMenu,
        onDismissRequest = { showMoreMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("前往專輯") },
            leadingIcon = { Icon(Icons.Default.Album, null) },
            enabled = item.albumName.isNotBlank(),
            onClick = {
                showMoreMenu = false
                scope.launch {
                    val results = MaApiClient.searchMusic(maSession, item.albumName, limit = 10)
                    val target = results.albums.firstOrNull {
                        it.name.equals(item.albumName, ignoreCase = true)
                    } ?: results.albums.firstOrNull()
                    if (target != null) onNavigate(target)
                    else onShowMessage("找不到對應專輯")
                }
            }
        )
        DropdownMenuItem(
            text = { Text("前往歌手") },
            leadingIcon = { Icon(Icons.Default.Person, null) },
            enabled = item.artistName.isNotBlank(),
            onClick = {
                showMoreMenu = false
                scope.launch {
                    val results = MaApiClient.searchMusic(maSession, item.artistName, limit = 10)
                    val target = results.artists.firstOrNull {
                        it.name.equals(item.artistName, ignoreCase = true)
                    } ?: results.artists.firstOrNull()
                    if (target != null) onNavigate(target)
                    else onShowMessage("找不到對應歌手")
                }
            }
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  BROWSE VIEW
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun BrowseView(
    items: List<MaBrowseItem>,
    maSession: MaSession,
    onNavigate: (MaBrowseItem) -> Unit,
    onPlay: (MaBrowseItem) -> Unit
) {
    // Determine dominant content type to choose layout
    val trackCount  = items.count { it.mediaType == "track" || it.mediaType == "radio" }
    val folderCount = items.count { it.isFolder }
    val useList     = trackCount > items.size / 2 || folderCount > items.size / 2

    if (useList) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier       = Modifier.fillMaxSize()
        ) {
            items(items) { item ->
                BrowseListRow(item, maSession, onNavigate, onPlay)
                HorizontalDivider(color = MaDivider.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 64.dp))
            }
        }
    } else {
        LazyVerticalGrid(
            columns        = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier       = Modifier.fillMaxSize()
        ) {
            items(items) { item ->
                BrowseGridCard(item, maSession, onNavigate, onPlay)
            }
        }
    }
}

// ── Grid card (album / artist / playlist / folder) ────────────────────────────

@Composable
private fun BrowseGridCard(
    item: MaBrowseItem,
    maSession: MaSession,
    onNavigate: (MaBrowseItem) -> Unit,
    onPlay: (MaBrowseItem) -> Unit
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    val isNavigable = item.isFolder || item.mediaType in setOf("artist","album","playlist")

    Card(
        shape  = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaCard),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isNavigable) onNavigate(item) else onPlay(item) }
    ) {
        Box {
            // Cover image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(MaCardOn)
            ) {
                if (bitmap != null) {
                    Image(bitmap!!.asImageBitmap(), null,
                        contentScale = ContentScale.Crop,
                        modifier     = Modifier.fillMaxSize())
                } else {
                    Icon(
                        mediaTypeIcon(item.mediaType), null,
                        tint     = MaAccent.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.Center).size(40.dp)
                    )
                }
                // Gradient for text legibility
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                        )
                )
            }

            // Play button overlay (playable items)
            if (item.isPlayable && !isNavigable) {
                IconButton(
                    onClick  = { onPlay(item) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(MaAccent, CircleShape)
                ) {
                    Icon(Icons.Default.PlayArrow, null,
                        tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }

            // Favourite heart
            if (item.isFavorite) {
                Icon(Icons.Default.Favorite, null,
                    tint     = MaAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(14.dp))
            }
        }

        // Info below image
        Column(modifier = Modifier.padding(8.dp)) {
            Text(item.name, color = MaTextPri, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                if (item.artistName.isNotEmpty()) append(item.artistName)
                if (item.year > 0) { if (isNotEmpty()) append(" · "); append(item.year) }
            }
            if (sub.isNotEmpty())
                Text(sub, color = MaTextSec, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── List row (track / radio / folder) ────────────────────────────────────────

@Composable
private fun BrowseListRow(
    item: MaBrowseItem,
    maSession: MaSession,
    onNavigate: (MaBrowseItem) -> Unit,
    onPlay: (MaBrowseItem) -> Unit
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    val isNavigable = item.isFolder || item.mediaType in setOf("artist","album","playlist")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isNavigable) onNavigate(item) else onPlay(item) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaCardOn)
        ) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(mediaTypeIcon(item.mediaType), null,
                    tint     = MaAccent.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).size(22.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = MaTextPri, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                if (item.artistName.isNotEmpty()) append(item.artistName)
                if (item.albumName.isNotEmpty() && item.albumName != item.name) {
                    if (isNotEmpty()) append(" · "); append(item.albumName)
                }
            }
            if (sub.isNotEmpty())
                Text(sub, color = MaTextSec, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Duration / chevron
        if (item.duration > 0) {
            Text(maFormatTime(item.duration), color = MaTextSec, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (isNavigable) {
            Icon(Icons.Default.ChevronRight, null, tint = MaTextSec, modifier = Modifier.size(18.dp))
        } else {
            IconButton(onClick = { onPlay(item) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, null, tint = MaAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  SEARCH RESULTS
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SearchResultsView(
    results: MaSearchResults,
    maSession: MaSession,
    onPlay: (MaBrowseItem) -> Unit,
    onNavigate: (MaBrowseItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier       = Modifier.fillMaxSize()
    ) {
        // ── Artists ───────────────────────────────────────────────────────────
        if (results.artists.isNotEmpty()) {
            item {
                SectionHeader("藝術家", Icons.Default.Person)
                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results.artists) { artist ->
                        ArtistChip(artist, maSession, onClick = {
                            if (artist.mediaType == "artist") onNavigate(artist) else onPlay(artist)
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── Albums ────────────────────────────────────────────────────────────
        if (results.albums.isNotEmpty()) {
            item {
                SectionHeader("專輯", Icons.Default.Album)
                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results.albums) { album ->
                        AlbumSearchCard(album, maSession, onClick = {
                            onNavigate(album)
                        }, onPlay = { onPlay(album) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── Tracks ────────────────────────────────────────────────────────────
        if (results.tracks.isNotEmpty()) {
            item { SectionHeader("歌曲", Icons.Default.MusicNote) }
            items(results.tracks) { track ->
                BrowseListRow(track, maSession, onNavigate = {}, onPlay = onPlay)
                HorizontalDivider(color = MaDivider.copy(0.5f), modifier = Modifier.padding(start = 68.dp))
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Playlists ─────────────────────────────────────────────────────────
        if (results.playlists.isNotEmpty()) {
            item {
                SectionHeader("播放清單", Icons.Default.PlaylistPlay)
                LazyRow(
                    contentPadding        = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(results.playlists) { pl ->
                        AlbumSearchCard(pl, maSession, onClick = { onNavigate(pl) }, onPlay = { onPlay(pl) })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(icon, null, tint = MaAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = MaTextPri, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArtistChip(item: MaBrowseItem, maSession: MaSession, onClick: () -> Unit) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(64.dp)
                .clip(CircleShape).background(MaCardOn)
        ) {
            if (bitmap != null)
                Image(bitmap!!.asImageBitmap(), null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else
                Icon(Icons.Default.Person, null,
                    tint = MaAccent.copy(0.4f), modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(item.name, color = MaTextPri, fontSize = 10.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AlbumSearchCard(
    item: MaBrowseItem,
    maSession: MaSession,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    Column(
        modifier = Modifier.width(120.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)).background(MaCardOn)
        ) {
            if (bitmap != null)
                Image(bitmap!!.asImageBitmap(), null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else
                Icon(Icons.Default.Album, null,
                    tint = MaAccent.copy(0.4f), modifier = Modifier.align(Alignment.Center).size(32.dp))

            IconButton(
                onClick  = onPlay,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .size(28.dp).background(MaAccent, CircleShape)
            ) {
                Icon(Icons.Default.PlayArrow, null,
                    tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(item.name, color = MaTextPri, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.artistName.isNotEmpty())
            Text(item.artistName, color = MaTextSec, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  PLAY BOTTOM SHEET
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayBottomSheet(
    item: MaBrowseItem,
    maSession: MaSession,
    players: List<MaPlayer>,
    selectedPlayerId: String?,
    onPlayerSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onPlay: (queueId: String, option: String) -> Unit
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaSurf
    ) {
        Column(modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 24.dp)) {

            // ── Item info ─────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(60.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaCardOn)
                ) {
                    if (bitmap != null)
                        Image(bitmap!!.asImageBitmap(), null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else
                        Icon(mediaTypeIcon(item.mediaType), null,
                            tint = MaAccent.copy(0.4f),
                            modifier = Modifier.align(Alignment.Center).size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, color = MaTextPri, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.artistName.isNotEmpty())
                        Text(item.artistName, color = MaTextSec, fontSize = 12.sp, maxLines = 1)
                    if (item.albumName.isNotEmpty() && item.albumName != item.name)
                        Text(item.albumName, color = MaTextSec, fontSize = 11.sp, maxLines = 1)
                }
            }

            HorizontalDivider(color = MaDivider)
            Spacer(Modifier.height(12.dp))

            // ── Player selection ──────────────────────────────────────────────
            Text("播放到", color = MaTextSec, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            players.forEach { player ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (player.playerId == selectedPlayerId) MaCardOn else Color.Transparent)
                        .clickable { onPlayerSelect(player.playerId) }
                        .padding(10.dp, 8.dp)
                ) {
                    Box(
                        modifier = Modifier.size(10.dp)
                            .background(
                                if (player.state == "playing") MaAccent else MaTextSec,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(player.name, color = MaTextPri, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (player.playerId == selectedPlayerId)
                        Icon(Icons.Default.Check, null, tint = MaAccent, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaDivider)
            Spacer(Modifier.height(12.dp))

            // ── Action buttons ────────────────────────────────────────────────
            val pid = selectedPlayerId
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PlayActionBtn(
                    label    = "立即播放",
                    icon     = Icons.Default.PlayArrow,
                    color    = MaAccent,
                    enabled  = pid != null,
                    modifier = Modifier.weight(1f)
                ) { if (pid != null) onPlay(pid, "play") }

                PlayActionBtn(
                    label    = "下一首",
                    icon     = Icons.Default.SkipNext,
                    color    = MaTextPri,
                    enabled  = pid != null,
                    modifier = Modifier.weight(1f)
                ) { if (pid != null) onPlay(pid, "next") }

                PlayActionBtn(
                    label    = "加入佇列",
                    icon     = Icons.Default.AddToQueue,
                    color    = MaTextPri,
                    enabled  = pid != null,
                    modifier = Modifier.weight(1f)
                ) { if (pid != null) onPlay(pid, "add") }
            }
        }
    }
}

@Composable
private fun PlayActionBtn(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(10.dp),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PlayerControlPanel(
    player: MaPlayer?,
    allPlayers: List<MaPlayer>,
    queueSnapshot: MaQueueSnapshot?,
    lyrics: MaLyrics,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onControlAction: (String) -> Unit,
    onSeek: (Float) -> Unit,
    onSetVolume: (Int) -> Unit,
    onSetMemberVolume: (String, Int) -> Unit,
    onQueuePlayNow: (MaQueueItem) -> Unit,
    onQueueMove: (MaQueueItem, Int) -> Unit,
    onQueueRemove: (MaQueueItem) -> Unit,
    onClearQueue: () -> Unit
) {
    val currentQueueItem = queueSnapshot?.items?.getOrNull(queueSnapshot.currentIndex)
    val lrcLines = remember(lyrics.lines) { parseLrcLines(lyrics.lines) }
    var liveElapsedMs by remember(queueSnapshot?.elapsedTime, player?.elapsedTime, player?.state) {
        mutableStateOf(
            (((queueSnapshot?.elapsedTime ?: player?.elapsedTime ?: 0f) * 1000f).roundToInt()).toLong()
        )
    }
    var localSeekValue by remember(queueSnapshot?.elapsedTime, player?.elapsedTime) {
        mutableStateOf(queueSnapshot?.elapsedTime ?: player?.elapsedTime ?: 0f)
    }
    var localVolume by remember(player?.volumeLevel) {
        mutableFloatStateOf((player?.volumeLevel ?: 0).toFloat())
    }
    LaunchedEffect(queueSnapshot?.elapsedTime, player?.elapsedTime, player?.state) {
        liveElapsedMs = (((queueSnapshot?.elapsedTime ?: player?.elapsedTime ?: 0f) * 1000f).roundToInt()).toLong()
        while (true) {
            delay(500)
            if (player?.state == "playing") {
                liveElapsedMs += 500
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = player?.name ?: "尚未選擇播放器",
                        color = MaTextPri,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (player?.state) {
                            "playing" -> "播放中"
                            "paused" -> "暫停"
                            "off" -> "關閉"
                            else -> "待命"
                        },
                        color = MaTextSec,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = MaAccent
                    )
                }
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(8.dp))
            Text(
                currentQueueItem?.name ?: player?.currentTrackName?.ifBlank { "目前無歌曲" } ?: "目前無歌曲",
                color = MaTextPri,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = currentQueueItem?.artist?.ifBlank { player?.currentArtistName ?: "" }
                ?: player?.currentArtistName.orEmpty()
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = MaTextSec,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val duration = currentQueueItem?.duration?.takeIf { it > 0 }
                ?: player?.currentDuration?.takeIf { it > 0 }
                ?: 0
            if (duration > 0) {
                Slider(
                    value = localSeekValue.coerceIn(0f, duration.toFloat()),
                    onValueChange = { localSeekValue = it },
                    onValueChangeFinished = { onSeek(localSeekValue) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = MaAccent, activeTrackColor = MaAccent)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(maFormatTime(localSeekValue.toInt()), color = MaTextSec, fontSize = 10.sp)
                    Text(maFormatTime(duration), color = MaTextSec, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { onControlAction("previous") }) {
                    Icon(Icons.Default.SkipPrevious, null, tint = MaTextPri)
                }
                IconButton(onClick = {
                    onControlAction(if (player?.state == "playing") "pause" else "play")
                }) {
                    Icon(
                        if (player?.state == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        tint = MaAccent
                    )
                }
                IconButton(onClick = { onControlAction("next") }) {
                    Icon(Icons.Default.SkipNext, null, tint = MaTextPri)
                }
                IconButton(onClick = { onControlAction("stop") }) {
                    Icon(Icons.Default.Stop, null, tint = MaTextPri)
                }
                Spacer(Modifier.weight(1f))
                ElevatedAssistChip(
                    onClick = onClearQueue,
                    colors = AssistChipDefaults.elevatedAssistChipColors(
                        containerColor = Color(0xFF5A1D27),
                        labelColor = Color(0xFFFFDCE1),
                        leadingIconContentColor = Color(0xFFFF8FA3)
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = Color(0xFFFF8FA3)
                    ),
                    label = { Text("清空佇列", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.ClearAll, null, modifier = Modifier.size(14.dp)) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VolumeDown, null, tint = MaTextSec, modifier = Modifier.size(16.dp))
                Slider(
                    value = localVolume.coerceIn(0f, 100f),
                    onValueChange = { localVolume = it },
                    onValueChangeFinished = { onSetVolume(localVolume.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = MaAccent, activeTrackColor = MaAccent)
                )
                Icon(Icons.Default.VolumeUp, null, tint = MaTextSec, modifier = Modifier.size(16.dp))
            }

            val groupMembers = remember(player?.playerId, player?.groupChildPlayerIds, allPlayers) {
                val memberIds = player?.groupChildPlayerIds ?: emptyList()
                if (memberIds.isEmpty()) emptyList()
                else allPlayers.filter { it.playerId in memberIds }
            }
            if (groupMembers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("群組成員音量", color = MaTextPri, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                groupMembers.forEach { member ->
                    key(member.playerId) {
                        var localMemberVolume by remember(member.playerId, member.volumeLevel) {
                            mutableFloatStateOf(member.volumeLevel.toFloat())
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = member.name,
                                color = MaTextSec,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(92.dp)
                            )
                            Slider(
                                value = localMemberVolume.coerceIn(0f, 100f),
                                onValueChange = { localMemberVolume = it },
                                onValueChangeFinished = {
                                    onSetMemberVolume(member.playerId, localMemberVolume.toInt())
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaAccent,
                                    activeTrackColor = MaAccent
                                )
                            )
                            Text(
                                text = "${localMemberVolume.toInt()}",
                                color = MaTextSec,
                                fontSize = 10.sp,
                                modifier = Modifier.width(28.dp)
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shuffleOn = queueSnapshot?.shuffleEnabled == true
                FilterChip(
                    selected = shuffleOn,
                    onClick = { onControlAction("shuffle") },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = shuffleOn,
                        borderColor = if (shuffleOn) MaAccent else MaDivider,
                        selectedBorderColor = MaAccent
                    ),
                    label = {
                        Text(
                            if (shuffleOn) "隨機：開" else "隨機：關",
                            fontSize = 11.sp,
                            fontWeight = if (shuffleOn) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (shuffleOn) MaAccent else MaTextSec
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Shuffle,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = if (shuffleOn) MaAccent else MaTextSec
                        )
                    }
                )
                val repeatOn = (queueSnapshot?.repeatMode ?: "off") != "off"
                FilterChip(
                    selected = repeatOn,
                    onClick = { onControlAction("repeat") },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = repeatOn,
                        borderColor = if (repeatOn) MaGold else MaDivider,
                        selectedBorderColor = MaGold
                    ),
                    label = {
                        Text(
                            if (repeatOn) "重複：開" else "重複：關",
                            fontSize = 11.sp,
                            fontWeight = if (repeatOn) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (repeatOn) MaGold else MaTextSec
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Repeat,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = if (repeatOn) MaGold else MaTextSec
                        )
                    }
                )
            }

            Spacer(Modifier.height(6.dp))
            Text("目前佇列", color = MaTextPri, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            val queueItems = queueSnapshot?.items ?: emptyList()
            if (queueItems.isEmpty()) {
                Text("此播放器暫無佇列內容", color = MaTextSec, fontSize = 11.sp)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(queueItems) { idx, item ->
                        QueueItemRow(
                            item = item,
                            index = idx,
                            totalItems = queueItems.size,
                            isCurrent = idx == queueSnapshot?.currentIndex,
                            onPlayNow = { onQueuePlayNow(item) },
                            onDragMove = { target ->
                                if (target == idx) return@QueueItemRow
                                onQueueMove(item, target)
                            },
                            onRemove = { onQueueRemove(item) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("歌詞", color = MaTextPri, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (lrcLines.isNotEmpty()) {
                LyricsLrcView(
                    lines = lrcLines,
                    elapsedMs = liveElapsedMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .background(MaSurf, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            } else {
                val lyricText = lyrics.text.takeIf { it.isNotBlank() } ?: "目前歌曲沒有可用歌詞"
                Text(
                    lyricText,
                    color = MaTextSec,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(MaSurf, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: MaQueueItem,
    index: Int,
    totalItems: Int,
    isCurrent: Boolean,
    onPlayNow: () -> Unit,
    onDragMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    var dragTotalY by remember { mutableFloatStateOf(0f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaCardOn else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .pointerInput(index, totalItems) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragTotalY = 0f },
                    onDragEnd = {
                        val slotDelta = (dragTotalY / 44f).roundToInt()
                        val target = (index + slotDelta).coerceIn(0, (totalItems - 1).coerceAtLeast(0))
                        if (target != index) onDragMove(target)
                        dragTotalY = 0f
                    },
                    onDragCancel = { dragTotalY = 0f }
                ) { change, dragAmount ->
                    dragTotalY += dragAmount.y
                }
            }
    ) {
        Text("${index + 1}", color = MaTextSec, fontSize = 10.sp, modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = MaTextPri, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, color = MaTextSec, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onPlayNow, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.PlayArrow, null, tint = MaAccent, modifier = Modifier.size(15.dp))
        }
        Icon(Icons.Default.DragHandle, null, tint = MaTextSec, modifier = Modifier.size(18.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.DeleteOutline, null, tint = MaTextSec, modifier = Modifier.size(14.dp))
        }
    }
}

private data class LrcLine(
    val timeMs: Long,
    val text: String
)

private fun parseLrcLines(lines: List<String>): List<LrcLine> {
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    return lines.flatMap { raw ->
        val matches = regex.findAll(raw).toList()
        if (matches.isEmpty()) return@flatMap emptyList()
        val pureText = raw.replace(regex, "").trim()
        if (pureText.isBlank()) return@flatMap emptyList()
        matches.map { m ->
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val fracRaw = m.groupValues[3]
            val ms = when (fracRaw.length) {
                1 -> fracRaw.toLongOrNull()?.times(100) ?: 0L
                2 -> fracRaw.toLongOrNull()?.times(10) ?: 0L
                else -> fracRaw.take(3).toLongOrNull() ?: 0L
            }
            LrcLine(timeMs = min * 60_000L + sec * 1_000L + ms, text = pureText)
        }
    }.sortedBy { it.timeMs }
}

@Composable
private fun LyricsLrcView(
    lines: List<LrcLine>,
    elapsedMs: Long,
    modifier: Modifier = Modifier
) {
    val currentIndex = remember(lines, elapsedMs) {
        lines.indexOfLast { it.timeMs <= elapsedMs }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, lines.size) {
        if (lines.isNotEmpty() && currentIndex in lines.indices) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        itemsIndexed(lines) { idx, line ->
            val active = idx == currentIndex
            Text(
                text = line.text,
                color = if (active) MaAccent else MaTextSec,
                fontSize = if (active) 12.sp else 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  HELPERS
// ═════════════════════════════════════════════════════════════════════════════

// ═════════════════════════════════════════════════════════════════════════════
//  HOME VIEW
// ═════════════════════════════════════════════════════════════════════════════

private data class RecommendSection(
    val title: String,
    val providerName: String,
    val items: List<MaBrowseItem>
)

@Composable
private fun MaHomeView(
    maSession: MaSession,
    onPlay: (MaBrowseItem) -> Unit,
    onNavigate: (MaBrowseItem) -> Unit,
) {
    // 固定區塊
    var recentlyAdded   by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var recentlyPlayed  by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var playlists       by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var artists         by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var albums          by remember { mutableStateOf<List<MaBrowseItem>>(emptyList()) }
    var loading         by remember { mutableStateOf(true) }
    var debugInfo       by remember { mutableStateOf("") }

    // 各 provider 的推薦區塊（漸進式載入）
    val recommendSections = remember { mutableStateListOf<RecommendSection>() }

    LaunchedEffect(Unit) {
        loading = true
        recommendSections.clear()

        // ── Step 1：取根目錄（4 個 provider） ───────────────────────────────
        val rootItems = MaApiClient.browsePath(maSession, "", limit = 50)

        // ── Step 2：瀏覽 builtin:// 與 library:// 取資料夾 ──────────────────
        val builtinPath = rootItems.find { it.path.startsWith("builtin") }?.path ?: "builtin://"
        val builtinFolders = MaApiClient.browsePath(maSession, builtinPath, limit = 50)
        val libraryPath = rootItems.find { it.path.startsWith("library") }?.path ?: "library://"
        val libraryFolders = MaApiClient.browsePath(maSession, libraryPath, limit = 50)

        // MA 2.x 用 translation_key 當 name，例如 "recently_played"、"recently_added"
        fun findBuiltin(vararg keys: String) =
            builtinFolders.find { item ->
                keys.any { k -> k in item.name.lowercase() || k in item.path.lowercase() }
            }
        fun findLibrary(vararg keys: String) =
            libraryFolders.find { item ->
                keys.any { k -> k in item.name.lowercase() || k in item.path.lowercase() }
            }

        val recentlyAddedFolder  = findBuiltin("recently_added",  "new",    "added")
        val recentlyPlayedFolder = findBuiltin("recently_played", "played", "history")
        val playlistFolder       = findBuiltin("playlists", "playlist")
        val artistFolder         = findLibrary("artists", "artist")
        val albumFolder          = findLibrary("albums", "album")
        val trackFolder          = findBuiltin("tracks", "track")

        // ── Step 3：載入各固定區塊 ──────────────────────────────────────────
        recentlyAdded = recentlyAddedFolder?.let {
            MaApiClient.browsePath(maSession, it.path, limit = 20)
        } ?: emptyList()

        recentlyPlayed = recentlyPlayedFolder?.let {
            MaApiClient.browsePath(maSession, it.path, limit = 20)
        } ?: emptyList()

        // 播放清單：builtin 的 playlists 資料夾
        playlists = playlistFolder?.let {
            MaApiClient.browsePath(maSession, it.path, limit = 20)
        } ?: emptyList()

        // Library provider：artists / albums
        artists = artistFolder?.let {
            MaApiClient.browsePath(maSession, it.path, limit = 20)
        } ?: emptyList()

        albums = albumFolder?.let {
            MaApiClient.browsePath(maSession, it.path, limit = 20)
        } ?: emptyList()

        // 若沒有 recently_added，用全部曲目代替
        if (recentlyAdded.isEmpty() && trackFolder != null) {
            recentlyAdded = MaApiClient.browsePath(maSession, trackFolder.path, limit = 20)
        }

        debugInfo = buildString {
            append("builtin folders: ")
            append(builtinFolders.joinToString { "${it.name}(${it.path})" })
            append(" | library folders: ")
            append(libraryFolders.joinToString { "${it.name}(${it.path})" })
        }
        loading = false

        // ── Step 4：各 provider 的推薦資料夾（背景漸進載入） ────────────────
        // Avoid guessing sub-paths (which can trigger "Invalid subpath" on MA).
        // We only browse server-returned child folders under each provider root.
        val recKeywords = listOf(
            "recommendation", "recommendations",
            "featured", "new_release", "new_releases",
            "suggestion", "suggestions",
            "discover", "top"
        )

        for (provider in rootItems) {
            if (provider.path.startsWith("builtin")) continue
            val provBase = provider.path  // already ends with "://"
            if (!provBase.contains("://")) continue
            val providerDomain = provBase.substringBefore("://", "").lowercase()
            if (
                providerDomain.contains("filesystem") ||
                providerDomain == "library" ||
                providerDomain == "builtin"
            ) continue

            val providerRootItems = MaApiClient.browsePath(maSession, provBase, limit = 80)
            if (providerRootItems.isEmpty()) continue

            val recommendationFolders = providerRootItems.filter { item ->
                if (item.mediaType != "folder") return@filter false
                val haystack = "${item.name} ${item.path}".lowercase()
                recKeywords.any { key -> haystack.contains(key) }
            }

            for (folder in recommendationFolders) {
                val recItems = MaApiClient.browsePath(maSession, folder.path, limit = 30)
                if (recItems.isEmpty()) continue

                val subFolders = recItems.filter { it.mediaType == "folder" }
                for (sub in subFolders) {
                    val sectionItems = MaApiClient.browsePath(maSession, sub.path, limit = 20)
                    if (sectionItems.isNotEmpty()) {
                        recommendSections.add(
                            RecommendSection(
                                title = sub.name,
                                providerName = provider.name,
                                items = sectionItems
                            )
                        )
                    }
                }

                val directItems = recItems.filter { it.mediaType != "folder" }
                if (directItems.isNotEmpty()) {
                    recommendSections.add(
                        RecommendSection(
                            title = folder.name,
                            providerName = provider.name,
                            items = directItems
                        )
                    )
                }
            }
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaAccent, modifier = Modifier.size(36.dp))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaBg),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (recentlyAdded.isNotEmpty()) {
            item {
                HomeSectionHeader(
                    icon  = Icons.Default.FiberNew,
                    title = "最近新增",
                    tint  = MaAccent
                )
            }
            item {
                HomeRowList(items = recentlyAdded, maSession = maSession, onPlay = onPlay, onNavigate = onNavigate)
            }
        }

        if (recentlyPlayed.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                HomeSectionHeader(
                    icon  = Icons.Default.History,
                    title = "最近播放",
                    tint  = MaGold
                )
            }
            item {
                HomeRowList(items = recentlyPlayed, maSession = maSession, onPlay = onPlay, onNavigate = onNavigate)
            }
        }

        if (playlists.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                HomeSectionHeader(
                    icon  = Icons.Default.PlaylistPlay,
                    title = "播放清單",
                    tint  = Color(0xFF60CFFF)
                )
            }
            item {
                HomeRowList(items = playlists, maSession = maSession, onPlay = onPlay, onNavigate = onNavigate)
            }
        }

        if (artists.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                HomeSectionHeader(
                    icon  = Icons.Default.Person,
                    title = "藝術家",
                    tint  = Color(0xFF80CBC4)
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(artists) { item ->
                        HomeCard(item, maSession, onPlay, onNavigate)
                    }
                }
            }
        }

        if (albums.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
            item {
                HomeSectionHeader(
                    icon  = Icons.Default.Album,
                    title = "專輯",
                    tint  = Color(0xFFB39DDB)
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(albums) { item ->
                        HomeCard(item, maSession, onPlay, onNavigate)
                    }
                }
            }
        }

        // 各 provider 推薦區塊（漸進顯示）
        items(recommendSections, key = { "${it.providerName}::${it.title}" }) { section ->
            Spacer(Modifier.height(16.dp))
            HomeSectionHeader(
                icon  = Icons.Default.Star,
                title = "${section.providerName} · ${section.title}",
                tint  = Color(0xFF80DEEA)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(section.items) { item ->
                    HomeCard(item, maSession, onPlay, onNavigate)
                }
            }
        }

        if (
            recentlyAdded.isEmpty() &&
            recentlyPlayed.isEmpty() &&
            playlists.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            recommendSections.isEmpty()
        ) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, null,
                            tint = MaTextSec, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("資料庫尚無內容", color = MaTextSec, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(debugInfo, color = MaTextSec.copy(alpha = 0.6f),
                            fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = MaTextPri, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeRowList(
    items: List<MaBrowseItem>,
    maSession: MaSession,
    onPlay: (MaBrowseItem) -> Unit,
    onNavigate: (MaBrowseItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        items.forEachIndexed { index, item ->
            BrowseListRow(
                item = item,
                maSession = maSession,
                onNavigate = onNavigate,
                onPlay = onPlay
            )
            if (index < items.lastIndex) {
                HorizontalDivider(
                    color = MaDivider.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 64.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeCard(
    item: MaBrowseItem,
    maSession: MaSession,
    onPlay: (MaBrowseItem) -> Unit,
    onNavigate: (MaBrowseItem) -> Unit,
) {
    var bitmap by remember(item.imageUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.imageUrl) {
        bitmap = item.imageUrl?.let { MaApiClient.fetchBrowseImage(it, maSession) }
    }

    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onNavigate(item) }
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaCard)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(mediaTypeIcon(item.mediaType), null,
                        tint = MaTextSec, modifier = Modifier.size(40.dp))
                }
            }
            // Play overlay
            if (item.isPlayable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaAccent.copy(alpha = 0.9f))
                        .clickable { onPlay(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, null,
                        tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            item.name,
            color = MaTextPri, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (item.artistName.isNotEmpty()) {
            Text(
                item.artistName,
                color = MaTextSec, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyView(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.MusicOff, null, tint = MaTextSec, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaTextSec, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun mediaTypeIcon(mediaType: String) = when (mediaType) {
    "artist"   -> Icons.Default.Person
    "album"    -> Icons.Default.Album
    "track"    -> Icons.Default.MusicNote
    "playlist" -> Icons.Default.PlaylistPlay
    "radio"    -> Icons.Default.Radio
    "folder"   -> Icons.Default.Folder
    else       -> Icons.Default.MusicNote
}

private fun maFormatTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

