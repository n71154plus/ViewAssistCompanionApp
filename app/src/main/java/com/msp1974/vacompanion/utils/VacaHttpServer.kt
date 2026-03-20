package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWord
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class VacaHttpServer(
    private val context: Context,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val config = APPConfig.getInstance(context)

    // MJPEG frame callback - set by CameraBackgroundTask
    var mjpegFrameProvider: (() -> ByteArray?)? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "VacaHttpServer") {
            try {
                serverSocket = ServerSocket(port)
                Timber.d("VACA HTTP server started on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Timber.e("VACA HTTP server error: $e")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        Timber.d("VACA HTTP server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val method = requestLine.substringBefore(" ")
            val path = requestLine.substringAfter(" ").substringBefore(" ")

            when {
                path.startsWith("/icon") -> handleIcon(socket, path)
                path == "/stream" -> handleMjpegStream(socket)
                path == "/snapshot" -> handleSnapshot(socket)
                path == "/status" -> handleStatus(socket)
                path == "/wakeword" && method == "GET" -> handleWakeWordPage(socket)
                path == "/wakeword/list" -> handleWakeWordList(socket)
                path == "/wakeword/upload" && method == "POST" -> handleWakeWordUpload(socket, reader)
                path.startsWith("/wakeword/delete") -> handleWakeWordDelete(socket, path)
                path.startsWith("/wakeword/activate") -> handleWakeWordActivate(socket, path)
                else -> sendError(socket, 404, "Not Found")
            }
        } catch (e: Exception) {
            Timber.e("Error handling request: $e")
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    // ── Icon ────────────────────────────────────────────────────────────────

    private fun handleIcon(socket: Socket, path: String) {
        if (!config.iconServerEnabled) { sendError(socket, 503, "Icon server disabled"); return }
        val pkg = path.substringAfter("pkg=").substringBefore("&").substringBefore(" ").trim()
        if (pkg.isEmpty()) { sendError(socket, 400, "Missing pkg"); return }
        val bytes = getIconBytes(pkg)
        if (bytes == null) { sendError(socket, 404, "Icon not found"); return }
        sendBytes(socket, bytes, "image/png", cache = true)
    }

    private fun getIconBytes(packageName: String): ByteArray? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = drawableToBitmap(drawable)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } catch (e: PackageManager.NameNotFoundException) { null }
        catch (e: Exception) { Timber.e("Icon error for $packageName: $e"); null }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, 192, 192)
        d.draw(canvas)
        return bmp
    }

    // ── MJPEG stream ─────────────────────────────────────────────────────────

    private fun handleMjpegStream(socket: Socket) {
        if (!config.mjpegStreamEnabled) { sendError(socket, 503, "Stream disabled"); return }
        val out = socket.getOutputStream()
        try {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
            out.flush()
            while (running && !socket.isClosed) {
                val frame = mjpegFrameProvider?.invoke()
                if (frame != null) {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                    out.write(frame)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Timber.d("MJPEG client disconnected")
        }
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    private fun handleSnapshot(socket: Socket) {
        if (!config.mjpegStreamEnabled) { sendError(socket, 503, "Stream disabled"); return }
        val frame = mjpegFrameProvider?.invoke()
        if (frame == null) { sendError(socket, 503, "No frame available"); return }
        sendBytes(socket, frame, "image/jpeg")
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun handleStatus(socket: Socket) {
        val json = """
        {
          "app_version": "${config.version}",
          "screen_on": ${config.screenOn},
          "muted": ${config.isMuted},
          "music_volume": ${config.musicVolume},
          "notification_volume": ${config.notificationVolume},
          "dark_mode": ${config.darkMode},
          "do_not_disturb": ${config.doNotDisturb},
          "current_path": "${config.currentPath}",
          "http_server_enabled": ${config.httpServerEnabled},
          "icon_server_enabled": ${config.iconServerEnabled},
          "mjpeg_stream_enabled": ${config.mjpegStreamEnabled},
          "ble_proxy_enabled": ${config.bleProxyEnabled}
        }
        """.trimIndent()
        sendString(socket, json, "application/json")
    }

    // ── Wake word page ────────────────────────────────────────────────────────

    private fun handleWakeWordPage(socket: Socket) {
        val wakeWords = WakeWords(context).getWakeWords()
        val current = config.wakeWord
        val rows = wakeWords.entries.joinToString("") { (key, ww) ->
            val isBuiltIn = ww.builtIn
            val activeBadge = if (key == current) "<span class='badge'>使用中</span>" else ""
            val deleteBtn = if (!isBuiltIn) "<button onclick=\"deleteWW('$key')\">刪除</button>" else ""
            val activateBtn = if (key != current) "<button onclick=\"activateWW('$key')\">啟用</button>" else ""
            "<tr><td>${ww.name}$activeBadge</td><td>${if (isBuiltIn) "內建" else "自訂"}</td><td>$activateBtn $deleteBtn</td></tr>"
        }
        val html = """
<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VACA Wake Word 管理</title>
<style>
  body { font-family: sans-serif; max-width: 700px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.4rem; }
  table { width: 100%; border-collapse: collapse; margin-bottom: 2rem; }
  th, td { padding: 0.6rem 0.8rem; border-bottom: 1px solid #ddd; text-align: left; }
  th { background: #f5f5f5; }
  .badge { background: #4caf50; color: #fff; font-size: 11px; padding: 2px 6px; border-radius: 4px; margin-left: 6px; }
  button { padding: 4px 10px; margin: 2px; cursor: pointer; border-radius: 4px; border: 1px solid #ccc; }
  .upload-area { border: 2px dashed #aaa; padding: 2rem; text-align: center; border-radius: 8px; margin-bottom: 1rem; }
  #status { margin-top: 1rem; font-weight: bold; }
</style>
</head>
<body>
<h1>VACA Wake Word 管理</h1>
<table>
  <thead><tr><th>名稱</th><th>類型</th><th>操作</th></tr></thead>
  <tbody>$rows</tbody>
</table>
<h2>上傳自訂 Wake Word</h2>
<div class="upload-area" id="drop">
  <p>拖曳 .onnx 檔案到此處，或</p>
  <input type="file" id="fileInput" accept=".onnx">
</div>
<div id="status"></div>
<script>
function setStatus(msg, ok) {
  const el = document.getElementById('status');
  el.textContent = msg;
  el.style.color = ok ? 'green' : 'red';
}
document.getElementById('fileInput').addEventListener('change', function() {
  if (this.files.length > 0) uploadFile(this.files[0]);
});
const drop = document.getElementById('drop');
drop.addEventListener('dragover', e => { e.preventDefault(); drop.style.borderColor = '#333'; });
drop.addEventListener('dragleave', () => { drop.style.borderColor = '#aaa'; });
drop.addEventListener('drop', e => {
  e.preventDefault(); drop.style.borderColor = '#aaa';
  if (e.dataTransfer.files.length > 0) uploadFile(e.dataTransfer.files[0]);
});
function uploadFile(file) {
  if (!file.name.endsWith('.onnx')) { setStatus('只支援 .onnx 檔案', false); return; }
  setStatus('上傳中...', true);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/wakeword/upload');
  xhr.setRequestHeader('X-Filename', file.name);
  xhr.onload = () => {
    if (xhr.status === 200) { setStatus('上傳成功！重新整理中...', true); setTimeout(() => location.reload(), 1000); }
    else { setStatus('上傳失敗: ' + xhr.responseText, false); }
  };
  xhr.send(file);
}
function deleteWW(key) {
  if (!confirm('確定刪除 ' + key + '？')) return;
  fetch('/wakeword/delete?name=' + key, { method: 'DELETE' })
    .then(r => r.ok ? location.reload() : alert('刪除失敗'));
}
function activateWW(key) {
  fetch('/wakeword/activate?name=' + key, { method: 'POST' })
    .then(r => r.ok ? location.reload() : alert('啟用失敗'));
}
</script>
</body>
</html>""".trimIndent()
        sendString(socket, html, "text/html; charset=utf-8")
    }

    // ── Wake word list (JSON) ─────────────────────────────────────────────────

    private fun handleWakeWordList(socket: Socket) {
        val wakeWords = WakeWords(context).getWakeWords()
        val current = config.wakeWord
        val items = wakeWords.entries.joinToString(",") { (key, ww) ->
            """{"key":"$key","name":"${ww.name}","active":${key == current},"builtin":${ww.builtIn}}"""
        }
        sendString(socket, """{"wake_words":[$items]}""", "application/json")
    }

    // ── Wake word upload ──────────────────────────────────────────────────────

    private fun handleWakeWordUpload(socket: Socket, reader: java.io.BufferedReader) {
        try {
            // Read headers to find filename and content-length
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = reader.readLine()
            }
            val filename = headers["x-filename"] ?: "custom.onnx"
            val length = headers["content-length"]?.toIntOrNull() ?: run {
                sendError(socket, 400, "Missing Content-Length"); return
            }
            if (!filename.endsWith(".onnx")) { sendError(socket, 400, "Only .onnx supported"); return }

            val dir = File(context.filesDir, "vaca").also { it.mkdirs() }
            val file = File(dir, filename.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' })
            val inputStream: InputStream = socket.getInputStream()
            file.outputStream().use { out ->
                val buf = ByteArray(8192)
                var remaining = length
                while (remaining > 0) {
                    val read = inputStream.read(buf, 0, minOf(buf.size, remaining))
                    if (read < 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
            sendString(socket, """{"status":"ok","file":"${file.name}"}""", "application/json")
        } catch (e: Exception) {
            Timber.e("Upload error: $e")
            sendError(socket, 500, "Upload failed")
        }
    }

    // ── Wake word delete ──────────────────────────────────────────────────────

    private fun handleWakeWordDelete(socket: Socket, path: String) {
        val name = path.substringAfter("name=").substringBefore("&").trim()
        val dir = File(context.filesDir, "vaca")
        val file = File(dir, "$name.onnx")
        return if (file.exists() && file.delete()) {
            sendString(socket, """{"status":"ok"}""", "application/json")
        } else {
            sendError(socket, 404, "File not found")
        }
    }

    // ── Wake word activate ────────────────────────────────────────────────────

    private fun handleWakeWordActivate(socket: Socket, path: String) {
        val name = path.substringAfter("name=").substringBefore("&").trim()
        config.wakeWord = name
        sendString(socket, """{"status":"ok","active":"$name"}""", "application/json")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendBytes(socket: Socket, data: ByteArray, contentType: String, cache: Boolean = false) {
        val out = DataOutputStream(socket.getOutputStream())
        out.writeBytes("HTTP/1.1 200 OK\r\n")
        out.writeBytes("Content-Type: $contentType\r\n")
        out.writeBytes("Content-Length: ${data.size}\r\n")
        out.writeBytes("Access-Control-Allow-Origin: *\r\n")
        if (cache) out.writeBytes("Cache-Control: max-age=86400\r\n")
        out.writeBytes("\r\n")
        out.write(data)
        out.flush()
    }

    private fun sendString(socket: Socket, body: String, contentType: String) {
        sendBytes(socket, body.toByteArray(Charsets.UTF_8), contentType)
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes("HTTP/1.1 $code $message\r\nContent-Length: ${message.length}\r\n\r\n$message")
            out.flush()
        } catch (e: Exception) {}
    }
}
