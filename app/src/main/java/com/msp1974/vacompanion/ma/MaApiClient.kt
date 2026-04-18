package com.msp1974.vacompanion.ma

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Music Assistant JSON-RPC client (websocket transport).
 *
 * API: WS {baseUrl}/ws  →  {"message_id":1,"command":"...", "args":{...}}
 * Auth: {"command":"auth/login","args":{"username":"...","password":"..."}}
 *       Response: {"success":true, "access_token":"JWT..."}
 */
object MaApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val nextMessageId = AtomicInteger(1)

    // ── HTTP clients ──────────────────────────────────────────────────────────

    private val trustAllCerts = arrayOf<TrustManager>(
        @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate?>?, a: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate?>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate?> = arrayOf()
        }
    )

    private val localClient: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("SSL").apply { init(null, trustAllCerts, SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Strict client for public CDN image URLs. */
    private val publicClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ── Token cache ───────────────────────────────────────────────────────────

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry:  Long   = 0L

    // ── Auth ──────────────────────────────────────────────────────────────────

    private suspend fun getToken(session: MaSession): String? {
        if (session.username.isBlank() && session.password.isBlank()) return null
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiry) return it }

        return withContext(Dispatchers.IO) {
            // ── Strategy 1: auth/login command (MA 1.x / custom deployments) ──
            if (session.username.isNotBlank()) {
                val loginToken = runCatching {
                    val msg = wsCommand(session, "auth/login", authToken = null) { cmd ->
                        cmd.put("args", JSONObject().apply {
                            put("username", session.username)
                            put("password", session.password)
                        })
                    } ?: return@runCatching null
                    val result = msg.optJSONObject("result") ?: return@runCatching null
                    if (!result.optBoolean("success", false)) return@runCatching null
                    result.optString("access_token", "").takeIf { it.isNotEmpty() }
                }.getOrNull()
                if (loginToken != null) {
                    cachedToken = loginToken
                    tokenExpiry = now + 23 * 3600 * 1000L
                    return@withContext loginToken
                }
            }

            // ── Strategy 2: password is a direct Bearer token (MA 2.x HA LLAT) ──
            if (session.password.isNotBlank()) {
                android.util.Log.d("MaApiClient", "auth/login failed, using password as Bearer token")
                cachedToken = session.password
                tokenExpiry = now + 23 * 3600 * 1000L
                return@withContext session.password
            }

            null
        }
    }

    // ── JSON-RPC call ──────────────────────────────────────────────────────────

    private suspend fun call(
        session: MaSession,
        command: String,
        args: JSONObject = JSONObject()
    ): String? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = if (session.username.isNotBlank() || session.password.isNotBlank()) {
                    getToken(session)
                } else null
                if ((session.username.isNotBlank() || session.password.isNotBlank()) && token == null) {
                    android.util.Log.w("MaApiClient", "getToken returned null, proceeding without auth")
                }
                val raw = httpCommand(session, command, token, args) ?: return@runCatching null
                // HTTP /api may return:
                // 1) raw result array/object, or
                // 2) websocket-like envelope {result: ...}, or
                // 3) error envelope.
                runCatching {
                    val obj = JSONObject(raw)
                    when {
                        obj.has("error_code") -> {
                            android.util.Log.w(
                                "MaApiClient",
                                "call $command error=${obj.optInt("error_code")} details=${obj.optString("details")}"
                            )
                            null
                        }
                        obj.has("result") && !obj.isNull("result") -> obj.get("result").toString()
                        else -> raw
                    }
                }.getOrElse { raw }
            }.onFailure { e ->
                android.util.Log.e("MaApiClient", "call $command exception: $e")
            }.getOrNull()
        }
    }

    private suspend fun httpCommand(
        session: MaSession,
        command: String,
        authToken: String?,
        args: JSONObject = JSONObject()
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = toApiUrl(session.baseUrl)
            val payload = JSONObject()
                .put("message_id", nextMessageId.getAndIncrement())
                .put("command", command)
            if (args.length() > 0) {
                payload.put("args", args)
            }

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
            if (authToken != null) {
                requestBuilder.header("Authorization", "Bearer $authToken")
            }

            localClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("MaApiClient", "httpCommand $command failed http=${response.code}")
                    return@runCatching null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching null
                body
            }
        }.getOrNull()
    }

    private suspend fun wsCommand(
        session: MaSession,
        command: String,
        authToken: String?,
        fillPayload: (JSONObject) -> Unit = {}
    ): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val wsUrl = toWebSocketUrl(session.baseUrl)
            val request = Request.Builder().url(wsUrl).build()
            val resultRef = AtomicReference<JSONObject?>(null)
            val latch = CountDownLatch(1)
            val cmdId = 2

            val ws = localClient.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val msgId = msg.optString("message_id", "")
                    when {
                        msgId == "1" && msg.has("error_code") -> {
                            resultRef.set(null); latch.countDown()
                        }
                        msgId == "1" && authToken != null -> {
                            val cmd = JSONObject()
                                .put("message_id", cmdId)
                                .put("command", command)
                            fillPayload(cmd)
                            webSocket.send(cmd.toString())
                        }
                        msgId == cmdId.toString() || (authToken == null && msgId == "1") -> {
                            resultRef.set(msg); latch.countDown()
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }
            })

            if (authToken != null) {
                ws.send(
                    JSONObject()
                        .put("message_id", 1)
                        .put("command", "auth")
                        .put("args", JSONObject().put("access_token", authToken))
                        .toString()
                )
            } else {
                val cmd = JSONObject()
                    .put("message_id", 1)
                    .put("command", command)
                fillPayload(cmd)
                ws.send(cmd.toString())
            }

            latch.await(12, TimeUnit.SECONDS)
            runCatching { ws.close(1000, "done") }
            resultRef.get()
        }.getOrNull()
    }

    private fun toWebSocketUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}/ws"
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}/ws"
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> "$normalized/ws"
            else -> "ws://$normalized/ws"
        }
    }

    private fun toApiUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.endsWith("/api") -> normalized
            else -> "$normalized/api"
        }
    }

    suspend fun listenForEvents(
        session: MaSession,
        onEvent: (JSONObject) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getToken(session) ?: return@withContext false
        runCatching {
            val wsUrl = toWebSocketUrl(session.baseUrl)
            val request = Request.Builder().url(wsUrl).build()
            val closeLatch = CountDownLatch(1)
            var didAuthenticate = false

            val ws = localClient.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return

                    // First frame is server info; authenticate right away.
                    if (msg.has("server_id") && !didAuthenticate) {
                        webSocket.send(
                            JSONObject()
                                .put("message_id", 1)
                                .put("command", "auth")
                                .put("args", JSONObject().put("access_token", token))
                                .toString()
                        )
                        return
                    }

                    if (msg.optString("message_id", "") == "1") {
                        val authenticated = msg.optJSONObject("result")
                            ?.optBoolean("authenticated", false) == true
                        didAuthenticate = authenticated
                        return
                    }

                    if (didAuthenticate && isEventMessage(msg)) {
                        onEvent(msg)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeLatch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closeLatch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closeLatch.countDown()
                }
            })

            closeLatch.await(6, TimeUnit.HOURS)
            runCatching { ws.close(1000, "done") }
            true
        }.getOrDefault(false)
    }

    // ── Browse (raw, for diagnostics) ────────────────────────────────────────

    /** Returns the raw JSON string for a browse path, for debugging. */
    suspend fun browsePathRaw(session: MaSession, path: String, limit: Int = 50): String {
        val args = JSONObject().apply {
            if (path.isNotEmpty()) put("path", path)
            put("limit", limit)
        }
        return call(session, "music/browse", args) ?: "(call returned null)"
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    /**
     * Browse the MA library tree.
     * [path] = "" (root) / "builtin://" / "spotify--xxx://" / "library://artist/1" etc.
     */
    suspend fun browsePath(
        session: MaSession,
        path: String,
        limit: Int = 60
    ): List<MaBrowseItem> {
        val args = JSONObject().apply {
            if (path.isNotEmpty()) put("path", path)
            put("limit", limit)
        }
        val raw = call(session, "music/browse", args)
        if (raw == null) {
            android.util.Log.w("MaApiClient", "browsePath '$path' → call returned null")
            return emptyList()
        }
        return runCatching {
            // MA 回傳裸 array 或包在 {"items":[...]} 裡，兩種都處理
            val arr: JSONArray = runCatching { JSONArray(raw) }.getOrNull()
                ?: runCatching {
                    JSONObject(raw).let { obj ->
                        obj.optJSONArray("items") ?: obj.optJSONArray("results") ?: JSONArray()
                    }
                }.getOrNull() ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toMaBrowseItem() }.getOrNull()
            }.filter { item ->
                if (item.name == "..") return@filter false
                // MA 2.x library root: every provider tile uses item_id "root" — must keep them.
                // Deeper browse levels: hide the parent-link row that reuses item_id "root".
                path.isEmpty() || item.itemId != "root"
            }
            android.util.Log.d("MaApiClient", "browsePath '$path' → ${items.size} items, raw[0..100]=${raw.take(100)}")
            items
        }.onFailure { e ->
            android.util.Log.e("MaApiClient", "browsePath '$path' parse error: $e  raw=${raw.take(200)}")
        }.getOrDefault(emptyList())
    }

    // ── Search ────────────────────────────────────────────────────────────────

    suspend fun searchMusic(
        session: MaSession,
        query: String,
        limit: Int = 25
    ): MaSearchResults {
        val args = JSONObject().apply {
            put("search_query", query)
            put("media_types", JSONArray().apply {
                put("artist"); put("album"); put("track"); put("playlist")
            })
            put("limit", limit)
        }
        val raw = call(session, "music/search", args) ?: return MaSearchResults()
        return runCatching {
            val obj = JSONObject(raw)
            MaSearchResults(
                artists   = obj.optJSONArray("artists")?.parseItems()   ?: emptyList(),
                albums    = obj.optJSONArray("albums")?.parseItems()    ?: emptyList(),
                tracks    = obj.optJSONArray("tracks")?.parseItems()    ?: emptyList(),
                playlists = obj.optJSONArray("playlists")?.parseItems() ?: emptyList()
            )
        }.getOrDefault(MaSearchResults())
    }

    private fun JSONArray.parseItems(): List<MaBrowseItem> =
        (0 until length()).mapNotNull { i ->
            runCatching { getJSONObject(i).toMaBrowseItem() }.getOrNull()
        }

    // ── Playlist tracks ───────────────────────────────────────────────────────

    /**
     * Fetch tracks inside a playlist. This is required for MA setups where
     * browsing library://playlist/<id> only returns the back-navigation row.
     */
    suspend fun getPlaylistTracks(
        session: MaSession,
        itemId: String,
        providerInstanceIdOrDomain: String,
        forceRefresh: Boolean = false
    ): List<MaBrowseItem> {
        if (itemId.isBlank() || providerInstanceIdOrDomain.isBlank()) return emptyList()
        val args = JSONObject().apply {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", providerInstanceIdOrDomain)
            put("force_refresh", forceRefresh)
        }
        val raw = call(session, "music/playlists/playlist_tracks", args) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toMaBrowseItem() }.getOrNull()
            }
        }.onFailure { e ->
            android.util.Log.e("MaApiClient", "getPlaylistTracks failed: $e raw=${raw.take(200)}")
        }.getOrDefault(emptyList())
    }

    suspend fun getAlbumTracks(
        session: MaSession,
        itemId: String,
        providerInstanceIdOrDomain: String
    ): List<MaBrowseItem> {
        if (itemId.isBlank() || providerInstanceIdOrDomain.isBlank()) return emptyList()
        val args = JSONObject().apply {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", providerInstanceIdOrDomain)
        }
        val raw = call(session, "music/albums/album_tracks", args) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toMaBrowseItem() }.getOrNull()
            }
        }.onFailure { e ->
            android.util.Log.e("MaApiClient", "getAlbumTracks failed: $e raw=${raw.take(200)}")
        }.getOrDefault(emptyList())
    }

    suspend fun getArtistTracks(
        session: MaSession,
        itemId: String,
        providerInstanceIdOrDomain: String
    ): List<MaBrowseItem> {
        if (itemId.isBlank() || providerInstanceIdOrDomain.isBlank()) return emptyList()
        val args = JSONObject().apply {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", providerInstanceIdOrDomain)
        }
        val raw = call(session, "music/artists/artist_tracks", args) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toMaBrowseItem() }.getOrNull()
            }
        }.onFailure { e ->
            android.util.Log.e("MaApiClient", "getArtistTracks failed: $e raw=${raw.take(200)}")
        }.getOrDefault(emptyList())
    }

    suspend fun getTrackAsBrowseItem(
        session: MaSession,
        itemId: String,
        providerInstanceIdOrDomain: String
    ): MaBrowseItem? {
        if (itemId.isBlank() || providerInstanceIdOrDomain.isBlank()) return null
        val args = JSONObject().apply {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", providerInstanceIdOrDomain)
        }
        val raw = call(session, "music/tracks/get", args) ?: return null
        return runCatching {
            JSONObject(raw).toMaBrowseItem()
        }.onFailure { e ->
            android.util.Log.e("MaApiClient", "getTrackAsBrowseItem failed: $e raw=${raw.take(200)}")
        }.getOrNull()
    }

    // ── Players ───────────────────────────────────────────────────────────────

    suspend fun getPlayers(session: MaSession): List<MaPlayer> {
        val raw = call(session, "players/all") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val currentItem = obj.optJSONObject("current_item")
                    val artists = currentItem?.optJSONArray("artists")
                    val groupChildIds = extractGroupChildPlayerIds(obj)
                    MaPlayer(
                        playerId = obj.optString("player_id", ""),
                        name     = obj.optString("name", ""),
                        state    = obj.optString("state", "idle"),
                        volumeLevel = obj.optInt("volume_level", 0),
                        elapsedTime = obj.optDouble("elapsed_time", 0.0).toFloat(),
                        elapsedTimeLastUpdated = obj.optLong("elapsed_time_last_updated", 0L),
                        currentTrackName = currentItem?.optString("name", "") ?: "",
                        currentArtistName = artists?.optJSONObject(0)?.optString("name", "") ?: "",
                        currentDuration = currentItem?.optInt("duration", 0) ?: 0,
                        groupChildPlayerIds = groupChildIds
                    )
                }.getOrNull()
            }.filter { it.playerId.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun extractGroupChildPlayerIds(obj: JSONObject): List<String> {
        val candidates = listOf(
            obj.optJSONArray("group_childs"),
            obj.optJSONArray("group_children"),
            obj.optJSONArray("group_members"),
            obj.optJSONArray("members")
        )
        for (arr in candidates) {
            if (arr == null || arr.length() == 0) continue
            val ids = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> if (item.isNotBlank()) ids.add(item)
                    is JSONObject -> {
                        val id = item.optString("player_id", "")
                            .ifBlank { item.optString("child_player_id", "") }
                            .ifBlank { item.optString("id", "") }
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
            }
            val distinctIds = ids.distinct()
            if (distinctIds.isNotEmpty()) return distinctIds
        }
        return emptyList()
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    /**
     * Play / queue media on a player.
     * [option]: "play" (replace & play), "next" (play next), "add" (add to end)
     */
    suspend fun playMedia(
        session: MaSession,
        queueId: String,
        uri: String,
        option: String = "play"
    ) {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("media", uri)
            put("option", option)
        }
        call(session, "player_queues/play_media", args)
    }

    // ── Queue items (used by MaMediaPlayerRow) ────────────────────────────────

    suspend fun getQueueItems(
        session: MaSession,
        queueId: String,
        limit: Int = 5
    ): List<MaQueueItem> {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("limit", limit + 1)
            put("offset", 0)
        }
        val raw = call(session, "player_queues/items", args) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            // Skip index 0 (currently playing)
            val start = if (arr.length() > 1) 1 else 0
            (start until minOf(arr.length(), start + limit)).mapNotNull { i ->
                runCatching {
                    val item = arr.getJSONObject(i)
                    val media = item.optJSONObject("media_item")
                    val artist = media?.optJSONArray("artists")
                        ?.optJSONObject(0)?.optString("name", "") ?: ""
                    val imageUrl = media?.resolveImageUrl()
                        ?: item.resolveImageUrl()
                    MaQueueItem(
                        queueItemId = item.optString("queue_item_id", ""),
                        name        = item.optString("name", ""),
                        artist      = artist,
                        duration    = item.optInt("duration", 0),
                        imageUrl    = imageUrl,
                        mediaItemUri = media?.optString("uri", "") ?: "",
                        album = media?.optJSONObject("album")?.optString("name", "") ?: ""
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getQueueSnapshot(
        session: MaSession,
        queueId: String,
        limit: Int = 80
    ): MaQueueSnapshot? {
        val args = JSONObject().apply { put("queue_id", queueId) }
        val queueRaw = call(session, "player_queues/get", args) ?: return null
        val itemArgs = JSONObject().apply {
            put("queue_id", queueId)
            put("limit", limit)
            put("offset", 0)
        }
        val itemsRaw = call(session, "player_queues/items", itemArgs) ?: "[]"
        return runCatching {
            val queueObj = JSONObject(queueRaw)
            val arr = JSONArray(itemsRaw)
            val items = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val item = arr.getJSONObject(i)
                    val media = item.optJSONObject("media_item")
                    val artist = media?.optJSONArray("artists")
                        ?.optJSONObject(0)?.optString("name", "") ?: ""
                    MaQueueItem(
                        queueItemId = item.optString("queue_item_id", ""),
                        name = item.optString("name", ""),
                        artist = artist,
                        duration = item.optInt("duration", 0),
                        imageUrl = media?.resolveImageUrl() ?: item.resolveImageUrl(),
                        mediaItemUri = media?.optString("uri", "") ?: "",
                        album = media?.optJSONObject("album")?.optString("name", "") ?: ""
                    )
                }.getOrNull()
            }
            MaQueueSnapshot(
                queueId = queueId,
                state = queueObj.optString("state", "idle"),
                repeatMode = queueObj.optString("repeat_mode", "off"),
                shuffleEnabled = queueObj.optBoolean("shuffle_enabled", false),
                currentIndex = queueObj.optInt("current_index", -1),
                elapsedTime = queueObj.optDouble("elapsed_time", 0.0).toFloat(),
                items = items
            )
        }.getOrNull()
    }

    suspend fun runQueueCommand(
        session: MaSession,
        queueId: String,
        action: String,
        extras: JSONObject = JSONObject()
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            extras.keys().forEach { key -> put(key, extras.get(key)) }
        }
        return call(session, "player_queues/$action", args) != null
    }

    suspend fun setShuffleEnabled(
        session: MaSession,
        queueId: String,
        enabled: Boolean
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("shuffle_enabled", enabled)
        }

        if (call(session, "player_queues/shuffle", args) != null) return true
        val toggleArgs = JSONObject().apply { put("queue_id", queueId) }
        if (call(session, "player_queues/shuffle", toggleArgs) != null) return true
        return false
    }

    suspend fun setRepeatMode(
        session: MaSession,
        queueId: String,
        repeatMode: String
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("repeat_mode", repeatMode)
        }

        if (call(session, "player_queues/repeat", args) != null) return true
        val toggleArgs = JSONObject().apply { put("queue_id", queueId) }
        if (call(session, "player_queues/repeat", toggleArgs) != null) return true
        return false
    }

    suspend fun setVolume(
        session: MaSession,
        playerId: String,
        volume: Int
    ): Boolean {
        val args = JSONObject().apply {
            put("player_id", playerId)
            put("volume_level", volume.coerceIn(0, 100))
        }
        return call(session, "players/cmd/volume_set", args) != null
    }

    suspend fun seek(
        session: MaSession,
        queueId: String,
        seconds: Float
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("position", seconds.roundToInt().coerceAtLeast(0))
        }
        return call(session, "player_queues/seek", args) != null
    }

    suspend fun moveQueueItem(
        session: MaSession,
        queueId: String,
        queueItemId: String,
        position: Int
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("queue_item_id", queueItemId)
            put("pos", position.coerceAtLeast(0))
        }
        return call(session, "player_queues/move_item", args) != null
    }

    suspend fun removeQueueItem(
        session: MaSession,
        queueId: String,
        queueItemId: String
    ): Boolean {
        val args = JSONObject().apply {
            put("queue_id", queueId)
            put("queue_item_id", queueItemId)
        }
        return call(session, "player_queues/delete_item", args) != null
    }

    suspend fun clearQueue(
        session: MaSession,
        queueId: String
    ): Boolean {
        val args = JSONObject().apply { put("queue_id", queueId) }
        return call(session, "player_queues/clear", args) != null
    }

    suspend fun fetchLyricsByUri(
        session: MaSession,
        uri: String
    ): MaLyrics {
        if (uri.isBlank()) return MaLyrics()

        // First strategy: fetch complete item and read metadata lyrics fields.
        val itemArgs = JSONObject().apply { put("uri", uri) }
        val itemRaw = call(session, "music/item_by_uri", itemArgs)
        if (itemRaw != null) {
            val parsed = parseLyrics(itemRaw)
            if (!parsed.isEmpty) return parsed
        }

        // Fallback strategy: fetch track detail by item_id/provider.
        val provider = uri.substringBefore("://", "")
        val tail = uri.substringAfter("://", "")
        val mediaType = tail.substringBefore("/")
        val itemId = tail.substringAfter("/", "")
        if (provider.isNotBlank() && mediaType == "track" && itemId.isNotBlank()) {
            val trackArgs = JSONObject().apply {
                put("item_id", itemId)
                put("provider_instance_id_or_domain", provider)
            }
            val trackRaw = call(session, "music/tracks/get", trackArgs)
            if (trackRaw != null) {
                val parsed = parseLyrics(trackRaw)
                if (!parsed.isEmpty) return parsed
            }
        }
        return MaLyrics()
    }

    private fun parseLyrics(raw: String): MaLyrics {
        return runCatching {
            val obj = JSONObject(raw)
            val rootCandidates = buildList {
                add(obj.optJSONObject("lyrics"))
                add(obj.optJSONObject("metadata")?.optJSONObject("lyrics"))
                add(obj.optJSONObject("media_item")?.optJSONObject("metadata")?.optJSONObject("lyrics"))
                add(obj)
                add(obj.optJSONObject("metadata"))
                add(obj.optJSONObject("media_item")?.optJSONObject("metadata"))
            }
            val lines = buildList {
                for (candidate in rootCandidates) {
                    if (candidate == null) continue
                    val lyricsText = candidate.optString("lyrics", "").normalizeLyricString()
                    if (lyricsText.isNotBlank()) {
                        addAll(lyricsText.toLyricLines())
                    }
                    val merged = candidate.optString("text", "").normalizeLyricString()
                    if (isEmpty() && merged.isNotBlank()) {
                        addAll(merged.toLyricLines())
                    }
                    val lrcLyrics = candidate.optString("lrc_lyrics", "").normalizeLyricString()
                    if (isEmpty() && lrcLyrics.isNotBlank()) {
                        addAll(lrcLyrics.toLyricLines())
                    }
                    val lrc = candidate.optString("lrc", "").normalizeLyricString()
                    if (isEmpty() && lrc.isNotBlank()) {
                        addAll(lrc.toLyricLines())
                    }
                    if (isNotEmpty()) break
                }
            }
            MaLyrics(lines)
        }.getOrDefault(MaLyrics())
    }

    // ── Image loading ─────────────────────────────────────────────────────────

    /** In-memory image cache: url → (timestamp, bytes). 5-min TTL. */
    private val imageCache = HashMap<String, Pair<Long, ByteArray>>()
    private const val IMAGE_TTL_MS = 5 * 60 * 1000L

    /**
     * Fetch a browse image. Handles Spotify CDN (public, no auth) and
     * local MA imageproxy URLs (needs MA Bearer token).
     */
    suspend fun fetchBrowseImage(
        imageUrl: String,
        session: MaSession? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null

        imageCache[imageUrl]?.let { (ts, bytes) ->
            if (System.currentTimeMillis() - ts < IMAGE_TTL_MS) {
                return@withContext runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }

        runCatching {
            val isPublic = imageUrl.startsWith("https://")
            val http = if (isPublic) publicClient else localClient

            val reqBuilder = Request.Builder().url(imageUrl).get()
            if (!isPublic && session != null) {
                val token = getToken(session)
                if (token != null) reqBuilder.header("Authorization", "Bearer $token")
            }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()?.also { bytes ->
                    imageCache[imageUrl] = System.currentTimeMillis() to bytes
                }.let { bytes ->
                    if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    else null
                }
            }
        }.getOrNull()
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    fun invalidateToken() {
        cachedToken = null
        tokenExpiry = 0L
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun JSONObject.resolveImageUrl(): String? {
        // Direct "image" field on item
        optJSONObject("image")?.optString("path", "")?.takeIf { it.isNotEmpty() }?.let { return it }
        // metadata.images[0].path
        optJSONObject("metadata")?.optJSONArray("images")?.optJSONObject(0)
            ?.optString("path", "")?.takeIf { it.isNotEmpty() }?.let { return it }
        // album.image.path (for tracks)
        optJSONObject("album")?.optJSONObject("image")
            ?.optString("path", "")?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun String.normalizeLyricString(): String {
        val trimmed = trim()
        if (trimmed.equals("null", ignoreCase = true)) return ""
        if (trimmed.equals("none", ignoreCase = true)) return ""
        return trimmed
    }

    private fun String.toLyricLines(): List<String> =
        lines()
            .map { it.trimEnd() }
            .map { it.normalizeLyricString() }
            .filter { it.isNotBlank() }

    private fun isEventMessage(message: JSONObject): Boolean {
        if (message.has("event")) return true
        if (message.has("event_name")) return true
        if (message.has("type")) {
            val typeValue = message.optString("type", "")
            if (typeValue.isNotBlank() && !typeValue.equals("result", ignoreCase = true)) return true
        }
        if (message.has("object_id")) return true
        return false
    }

    fun shouldRefreshForPlaybackEvent(message: JSONObject, selectedPlayerId: String?): Boolean {
        val eventName = sequenceOf(
            message.optString("event", ""),
            message.optString("event_name", ""),
            message.optString("type", "")
        ).firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()

        val objectId = message.optString("object_id", "").lowercase()
        val payload = message.optJSONObject("data") ?: message.optJSONObject("event_data")
        val payloadString = payload?.toString()?.lowercase().orEmpty()
        val msgString = message.toString().lowercase()
        val pid = selectedPlayerId?.lowercase()

        val touchesPlaybackDomain =
            eventName.contains("player") ||
                eventName.contains("queue") ||
                eventName.contains("media") ||
                eventName.contains("track") ||
                objectId.contains("player") ||
                objectId.contains("queue")

        if (!touchesPlaybackDomain) return false

        if (pid == null) return true
        if (objectId.contains(pid)) return true
        if (payloadString.contains(pid)) return true
        if (msgString.contains(pid)) return true

        // Player-agnostic queue/media events can still impact selection/current track.
        return eventName.contains("queue") || eventName.contains("track") || eventName.contains("media")
    }

    private fun JSONObject.toMaBrowseItem(): MaBrowseItem {
        val mediaType = optString("media_type", "folder")
        val uri  = optString("uri", "")
        val path = optString("path", uri)

        val artistName = optJSONArray("artists")
            ?.optJSONObject(0)?.optString("name", "") ?: ""
        val albumObj = optJSONObject("album")
        val albumName = albumObj?.optString("name", "") ?: ""

        // Image: direct field → metadata.images[0] → album.image
        val imageUrl = resolveImageUrl()

        // MA 2.x stores localised names in translation_key when name is blank
        val translationKey = optString("translation_key", "")
        val displayName = optString("name", "").ifEmpty { translationKey }

        return MaBrowseItem(
            itemId     = optString("item_id", ""),
            uri        = uri,
            name       = displayName,
            mediaType  = mediaType,
            path       = path,
            imageUrl   = imageUrl,
            artistName = artistName,
            albumName  = albumName,
            duration   = optInt("duration", 0),
            year       = albumObj?.optInt("year", 0) ?: optInt("year", 0),
            isPlayable = optBoolean("is_playable", false),
            isFavorite = optBoolean("favorite", false)
        )
    }
}
