package com.radar.app.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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

private val ColorSweep = Color(0xFF00FF41)
private val ColorGrid = Color(0xFF1A3A1A)
private val ColorCenter = Color(0xFF00FF41)
private val ColorDevicePhone = Color(0xFF00BFFF)
private val ColorDeviceAP = Color(0xFFFFD700)
private val ColorDeviceAccessory = Color(0xFFFF69B4)
private val ColorDeviceIot = Color(0xFFFF8C00)
private val ColorDeviceUnknown = Color(0xFFAAAAAA)

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

    // Store last known device positions for tap detection
    var devicePositions by remember { mutableStateOf(mapOf<String, Offset>()) }

    Canvas(
        modifier = modifier
            .pointerInput(devices) {
                detectTapGestures { tapOffset ->
                    val tapped = devicePositions.entries
                        .firstOrNull { (_, pos) ->
                            val dist = sqrt(
                                (tapOffset.x - pos.x).pow(2) +
                                (tapOffset.y - pos.y).pow(2)
                            )
                            dist < 40f
                        }
                    if (tapped != null) {
                        onDeviceTapped(tapped.key)
                    } else {
                        onBackgroundTapped()
                    }
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = (minOf(size.width, size.height) / 2f) * zoomLevel.coerceIn(0.3f, 2f)

        // Background
        drawCircle(color = Color(0xFF050F05), radius = maxRadius, center = Offset(cx, cy))

        // Grid rings
        val rings = 4
        for (i in 1..rings) {
            val r = maxRadius * i / rings
            drawCircle(
                color = ColorGrid,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1f)
            )
        }

        // Grid crosshairs
        drawLine(ColorGrid, Offset(cx - maxRadius, cy), Offset(cx + maxRadius, cy), 1f)
        drawLine(ColorGrid, Offset(cx, cy - maxRadius), Offset(cx, cy + maxRadius), 1f)

        // Sweep line with fading trail
        rotate(sweepAngle, Offset(cx, cy)) {
            // Trail
            for (i in 1..30) {
                val trailAlpha = (30 - i) / 30f * 0.3f
                rotate(-i * 3f, Offset(cx, cy)) {
                    drawLine(
                        color = ColorSweep.copy(alpha = trailAlpha),
                        start = Offset(cx, cy),
                        end = Offset(cx, cy - maxRadius),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round
                    )
                }
            }
            // Sweep arm
            drawLine(
                color = ColorSweep.copy(alpha = 0.9f),
                start = Offset(cx, cy),
                end = Offset(cx, cy - maxRadius),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }

        // Center dot
        drawCircle(color = ColorCenter, radius = 6f, center = Offset(cx, cy))
        drawCircle(
            color = ColorCenter.copy(alpha = 0.3f),
            radius = 14f,
            center = Offset(cx, cy),
            style = Stroke(2f)
        )

        // Devices
        val positions = mutableMapOf<String, Offset>()
        devices.forEach { device ->
            val pos = drawDevice(
                device = device,
                cx = cx, cy = cy,
                maxRadius = maxRadius,
                isSelected = device.stableId == selectedDeviceId,
                textMeasurer = textMeasurer
            )
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

    val deviceColor = when (device.info.inferredClass) {
        DeviceClass.PHONE -> ColorDevicePhone
        DeviceClass.AP -> ColorDeviceAP
        DeviceClass.ACCESSORY -> ColorDeviceAccessory
        DeviceClass.IOT -> ColorDeviceIot
        DeviceClass.UNKNOWN -> ColorDeviceUnknown
    }

    val alpha = device.overallConfidence.coerceIn(0.3f, 1f)

    // Bearing cone (only when bearing is known and confidence > 0.3)
    if (device.bearingDeg != null && device.bearingConfidence > 0.3f) {
        val coneAngle = 45f * (1f - device.bearingConfidence) + 15f
        // Draw as arc
        drawArc(
            color = deviceColor.copy(alpha = alpha * 0.15f),
            startAngle = bearing - coneAngle / 2f - 90f,
            sweepAngle = coneAngle,
            useCenter = true,
            topLeft = Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        )
    }

    // Outer glow
    if (isSelected) {
        drawCircle(
            color = deviceColor.copy(alpha = 0.3f),
            radius = 22f,
            center = center
        )
    }

    // Device blip
    drawCircle(
        color = deviceColor.copy(alpha = alpha),
        radius = if (isSelected) 12f else 8f,
        center = center
    )

    // Device name label
    val name = device.info.displayName ?: device.stableId.take(6)
    val measured = textMeasurer.measure(
        text = name,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = deviceColor)
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(x + 14f, y - measured.size.height / 2f)
    )

    return center
}
