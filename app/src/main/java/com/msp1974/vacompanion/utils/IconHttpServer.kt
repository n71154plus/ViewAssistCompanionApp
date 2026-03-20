package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class IconHttpServer(private val context: Context, private val port: Int = 8080) {

    private var serverSocket: ServerSocket? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "IconHttpServer") {
            try {
                serverSocket = ServerSocket(port)
                Timber.d("Icon HTTP server started on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Timber.e("Icon HTTP server error: $e")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        Timber.d("Icon HTTP server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            // GET /icon?pkg=com.xxx HTTP/1.1
            val packageName = requestLine
                .substringAfter("pkg=")
                .substringBefore(" ")
                .substringBefore("&")
                .trim()

            if (packageName.isEmpty()) {
                sendError(socket, 400, "Missing pkg parameter")
                return
            }

            val iconBytes = getAppIconBytes(packageName)
            if (iconBytes == null) {
                sendError(socket, 404, "Icon not found")
                return
            }

            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes("HTTP/1.1 200 OK\r\n")
            out.writeBytes("Content-Type: image/png\r\n")
            out.writeBytes("Content-Length: ${iconBytes.size}\r\n")
            out.writeBytes("Access-Control-Allow-Origin: *\r\n")
            out.writeBytes("Cache-Control: max-age=86400\r\n")
            out.writeBytes("\r\n")
            out.write(iconBytes)
            out.flush()
        } catch (e: Exception) {
            Timber.e("Error handling icon request: $e")
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun getAppIconBytes(packageName: String): ByteArray? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            Timber.e("Error getting icon for $packageName: $e")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes("HTTP/1.1 $code $message\r\n\r\n")
            out.flush()
        } catch (e: Exception) {}
    }
}