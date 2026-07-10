package com.radar.app.ui.radar

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.asin

/**
 * 3D sphere view: devices arranged in a star topology around the center.
 * Uses simple isometric projection to suggest depth — devices above "floor" 
 * (stronger signal) are higher up the sphere.
 */
@Composable
fun RadarCanvas3D(
    devices: List<TrackedDevice>,
    zoomLevel: Float,
    selectedDeviceId: String?,
    onDeviceTapped: (String) -> Unit,
    onBackgroundTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var devicePositions by remember { mutableStateOf(mapOf<String, Offset>()) }

    Canvas(
        modifier = modifier
            .pointerInput(devices) {
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
        val sphereR = (minOf(size.width, size.height) / 2f - 24f) * zoomLevel.coerceIn(0.3f, 2f)

        // Sphere outline
        drawCircle(
            color = Color(0xFF0A200A),
            radius = sphereR,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color(0xFF1A3A1A),
            radius = sphereR,
            center = Offset(cx, cy),
            style = Stroke(1.5f)
        )

        // Latitude rings (3 rings to suggest sphere shape)
        for (lat in listOf(0.33f, 0.66f)) {
            val latR = sphereR * lat
            val flatR = sphereR * sqrt(1f - lat * lat)
            drawEllipse(
                cx = cx, cy = cy,
                rx = flatR, ry = flatR * 0.35f, // flatten to suggest tilt
                color = Color(0xFF1A3A1A)
            )
        }

        // Equator
        drawEllipse(cx, cy, sphereR, sphereR * 0.35f, Color(0xFF224422))

        // Center crosshair (you)
        drawCircle(Color(0xFF00FF41), 8f, Offset(cx, cy))
        drawLine(Color(0xFF00FF41), Offset(cx - 16f, cy), Offset(cx + 16f, cy), 1.5f)
        drawLine(Color(0xFF00FF41), Offset(cx, cy - 16f), Offset(cx, cy + 16f), 1.5f)

        // Devices: use bearing for azimuth, proximityScore for elevation
        // High proximity = device is close = low elevation (near equator)
        // Low proximity = device is far = high elevation (near poles, "above" or "below")
        val positions = mutableMapOf<String, Offset>()

        // Sort by depth (far devices drawn first, close devices on top)
        val sorted = devices.sortedBy { it.proximityScore }

        sorted.forEachIndexed { _, device ->
            val bearing = device.bearingDeg ?: (devices.indexOf(device) * (360f / devices.size.coerceAtLeast(1)))
            val bearingRad = bearing * PI.toFloat() / 180f

            // elevation: 0 = equator, 1 = top pole
            // Far devices spread to upper half-sphere, close ones near equator
            val elevation = (1f - device.proximityScore).coerceIn(0f, 0.9f)
            val elevationRad = elevation * PI.toFloat() / 2f

            // 3D sphere coords
            val sx = sphereR * cos(elevationRad) * sin(bearingRad)
            val sy_3d = sphereR * cos(elevationRad) * cos(bearingRad)
            val sz = sphereR * sin(elevationRad)

            // Isometric projection: flatten Y to suggest depth
            val projX = cx + sx
            val projY = cy - sz - sy_3d * 0.3f // tilt perspective

            val pos = Offset(projX, projY)

            val deviceColor = when (device.info.inferredClass) {
                DeviceClass.PHONE -> ColorDevicePhone
                DeviceClass.AP -> ColorDeviceAP
                DeviceClass.ACCESSORY -> ColorDeviceAccessory
                DeviceClass.IOT -> ColorDeviceIot
                DeviceClass.UNKNOWN -> ColorDeviceUnknown
            }

            val alpha = device.overallConfidence.coerceIn(0.3f, 1f)

            // Line from center to device (star topology)
            drawLine(
                color = deviceColor.copy(alpha = alpha * 0.3f),
                start = Offset(cx, cy),
                end = pos,
                strokeWidth = 1f,
                cap = StrokeCap.Round
            )

            // Device blip
            val isSelected = device.stableId == selectedDeviceId
            if (isSelected) {
                drawCircle(deviceColor.copy(alpha = 0.25f), 20f, pos)
            }
            drawCircle(deviceColor.copy(alpha = alpha), if (isSelected) 12f else 8f, pos)

            // Label
            val name = device.info.displayName ?: device.stableId.take(6)
            val measured = textMeasurer.measure(
                name,
                TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = deviceColor)
            )
            drawText(measured, Offset(projX + 14f, projY - measured.size.height / 2f))

            positions[device.stableId] = pos
        }

        devicePositions = positions
    }
}

// Draw an ellipse using a Path (Canvas doesn't have drawEllipse directly)
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEllipse(
    cx: Float, cy: Float, rx: Float, ry: Float, color: Color
) {
    val path = Path().apply {
        moveTo(cx + rx, cy)
        cubicTo(cx + rx, cy - ry * 0.55f, cx + rx * 0.55f, cy - ry, cx, cy - ry)
        cubicTo(cx - rx * 0.55f, cy - ry, cx - rx, cy - ry * 0.55f, cx - rx, cy)
        cubicTo(cx - rx, cy + ry * 0.55f, cx - rx * 0.55f, cy + ry, cx, cy + ry)
        cubicTo(cx + rx * 0.55f, cy + ry, cx + rx, cy + ry * 0.55f, cx + rx, cy)
        close()
    }
    drawPath(path, color, style = Stroke(1f))
}
