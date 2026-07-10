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
import com.radar.app.data.model.TrackedDevice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
        val sphereR = (minOf(size.width, size.height) / 2f - 24f) * zoomLevel.coerceIn(0.3f, 2f)

        drawCircle(color = Color(0xFF0A200A), radius = sphereR, center = Offset(cx, cy))
        drawCircle(
            color = Color(0xFF1A3A1A), radius = sphereR,
            center = Offset(cx, cy), style = Stroke(1.5f)
        )

        // Latitude rings
        listOf(0.33f, 0.66f).forEach { lat ->
            val flatR = sphereR * sqrt(1f - lat * lat)
            drawEllipsePath(cx, cy, flatR, flatR * 0.35f, Color(0xFF1A3A1A))
        }
        drawEllipsePath(cx, cy, sphereR, sphereR * 0.35f, Color(0xFF224422))

        // Center crosshair
        drawCircle(Color(0xFF00FF41), 8f, Offset(cx, cy))
        drawLine(Color(0xFF00FF41), Offset(cx - 16f, cy), Offset(cx + 16f, cy), 1.5f)
        drawLine(Color(0xFF00FF41), Offset(cx, cy - 16f), Offset(cx, cy + 16f), 1.5f)

        val positions = mutableMapOf<String, Offset>()
        val sorted = devices.sortedBy { it.proximityScore }

        sorted.forEach { device ->
            val bearing = device.bearingDeg
                ?: (devices.indexOf(device) * (360f / devices.size.coerceAtLeast(1)))
            val bearingRad = bearing * PI.toFloat() / 180f
            val elevation = (1f - device.proximityScore).coerceIn(0f, 0.9f)
            val elevationRad = elevation * PI.toFloat() / 2f

            val sx = sphereR * cos(elevationRad) * sin(bearingRad)
            val sy3d = sphereR * cos(elevationRad) * cos(bearingRad)
            val sz = sphereR * sin(elevationRad)

            val projX = cx + sx
            val projY = cy - sz - sy3d * 0.3f
            val pos = Offset(projX, projY)

            val deviceColor = deviceColor(device.info.inferredClass)
            val alpha = device.overallConfidence.coerceIn(0.3f, 1f)
            val isSelected = device.stableId == selectedDeviceId

            drawLine(
                color = deviceColor.copy(alpha = alpha * 0.3f),
                start = Offset(cx, cy), end = pos,
                strokeWidth = 1f, cap = StrokeCap.Round
            )

            if (isSelected) drawCircle(deviceColor.copy(alpha = 0.25f), 20f, pos)
            drawCircle(deviceColor.copy(alpha = alpha), if (isSelected) 12f else 8f, pos)

            val name = device.info.displayName ?: device.stableId.take(6)
            drawText(
                textMeasurer = textMeasurer,
                text = name,
                topLeft = Offset(projX + 14f, projY - 8f),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = deviceColor)
            )

            positions[device.stableId] = pos
        }

        devicePositions = positions
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEllipsePath(
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
