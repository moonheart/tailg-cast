package com.tailg.cast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TailgBlue = Color(0xFF1D4ED8)
private val TailgBlueDark = Color(0xFF8AB4FF)

private val LightColors = lightColorScheme(
    primary = TailgBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE6FF),
    onPrimaryContainer = Color(0xFF0A1D4A),
    secondary = Color(0xFF475569),
    secondaryContainer = Color(0xFFE6ECF7),
    onSecondaryContainer = Color(0xFF0F172A),
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFECEFF6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFD6DCE8),
)

private val DarkColors = darkColorScheme(
    primary = TailgBlueDark,
    onPrimary = Color(0xFF08214F),
    primaryContainer = Color(0xFF163268),
    onPrimaryContainer = Color(0xFFDBE6FF),
    secondary = Color(0xFFB6C2D9),
    secondaryContainer = Color(0xFF1D2940),
    onSecondaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0A0E17),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1B2436),
    onSurfaceVariant = Color(0xFFA9B6CC),
    outline = Color(0xFF2B364D),
)

@Composable
fun TailgCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
