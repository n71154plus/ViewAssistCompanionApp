package com.msp1974.vacompanion.ha

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Lightweight bundle passed to API calls to avoid threading 3 params everywhere. */
data class HaSession(
    val baseUrl: String,
    val token: String,
    val ignoreSSL: Boolean
)

object HaApiClient {

    // ── SSL trust-all client ──────────────────────────────────────────────────

    private val trustAllCerts = arrayOf<TrustManager>(
        @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate?>?, authType: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate?>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate?> = arrayOf()
        }
    )

    private val trustAllClient: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("SSL").apply { init(null, trustAllCerts, SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private val strictClient: OkHttpClient by lazy { OkHttpClient() }

    private fun client(ignoreSSL: Boolean) = if (ignoreSSL) trustAllClient else strictClient

    // ── Camera image cache (raw bytes, 90 s TTL) ──────────────────────────────

    /** Cache: entityId → (fetchedAtMs, jpegBytes) */
    private val cameraByteCache = HashMap<String, Pair<Long, ByteArray>>()
    private const val CAMERA_TTL_MS = 90_000L

    // ── Public API ────────────────────────────────────────────────────────────

    /** Long-lived websocket stream: initial `get_states` + incremental `state_changed`. */
    fun observeStates(session: HaSession): Flow<List<HaState>> = callbackFlow {
        val wsUrl = toWebSocketUrl(session.baseUrl)
        val request = Request.Builder().url(wsUrl).build()
        val statesById = LinkedHashMap<String, HaState>()
        val getStatesId = 1
        val subscribeId = 2

        val ws = client(session.ignoreSSL).newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "auth_required" -> {
                        val auth = JSONObject()
                            .put("type", "auth")
                            .put("access_token", session.token)
                        webSocket.send(auth.toString())
                    }
                    "auth_ok" -> {
                        webSocket.send(
                            JSONObject()
                                .put("id", getStatesId)
                                .put("type", "get_states")
                                .toString()
                        )
                        webSocket.send(
                            JSONObject()
                                .put("id", subscribeId)
                                .put("type", "subscribe_events")
                                .put("event_type", "state_changed")
                                .toString()
                        )
                    }
                    "result" -> {
                        val id = msg.optInt("id", -1)
                        if (!msg.optBoolean("success", false)) {
                            if (id == getStatesId) close(IllegalStateException("HA get_states failed"))
                            return
                        }
                        if (id == getStatesId) {
                            val arr = msg.optJSONArray("result") ?: JSONArray()
                            statesById.clear()
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val state = obj.toHaStateOrNull() ?: continue
                                statesById[state.entityId] = state
                            }
                            trySend(statesById.values.toList())
                        }
                    }
                    "event" -> {
                        val event = msg.optJSONObject("event") ?: return
                        if (event.optString("event_type") != "state_changed") return
                        val data = event.optJSONObject("data") ?: return
                        val entityId = data.optString("entity_id", "")
                        if (entityId.isEmpty()) return
                        val newState = data.optJSONObject("new_state")
                        if (newState == null || newState == JSONObject.NULL) {
                            statesById.remove(entityId)
                        } else {
                            val parsed = newState.toHaStateOrNull()
                            if (parsed != null) statesById[entityId] = parsed
                        }
                        trySend(statesById.values.toList())
                    }
                    "auth_invalid" -> close(IllegalStateException("HA websocket auth invalid"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })

        awaitClose {
            runCatching { ws.close(1000, "stop") }
        }
    }

    /** Fetch all HA entity states via websocket. Returns empty list on any error. */
    suspend fun getStates(session: HaSession): List<HaState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val wsResp = wsCommand(session, "get_states") ?: return@withContext emptyList()
                if (!wsResp.optBoolean("success", false)) return@withContext emptyList()
                val arr = wsResp.optJSONArray("result") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val obj = arr.getJSONObject(i)
                        HaState(
                            entityId   = obj.getString("entity_id"),
                            state      = obj.getString("state"),
                            attributes = obj.optJSONObject("attributes") ?: JSONObject()
                        )
                    }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Maps each entity_id → effective Home Assistant **area_id** for **房間頁** filtering only.
     * Uses the entity’s own [area_id] in the entity registry if set; otherwise the
     * [area_id] of the entity’s **device** from the device registry.
     * Lights / Climate / Media tabs do not use this map ([forRoomLegacy] instead).
     */
    suspend fun getEntityEffectiveAreaMap(session: HaSession): Map<String, String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val deviceAreaById = fetchDeviceAreaMap(session)
                val wsResp = wsCommand(session, "config/entity_registry/list") ?: return@withContext emptyMap()
                if (!wsResp.optBoolean("success", false)) return@withContext emptyMap()
                val arr = wsResp.optJSONArray("result") ?: return@withContext emptyMap()
                buildMap {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val eid = o.optString("entity_id", "")
                        if (eid.isEmpty()) continue
                        val entityArea = when {
                            !o.has("area_id") || o.isNull("area_id") -> null
                            else -> o.optString("area_id", "").takeIf { it.isNotEmpty() }
                        }
                        val deviceId = o.optString("device_id", "").takeIf { it.isNotEmpty() }
                        val deviceArea = deviceId?.let { deviceAreaById[it] }
                        val effective = entityArea ?: deviceArea
                        if (effective != null && effective.isNotEmpty()) put(eid, effective)
                    }
                }
            }.getOrDefault(emptyMap())
        }

    /** device_id → area_id (only devices with an area assigned). */
    private suspend fun fetchDeviceAreaMap(session: HaSession): Map<String, String> =
        runCatching {
            val wsResp = wsCommand(session, "config/device_registry/list") ?: return@runCatching emptyMap()
            if (!wsResp.optBoolean("success", false)) return@runCatching emptyMap()
            val arr = wsResp.optJSONArray("result") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    val aid = when {
                        !o.has("area_id") || o.isNull("area_id") -> null
                        else -> o.optString("area_id", "").takeIf { it.isNotEmpty() }
                    }
                    if (aid != null) put(id, aid)
                }
            }
        }.getOrDefault(emptyMap())

    /** Call a HA service (e.g. light/turn_on) via websocket. Fails silently. */
    suspend fun callService(
        session: HaSession,
        domain: String,
        service: String,
        entityId: String,
        extra: Map<String, Any?> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val serviceData = JSONObject().apply {
                put("entity_id", entityId)
                extra.forEach { (k, v) -> if (v != null) put(k, v) }
            }
            wsCommand(session, "call_service") { cmd ->
                cmd.put("domain", domain)
                cmd.put("service", service)
                cmd.put("service_data", serviceData)
            }
        }
    }

    private suspend fun wsCommand(
        session: HaSession,
        type: String,
        fillPayload: (JSONObject) -> Unit = {}
    ): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val wsUrl = toWebSocketUrl(session.baseUrl)
            val request = Request.Builder().url(wsUrl).build()
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<JSONObject?>(null)
            val reqId = 1

            val ws = client(session.ignoreSSL).newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (msg.optString("type")) {
                        "auth_required" -> {
                            val auth = JSONObject()
                                .put("type", "auth")
                                .put("access_token", session.token)
                            webSocket.send(auth.toString())
                        }
                        "auth_ok" -> {
                            val cmd = JSONObject()
                                .put("id", reqId)
                                .put("type", type)
                            fillPayload(cmd)
                            webSocket.send(cmd.toString())
                        }
                        "auth_invalid" -> {
                            resultRef.set(null)
                            latch.countDown()
                        }
                        "result" -> {
                            if (msg.optInt("id", -1) == reqId) {
                                resultRef.set(msg)
                                latch.countDown()
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }
            })

            latch.await(12, TimeUnit.SECONDS)
            runCatching { ws.close(1000, "done") }
            resultRef.get()
        }.getOrNull()
    }

    private fun toWebSocketUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}/api/websocket"
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}/api/websocket"
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> "$normalized/api/websocket"
            else -> "ws://$normalized/api/websocket"
        }
    }

    private fun JSONObject.toHaStateOrNull(): HaState? = runCatching {
        HaState(
            entityId = getString("entity_id"),
            state = getString("state"),
            attributes = optJSONObject("attributes") ?: JSONObject()
        )
    }.getOrNull()

    /**
     * Fetch a camera snapshot as raw JPEG bytes.
     * Returns null on 404 (camera doesn't exist) or any error.
     * Results are cached for [CAMERA_TTL_MS] ms.
     */
    suspend fun getCameraImageBytes(
        session: HaSession,
        entityId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        cameraByteCache[entityId]?.let { (ts, bytes) ->
            if (System.currentTimeMillis() - ts < CAMERA_TTL_MS) return@withContext bytes
        }
        runCatching {
            val req = Request.Builder()
                .url("${session.baseUrl.trimEnd('/')}/api/camera_proxy/$entityId")
                .header("Authorization", "Bearer ${session.token}")
                .get().build()
            client(session.ignoreSSL).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()?.also { bytes ->
                    cameraByteCache[entityId] = System.currentTimeMillis() to bytes
                }
            }
        }.getOrNull()
    }

    /** Decode cached/fetched camera bytes to an [android.graphics.Bitmap], returns null on failure. */
    suspend fun getCameraBitmap(session: HaSession, entityId: String) =
        getCameraImageBytes(session, entityId)?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
        }

    /** Invalidate the camera cache for a specific entity (e.g. after manual refresh). */
    fun invalidateCameraCache(entityId: String) {
        cameraByteCache.remove(entityId)
    }

    /** Invalidate all camera caches. */
    fun invalidateAllCameras() {
        cameraByteCache.clear()
    }

    // ── Generic image fetch (e.g. entity_picture / album art) ─────────────────

    /** Cache: url → (fetchedAtMs, bytes) – 30 s TTL */
    private val imageByteCache = HashMap<String, Pair<Long, ByteArray>>()
    private const val IMAGE_TTL_MS = 30_000L

    /**
     * Fetch an image from a HA-proxied URL (relative or absolute).
     * Uses the HA Bearer token for authentication.
     * Results cached for [IMAGE_TTL_MS] ms, keyed by URL.
     */
    suspend fun fetchImageBitmap(session: HaSession, relativeOrAbsoluteUrl: String): android.graphics.Bitmap? =
        fetchImageBytes(session, relativeOrAbsoluteUrl)?.let { bytes ->
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
        }

    private suspend fun fetchImageBytes(session: HaSession, url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            imageByteCache[url]?.let { (ts, bytes) ->
                if (System.currentTimeMillis() - ts < IMAGE_TTL_MS) return@withContext bytes
            }
            runCatching {
                val absUrl = if (url.startsWith("http")) url
                             else "${session.baseUrl.trimEnd('/')}$url"
                val req = Request.Builder()
                    .url(absUrl)
                    .header("Authorization", "Bearer ${session.token}")
                    .get().build()
                client(session.ignoreSSL).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes()?.also { bytes ->
                        imageByteCache[url] = System.currentTimeMillis() to bytes
                    }
                }
            }.getOrNull()
        }

    /** Invalidate the image cache for a URL (e.g. after track change). */
    fun invalidateImageCache(url: String) {
        imageByteCache.remove(url)
    }
}
