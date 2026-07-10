package com.radar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RadarColorScheme = darkColorScheme(
    primary = Color(0xFF00FF41),
    onPrimary = Color(0xFF050F05),
    primaryContainer = Color(0xFF0A200A),
    onPrimaryContainer = Color(0xFF00FF41),
    secondary = Color(0xFF00BFFF),
    background = Color(0xFF050F05),
    surface = Color(0xFF0A150A),
    onSurface = Color(0xFFCCFFCC),
    surfaceVariant = Color(0xFF1A2A1A),
    onSurfaceVariant = Color(0xFF88AA88)
)

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RadarColorScheme,
        content = content
    )
}
