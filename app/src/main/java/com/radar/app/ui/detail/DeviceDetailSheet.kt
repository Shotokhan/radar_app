package com.radar.app.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radar.app.data.model.DeviceClass
import com.radar.app.data.model.SignalTrend
import com.radar.app.data.model.SignalType
import com.radar.app.data.model.TrackedDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    device: TrackedDevice,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deviceClassEmoji(device.info.inferredClass),
                    fontSize = 28.sp
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.info.displayName ?: "Unknown Device",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = deviceClassLabel(device.info.inferredClass),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Proximity + confidence
            InfoSection(title = "Proximity") {
                ProximityBar(device.proximityScore)
                Spacer(Modifier.height(6.dp))
                InfoRow("Confidence", "%.0f%%".format(device.overallConfidence * 100))
                device.bearingDeg?.let {
                    InfoRow("Estimated direction", "%.0f°".format(it))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Active signals
            InfoSection(title = "Signals") {
                device.signals.forEach { signal ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = signalTypeLabel(signal.type),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            signal.currentRssi?.let {
                                Text("$it dBm", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TrendChip(signal.trend)
                        }
                    }
                }
                if (device.signals.isEmpty()) {
                    Text("No active signals", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Network info (if available)
            if (device.info.ipAddress != null || device.info.mdnsServices.isNotEmpty()) {
                InfoSection(title = "Network") {
                    device.info.ipAddress?.let { InfoRow("IP Address", it) }
                    if (device.info.mdnsServices.isNotEmpty()) {
                        InfoRow("Services", device.info.mdnsServices.joinToString(", "))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Identity
            InfoSection(title = "Identity") {
                InfoRow("Available via", device.info.availableProtocols.joinToString(" · ") {
                    signalTypeLabel(it)
                })
                if (device.info.knownMacs.isNotEmpty()) {
                    InfoRow(
                        "Known addresses",
                        device.info.knownMacs.joinToString("\n"),
                        monospace = true
                    )
                }
                if (device.info.macRotationCount > 0) {
                    InfoRow("MAC rotations", device.info.macRotationCount.toString())
                }
                device.info.mergeConfidence?.let {
                    InfoRow(
                        "Cross-protocol merge",
                        "%.0f%% confidence".format(it * 100),
                        note = "BLE and Wi-Fi signals may be same device"
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Timestamps
            InfoSection(title = "Timeline") {
                InfoRow("First seen", formatTimestamp(device.info.firstSeenMs))
                InfoRow("Last updated", formatTimestamp(device.lastUpdatedMs))
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false, note: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Column(modifier = Modifier.weight(0.55f), horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                fontWeight = FontWeight.Medium
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProximityBar(score: Float) {
    LinearProgressIndicator(
        progress = { score },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = when {
            score > 0.7f -> Color(0xFF4CAF50)
            score > 0.4f -> Color(0xFFFF9800)
            else -> Color(0xFF2196F3)
        },
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
private fun TrendChip(trend: SignalTrend) {
    val (text, color) = when (trend) {
        SignalTrend.APPROACHING -> "↑ closer" to Color(0xFF4CAF50)
        SignalTrend.RECEDING -> "↓ farther" to Color(0xFFFF5722)
        SignalTrend.STABLE -> "→ stable" to Color(0xFF9E9E9E)
        SignalTrend.UNKNOWN -> "? unknown" to Color(0xFF9E9E9E)
    }
    Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
}

private fun deviceClassEmoji(cls: DeviceClass) = when (cls) {
    DeviceClass.PHONE -> "📱"
    DeviceClass.ACCESSORY -> "🎧"
    DeviceClass.AP -> "📡"
    DeviceClass.IOT -> "🔌"
    DeviceClass.UNKNOWN -> "❓"
}

private fun deviceClassLabel(cls: DeviceClass) = when (cls) {
    DeviceClass.PHONE -> "Phone / Tablet"
    DeviceClass.ACCESSORY -> "Bluetooth accessory"
    DeviceClass.AP -> "Wi-Fi access point"
    DeviceClass.IOT -> "IoT device"
    DeviceClass.UNKNOWN -> "Unknown device"
}

private fun signalTypeLabel(type: SignalType) = when (type) {
    SignalType.BLE -> "Bluetooth LE"
    SignalType.WIFI_AP -> "Wi-Fi AP"
    SignalType.WIFI_CLIENT -> "Wi-Fi client"
    SignalType.NETWORK -> "Network"
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
