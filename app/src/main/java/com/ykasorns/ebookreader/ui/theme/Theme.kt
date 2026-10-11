package com.ykasorns.ebookreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE07A3A)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC8642A),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFF6E5D8),
    onSecondaryContainer = Color(0xFF3A2416),
    background = Color.White,
    surface = Color.White,
    surfaceContainer = Color(0xFFF5F3F1),
    surfaceContainerHigh = Color(0xFFEFECE9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4A46F),
    onPrimary = Color(0xFF3A1D08),
    secondaryContainer = Color(0xFF4A3426),
    onSecondaryContainer = Color(0xFFF6E5D8),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
)

@Composable
fun EbookReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
