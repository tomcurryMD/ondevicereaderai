package com.readertomeai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Brand colors
val Purple = Color(0xFF6C63FF)
val PurpleDark = Color(0xFF5A52D5)
val PurpleLight = Color(0xFF9D97FF)
val Coral = Color(0xFFFF6584)
val DarkBg = Color(0xFF1A1A2E)
val DarkSurface = Color(0xFF16213E)

private val DarkColorScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleDark,
    secondary = Coral,
    onSecondary = Color.White,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = Color(0xFFEAEAEA),
    onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF1E2A47),
    onSurfaceVariant = Color(0xFFBBBBCC)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleLight,
    secondary = Coral,
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF0F0F5),
    onSurfaceVariant = Color(0xFF555566)
)

@Composable
fun ReaderToMeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

val Typography = Typography()

// Reading theme colors (separate from app theme)
data class ReadingColors(
    val background: Color,
    val text: Color,
    val link: Color
)

val LightReadingColors = ReadingColors(
    background = Color(0xFFFAFAFA),
    text = Color(0xFF1A1A2E),
    link = Color(0xFF6C63FF)
)

val DarkReadingColors = ReadingColors(
    background = Color(0xFF1A1A2E),
    text = Color(0xFFEAEAEA),
    link = Color(0xFF8B8BFF)
)

val SepiaReadingColors = ReadingColors(
    background = Color(0xFFF4ECD8),
    text = Color(0xFF5B4636),
    link = Color(0xFF8B6914)
)
