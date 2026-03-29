package com.msp1974.vacompanion.ui.layouts

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.launcher.LauncherActivity
import com.msp1974.vacompanion.settings.APPConfig
import kotlin.math.roundToInt

private val ToggleOnColor  = Color(0xFF4CAF50)
private val ToggleOffColor = Color(0xFF3A3A5C)
private val NavButtonColor = Color(0xFF3A3A5C)

@Composable
fun FloatingLauncherBar(config: APPConfig) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Position (persisted as fractions of screen size)
    var offsetX by remember {
        mutableFloatStateOf(
            config.floatingBarPositionX * context.resources.displayMetrics.widthPixels.toFloat()
        )
    }
    var offsetY by remember {
        mutableFloatStateOf(
            config.floatingBarPositionY * context.resources.displayMetrics.heightPixels.toFloat()
        )
    }

    // Toggle states — initialised from config, written back on click
    var bleEnabled    by remember { mutableStateOf(config.bleProxyEnabled) }
    var cameraEnabled by remember { mutableStateOf(config.mjpegStreamEnabled) }
    var voiceEnabled  by remember { mutableStateOf(!config.isMuted) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .shadow(8.dp, RoundedCornerShape(24.dp))
                .background(Color(0xCC1E1E2E), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val screenW = context.resources.displayMetrics.widthPixels.toFloat()
                            val screenH = context.resources.displayMetrics.heightPixels.toFloat()
                            config.floatingBarPositionX = (offsetX / screenW).coerceIn(0f, 1f)
                            config.floatingBarPositionY = (offsetY / screenH).coerceIn(0f, 1f)
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            // --- Navigation buttons ---
            BarButton(text = "Apps", containerColor = NavButtonColor) {
                openLauncherActivity(context)
            }
            BarButton(text = "設定", containerColor = NavButtonColor) {
                openSystemSettings(context)
            }

            // --- Toggle buttons ---
            BarIconToggle(
                enabled = bleEnabled,
                iconOn = Icons.Filled.Bluetooth,
                iconOff = Icons.Filled.BluetoothDisabled,
                contentDescription = "BLE Proxy"
            ) {
                bleEnabled = !bleEnabled
                config.bleProxyEnabled = bleEnabled
            }

            BarIconToggle(
                enabled = cameraEnabled,
                iconOn = Icons.Filled.Videocam,
                iconOff = Icons.Filled.VideocamOff,
                contentDescription = "Camera"
            ) {
                cameraEnabled = !cameraEnabled
                config.mjpegStreamEnabled = cameraEnabled
            }

            BarIconToggle(
                enabled = voiceEnabled,
                iconOn = Icons.Filled.Mic,
                iconOff = Icons.Filled.MicOff,
                contentDescription = "Voice"
            ) {
                voiceEnabled = !voiceEnabled
                config.isMuted = !voiceEnabled
            }
        }
    }
}

private fun openSystemSettings(context: Context) {
    val intent = Intent(Settings.ACTION_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openLauncherActivity(context: Context) {
    val intent = Intent(context, LauncherActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    context.startActivity(intent)
}

@Composable
private fun BarButton(text: String, containerColor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White
        )
    ) {
        Text(text = text, fontSize = 13.sp)
    }
}

@Composable
private fun BarIconToggle(
    enabled: Boolean,
    iconOn: ImageVector,
    iconOff: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) ToggleOnColor else ToggleOffColor,
            contentColor = Color.White
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = if (enabled) iconOn else iconOff,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}
