package com.radar.app.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.radar.app.data.model.SignalType
import com.radar.app.ui.detail.DeviceDetailSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    modifier: Modifier = Modifier,
    viewModel: RadarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableStateOf(1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050F05))
    ) {
        val radarModifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    currentZoom = (currentZoom * zoom).coerceIn(0.3f, 3f)
                    viewModel.setZoom(currentZoom)
                }
            }

        when (state.viewMode) {
            RadarViewMode.FLAT_2D -> RadarCanvas2D(
                devices = state.devices,
                zoomLevel = state.zoomLevel,
                selectedDeviceId = state.selectedDeviceId,
                onDeviceTapped = { viewModel.selectDevice(it) },
                onBackgroundTapped = { viewModel.selectDevice(null) },
                modifier = radarModifier
            )
            RadarViewMode.SPHERE_3D -> RadarCanvas3D(
                devices = state.devices,
                zoomLevel = state.zoomLevel,
                selectedDeviceId = state.selectedDeviceId,
                onDeviceTapped = { viewModel.selectDevice(it) },
                onBackgroundTapped = { viewModel.selectDevice(null) },
                modifier = radarModifier
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Radar", color = Color(0xFF00FF41), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${state.devices.size} device${if (state.devices.size != 1) "s" else ""}",
                    color = Color(0xFF00FF41).copy(alpha = 0.6f), fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { showFilters = !showFilters },
                    modifier = Modifier.background(
                        Color(0xFF00FF41).copy(alpha = if (showFilters) 0.2f else 0.1f), CircleShape
                    )
                ) {
                    Icon(Icons.Default.FilterList, "Filters", tint = Color(0xFF00FF41))
                }
                TextButton(
                    onClick = { viewModel.toggleViewMode() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00FF41)),
                    modifier = Modifier.background(Color(0xFF00FF41).copy(alpha = 0.1f), MaterialTheme.shapes.small)
                ) {
                    Text(if (state.viewMode == RadarViewMode.FLAT_2D) "3D" else "2D", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showFilters) {
            FilterPanel(
                activeProtocols = state.activeProtocols,
                onToggle = { viewModel.toggleProtocol(it) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 60.dp, end = 16.dp)
            )
        }

        if (!state.hasPermissions) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF050F05).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("📡", fontSize = 48.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Radar needs Bluetooth and Location permissions to scan for nearby devices.",
                        color = Color(0xFF00FF41),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (state.isScanning && state.devices.isEmpty()) {
            Box(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF00FF41))
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning...", color = Color(0xFF00FF41).copy(alpha = 0.7f))
                }
            }
        }
    }

    if (state.selectedDeviceId != null) {
        viewModel.getSelectedDevice()?.let { device ->
            DeviceDetailSheet(device = device, onDismiss = { viewModel.selectDevice(null) })
        }
    }
}

@Composable
private fun FilterPanel(
    activeProtocols: Set<SignalType>,
    onToggle: (SignalType) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0A200A).copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.medium,
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Protocols", color = Color(0xFF00FF41).copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            listOf(SignalType.BLE, SignalType.WIFI_AP, SignalType.NETWORK).forEach { type ->
                val active = type in activeProtocols
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(
                        checked = active,
                        onCheckedChange = { onToggle(type) },
                        modifier = Modifier.height(24.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00FF41),
                            checkedTrackColor = Color(0xFF00FF41).copy(alpha = 0.3f)
                        )
                    )
                    Text(
                        text = when (type) {
                            SignalType.BLE -> "Bluetooth LE"
                            SignalType.WIFI_AP -> "Wi-Fi APs"
                            SignalType.NETWORK -> "Network"
                            else -> type.name
                        },
                        color = if (active) Color(0xFF00FF41) else Color(0xFF00FF41).copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
