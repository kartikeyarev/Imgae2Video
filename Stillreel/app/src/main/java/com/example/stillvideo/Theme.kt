package com.example.stillvideo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF5FD6C8), onPrimary = Color(0xFF00302B),
    primaryContainer = Color(0xFF0F3B38), onPrimaryContainer = Color(0xFFB6F2EA),
    background = Color(0xFF0B0F17), onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF131A26), onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1A2333), onSurfaceVariant = Color(0xFF9AA7BA),
    outline = Color(0xFF3A465A), error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B7A72), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFEA), onPrimaryContainer = Color(0xFF00322E),
    background = Color(0xFFF2F5F9), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE4EAF2), onSurfaceVariant = Color(0xFF4B576A),
    outline = Color(0xFFB5C0CF), error = Color(0xFFBA1A1A),
)

@Composable
fun StillreelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
