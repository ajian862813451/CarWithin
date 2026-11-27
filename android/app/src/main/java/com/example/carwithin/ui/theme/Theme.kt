package com.example.carwithin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1566C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F1FF),
    onPrimaryContainer = Color(0xFF134577),
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F3F8),
    onSurface = Color(0xFF111828),
    onSurfaceVariant = Color(0xFF525C70),
    outlineVariant = Color(0xFFE3E5ED),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8EC5FF),
    onPrimary = Color(0xFF052F60),
    primaryContainer = Color(0xFF163453),
    onPrimaryContainer = Color(0xFFD3E8FF),
    background = Color(0xFF101218),
    surface = Color(0xFF1B1E27),
    surfaceVariant = Color(0xFF262B36),
    onSurface = Color(0xFFF1F3FA),
    onSurfaceVariant = Color(0xFFBAC2D4),
    outlineVariant = Color(0xFF303745),
)

@Composable
fun CarWithinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography,
        content = content,
    )
}
