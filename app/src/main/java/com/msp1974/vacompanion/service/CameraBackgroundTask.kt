package com.msp1974.vacompanion.service

import android.Manifest
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection
import com.jjoe64.motiondetection.motiondetection.ImageProcessing
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils.Companion.log
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

class CameraBackgroundTask(val context: Context) {

    private val config = APPConfig.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Default)

    private val detector = AggregateLumaMotionDetection()
    private var lastCheck: Long = 0
    private var lastDetection: Long = 0
    private val settleDelay: Long = 5000
    private var settleDelayJob: Job? = null

    // Camera2
    private var cameraManager: CameraManager? = null
    private var previewSize: Size? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    @Volatile var isRunning: Boolean = false
    private val restartPending = AtomicBoolean(false)

    private var sensorOrientation: Int = 0

    // MJPEG output — written on cameraHandler thread, read by HTTP server
    @Volatile var latestJpegFrame: ByteArray? = null

    // Pre-allocated encode buffers (reused across frames — same dimensions for session lifetime)
    private var yDataBuf: ByteArray? = null
    private var nv21Buf: ByteArray? = null
    private var vRowBuf: ByteArray? = null
    private val jpegOutput = ByteArrayOutputStream(32768)

    // AE/AWB lock scheduling
    private var aeLockScheduled = false

    companion object {
        const val MOTION_INTERVAL = 10000
        const val MAX_LENIENCY = 50
    }

    init {
        setSensitivity(config.motionDetectionSensitivity)
    }

    fun setSensitivity(sensitivity: Int) {
        detector.setLeniency(min(MAX_LENIENCY, max(0, MAX_LENIENCY - sensitivity)))
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startCamera() {
        if (isRunning) return
        isRunning = true
        startCameraThread()
        scope.launch { initCam() }
    }

    fun stopCamera() {
        if (!isRunning) return
        isRunning = false
        closeCamera()
        stopCameraThread()
        latestJpegFrame = null
        yDataBuf = null
        nv21Buf = null
        vRowBuf = null
        jpegOutput.reset()
    }

    fun restartWithNewFps() {
        if (!isRunning) return
        scope.launch {
            Timber.d("Restarting camera for new FPS: ${config.mjpegFps}")
            closeSession()
            delay(200)
            createCaptureSession()
        }
    }

    // ── Camera thread ─────────────────────────────────────────────────────────

    private fun startCameraThread() {
        cameraThread = HandlerThread("CameraBackground").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (e: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    // ── Camera lifecycle ──────────────────────────────────────────────────────

    private fun closeSession() {
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        aeLockScheduled = false
    }

    private fun closeCamera() {
        closeSession()
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (e: Exception) {}
        imageReader = null
    }

    private fun scheduleRestart(delayMs: Long = 2000) {
        if (!isRunning || restartPending.getAndSet(true)) return
        scope.launch {
            delay(delayMs)
            restartPending.set(false)
            if (isRunning) {
                closeCamera()
                delay(500)
                initCam()
            }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            Timber.d("Camera opened")
            cameraDevice = cam
            createCaptureSession()
        }

        override fun onDisconnected(cam: CameraDevice) {
            Timber.w("Camera disconnected")
            cam.close()
            cameraDevice = null
            scheduleRestart(1000)
        }

        override fun onError(cam: CameraDevice, error: Int) {
            Timber.e("Camera error: $error")
            cam.close()
            cameraDevice = null
            if (error != 3) scheduleRestart(2000) // skip if CAMERA_DISABLED
        }
    }

    private fun initCam() {
        if (!isRunning) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("Camera permission not granted")
            return
        }

        try {
            val mgr = context.getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager = mgr

            val camId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: run {
                Timber.w("No front camera found")
                return
            }

            val chars = mgr.getCameraCharacteristics(camId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            previewSize = chooseSupportedSize(mgr, camId, 320, 240)
            Timber.d("Camera id=$camId size=$previewSize sensorOrientation=$sensorOrientation")

            mgr.openCamera(camId, stateCallback, cameraHandler)

            // Settle delay for motion detection
            settleDelayJob?.cancel()
            settleDelayJob = scope.launch { delay(settleDelay) }

        } catch (e: Exception) {
            Timber.e("initCam error: $e")
            scheduleRestart()
        }
    }

    // ── Capture session ───────────────────────────────────────────────────────

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (cameraDevice == null) { Timber.w("Camera closed before session configured"); return }
            captureSession = session
            try {
                val fps = if (config.mjpegStreamEnabled) config.mjpegFps.coerceIn(1, 30) else 5
                val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader!!.surface)
                    // AF OFF: front camera is fixed-focus; continuous AF sweeps cause 1-2s frame stalls
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    // Lock FPS range: prevents AE from slowing down to 1fps in low light
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                }.build()
                session.setRepeatingRequest(req, captureCallback, cameraHandler)
                Timber.d("Capture session started at ${fps}fps")
                // Lock AE + AWB after 3s — prevents periodic 3A recalibration stalls
                scheduleAeLock()
            } catch (e: CameraAccessException) {
                Timber.e("setRepeatingRequest failed: $e")
                scheduleRestart()
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Timber.e("Capture session configure failed")
            scheduleRestart()
        }

        override fun onClosed(session: CameraCaptureSession) {
            Timber.d("Capture session closed")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {}
        override fun onCaptureProgressed(s: CameraCaptureSession, r: CaptureRequest, result: CaptureResult) {}
    }

    // ── AE/AWB lock ───────────────────────────────────────────────────────────

    private fun scheduleAeLock() {
        if (aeLockScheduled) return
        aeLockScheduled = true
        // Post on the camera thread after 3 s so AE/AWB have time to converge first
        cameraHandler?.postDelayed({
            val device = cameraDevice ?: return@postDelayed
            val reader = imageReader ?: return@postDelayed
            val session = captureSession ?: return@postDelayed
            if (!isRunning) return@postDelayed
            val fps = if (config.mjpegStreamEnabled) config.mjpegFps.coerceIn(1, 30) else 5
            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }.build()
                session.setRepeatingRequest(req, captureCallback, cameraHandler)
                Timber.d("AE + AWB locked at ${fps}fps")
            } catch (e: Exception) {
                Timber.w("AE/AWB lock failed (device may not support): $e")
            }
        }, 3000L)
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val size = previewSize ?: return

        // Close previous imageReader before creating new one
        try { imageReader?.close() } catch (e: Exception) {}

        val fps = if (config.mjpegStreamEnabled) config.mjpegFps.coerceIn(1, 30) else 5
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader.setOnImageAvailableListener(imageListener, cameraHandler)
        imageReader = reader

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // API 30+: use SessionConfiguration (non-deprecated)
                val outputConfig = android.hardware.camera2.params.OutputConfiguration(reader.surface)
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    sessionCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(reader.surface), sessionCallback, cameraHandler)
            }
        } catch (e: Exception) {
            Timber.e("createCaptureSession failed: $e")
            scheduleRestart()
        }
    }

    // ── Image listener (runs on cameraHandler thread) ─────────────────────────

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader?.acquireLatestImage() ?: return@OnImageAvailableListener

        try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // Copy Y plane (strip row padding) into reusable buffer
            val yData = yDataBuf?.takeIf { it.size == width * height }
                ?: ByteArray(width * height).also { yDataBuf = it }
            val yBuf = yPlane.buffer
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.get(yData, row * width, width)
            }

            // Motion detection (lightweight - Y plane only)
            val now = System.currentTimeMillis()
            val cameraFps = if (config.mjpegStreamEnabled) config.mjpegFps.coerceIn(1, 30) else 5
            val detectionInterval = (2000L / cameraFps).coerceAtMost(500L)
            if (now - lastCheck > detectionInterval) {
                lastCheck = now
                if (settleDelayJob?.isActive == false || settleDelayJob == null) {
                    val luma = ImageProcessing.decodeYUV420SPtoLuma(yData, width, height)
                    if (detector.detect(luma, width, height)) {
                        if (now - lastDetection > MOTION_INTERVAL) {
                            log.d("Motion detected")
                            config.eventBroadcaster.notifyEvent(Event("motion", "", ""))
                            lastDetection = now
                        }
                    }
                }
            }

            // MJPEG: encode JPEG inline on cameraHandler thread using reusable buffers
            if (config.mjpegStreamEnabled) {
                // Reuse NV21 buffer (same size for the entire session)
                val nv21 = nv21Buf?.takeIf { it.size == width * height * 3 / 2 }
                    ?: ByteArray(width * height * 3 / 2).also { nv21Buf = it }
                System.arraycopy(yData, 0, nv21, 0, yData.size)
                val uBuf = uPlane.buffer
                val vBuf = vPlane.buffer
                var offset = width * height
                if (uvPixelStride == 2) {
                    // Fast path: V/U already interleaved in memory (most modern devices)
                    val rowBytes = width - 1
                    val vRow = vRowBuf?.takeIf { it.size == rowBytes }
                        ?: ByteArray(rowBytes).also { vRowBuf = it }
                    for (row in 0 until height / 2) {
                        vBuf.position(row * uvRowStride)
                        vBuf.get(vRow, 0, rowBytes)
                        System.arraycopy(vRow, 0, nv21, offset, rowBytes)
                        offset += rowBytes
                        uBuf.position(row * uvRowStride + (width / 2 - 1) * uvPixelStride)
                        nv21[offset++] = uBuf.get()
                    }
                } else {
                    // Slow path: planar U/V (uvPixelStride == 1)
                    val uRow = ByteArray(width / 2)
                    val vRow = ByteArray(width / 2)
                    for (row in 0 until height / 2) {
                        vBuf.position(row * uvRowStride); vBuf.get(vRow)
                        uBuf.position(row * uvRowStride); uBuf.get(uRow)
                        for (col in 0 until width / 2) {
                            nv21[offset++] = vRow[col]
                            nv21[offset++] = uRow[col]
                        }
                    }
                }
                // Compress to JPEG using reusable output stream — only allocates the final byte[]
                jpegOutput.reset()
                YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height),
                        config.mjpegQuality.coerceIn(10, 100), jpegOutput)
                latestJpegFrame = jpegOutput.toByteArray()
            }

        } catch (e: Exception) {
            Timber.e("imageListener error: $e")
        } finally {
            image.close()
        }
    }

    // ── Size selection ────────────────────────────────────────────────────────

    private fun chooseSupportedSize(mgr: CameraManager, camId: String, w: Int, h: Int): Size {
        val chars = mgr.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(w, h)
        val targetArea = w * h
        val targetAspect = w.toFloat() / h
        return sizes.minByOrNull { sz ->
            val aspect = sz.width.toFloat() / sz.height
            (aspect - targetAspect).absoluteValue * 10000 + (sz.width * sz.height - targetArea).absoluteValue / 100
        } ?: Size(w, h)
    }

}
