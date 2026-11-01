package com.bbcl.snapsave.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val BrandPink = Color(0xFFFF5F6D)
val BrandPurple = Color(0xFF7B2FF7)
val BrandGradient = Brush.linearGradient(listOf(BrandPink, BrandPurple))
val BrandGradientH = Brush.horizontalGradient(listOf(BrandPink, BrandPurple))
val SuccessGreen = Color(0xFF22B573)

private val Light = lightColorScheme(
    primary = Color(0xFF7B2FF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE3FF),
    onPrimaryContainer = Color(0xFF2A0A63),
    secondary = Color(0xFFE5485B),
    secondaryContainer = Color(0xFFFFE1E4),
    onSecondaryContainer = Color(0xFF5C0C16),
    background = Color(0xFFF7F5FB),
    onBackground = Color(0xFF1B1823),
    surface = Color.White,
    onSurface = Color(0xFF1B1823),
    surfaceVariant = Color(0xFFF0ECF7),
    onSurfaceVariant = Color(0xFF6B6578),
    surfaceContainerHighest = Color(0xFFEAE5F3),
    outline = Color(0xFFD9D3E4),
    outlineVariant = Color(0xFFE9E4F1),
    error = Color(0xFFD93445),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB993FF),
    onPrimary = Color(0xFF2A0A63),
    primaryContainer = Color(0xFF3B2368),
    onPrimaryContainer = Color(0xFFEDE3FF),
    secondary = Color(0xFFFF8A95),
    secondaryContainer = Color(0xFF5A2330),
    onSecondaryContainer = Color(0xFFFFE1E4),
    background = Color(0xFF100E17),
    onBackground = Color(0xFFECE8F4),
    surface = Color(0xFF1B1824),
    onSurface = Color(0xFFECE8F4),
    surfaceVariant = Color(0xFF262232),
    onSurfaceVariant = Color(0xFFA9A2B8),
    surfaceContainerHighest = Color(0xFF2E2A3B),
    outline = Color(0xFF3D3849),
    outlineVariant = Color(0xFF2E2A3A),
    error = Color(0xFFFF6B78),
)

@Composable
fun SnapSaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
