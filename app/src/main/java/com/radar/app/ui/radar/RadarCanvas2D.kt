package com.radar.app.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.radar.app.data.model.DeviceClass
import com.radar.app.data.model.TrackedDevice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun RadarCanvas2D(
    devices: List<TrackedDevice>,
    zoomLevel: Float,
    selectedDeviceId: String?,
    onDeviceTapped: (String) -> Unit,
    onBackgroundTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    val textMeasurer = rememberTextMeasurer()
    var devicePositions by remember { mutableStateOf(mapOf<String, Offset>()) }

    Canvas(
        modifier = modifier.pointerInput(devices) {
            detectTapGestures { tapOffset ->
                val tapped = devicePositions.entries.firstOrNull { (_, pos) ->
                    sqrt((tapOffset.x - pos.x).pow(2) + (tapOffset.y - pos.y).pow(2)) < 40f
                }
                if (tapped != null) onDeviceTapped(tapped.key)
                else onBackgroundTapped()
            }
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = (minOf(size.width, size.height) / 2f) * zoomLevel.coerceIn(0.3f, 2f)

        drawCircle(color = Color(0xFF050F05), radius = maxRadius, center = Offset(cx, cy))

        for (i in 1..4) {
            drawCircle(
                color = ColorGrid, radius = maxRadius * i / 4f,
                center = Offset(cx, cy), style = Stroke(width = 1f)
            )
        }
        drawLine(ColorGrid, Offset(cx - maxRadius, cy), Offset(cx + maxRadius, cy), 1f)
        drawLine(ColorGrid, Offset(cx, cy - maxRadius), Offset(cx, cy + maxRadius), 1f)

        rotate(sweepAngle, Offset(cx, cy)) {
            for (i in 1..30) {
                rotate(-i * 3f, Offset(cx, cy)) {
                    drawLine(
                        color = ColorSweep.copy(alpha = (30 - i) / 30f * 0.3f),
                        start = Offset(cx, cy), end = Offset(cx, cy - maxRadius),
                        strokeWidth = 2f, cap = StrokeCap.Round
                    )
                }
            }
            drawLine(
                color = ColorSweep.copy(alpha = 0.9f),
                start = Offset(cx, cy), end = Offset(cx, cy - maxRadius),
                strokeWidth = 2f, cap = StrokeCap.Round
            )
        }

        drawCircle(color = ColorCenter, radius = 6f, center = Offset(cx, cy))
        drawCircle(
            color = ColorCenter.copy(alpha = 0.3f), radius = 14f,
            center = Offset(cx, cy), style = Stroke(2f)
        )

        val positions = mutableMapOf<String, Offset>()
        devices.forEach { device ->
            val pos = drawDevice(device, cx, cy, maxRadius,
                device.stableId == selectedDeviceId, textMeasurer)
            positions[device.stableId] = pos
        }
        devicePositions = positions
    }
}

private fun DrawScope.drawDevice(
    device: TrackedDevice,
    cx: Float, cy: Float,
    maxRadius: Float,
    isSelected: Boolean,
    textMeasurer: TextMeasurer
): Offset {
    val bearing = device.bearingDeg ?: 0f
    val bearingRad = (bearing - 90f) * PI.toFloat() / 180f
    val r = device.radarRadius * maxRadius
    val x = cx + r * cos(bearingRad)
    val y = cy + r * sin(bearingRad)
    val center = Offset(x, y)

    val deviceColor = deviceColor(device.info.inferredClass)
    val alpha = device.overallConfidence.coerceIn(0.3f, 1f)

    if (device.bearingDeg != null && device.bearingConfidence > 0.3f) {
        val coneAngle = 45f * (1f - device.bearingConfidence) + 15f
        drawArc(
            color = deviceColor.copy(alpha = alpha * 0.15f),
            startAngle = bearing - coneAngle / 2f - 90f,
            sweepAngle = coneAngle,
            useCenter = true,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        )
    }

    if (isSelected) drawCircle(color = deviceColor.copy(alpha = 0.3f), radius = 22f, center = center)
    drawCircle(color = deviceColor.copy(alpha = alpha), radius = if (isSelected) 12f else 8f, center = center)

    val name = device.info.displayName ?: device.stableId.take(6)
    drawText(
        textMeasurer = textMeasurer,
        text = name,
        topLeft = Offset(x + 14f, y - 8f),
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = deviceColor)
    )

    return center
}

internal fun deviceColor(cls: DeviceClass) = when (cls) {
    DeviceClass.PHONE -> ColorDevicePhone
    DeviceClass.AP -> ColorDeviceAP
    DeviceClass.ACCESSORY -> ColorDeviceAccessory
    DeviceClass.IOT -> ColorDeviceIot
    DeviceClass.UNKNOWN -> ColorDeviceUnknown
}
