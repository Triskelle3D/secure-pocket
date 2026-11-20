package com.cairn.securepocket.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun WhisperTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = palette.text, onPrimary = palette.background, secondary = palette.emerald,
        onSecondary = palette.background, tertiary = palette.crimson, error = palette.error,
        background = palette.background, onBackground = palette.text, surface = palette.surface,
        onSurface = palette.text, surfaceVariant = palette.surface, onSurfaceVariant = palette.muted,
        outline = palette.muted, outlineVariant = palette.hairline,
    ) else lightColorScheme(
        primary = palette.text, onPrimary = ColorWhite, secondary = palette.emerald,
        onSecondary = ColorWhite, tertiary = palette.crimson, error = palette.error,
        background = palette.background, onBackground = palette.text, surface = palette.surface,
        onSurface = palette.text, surfaceVariant = Color(0xFFE8EAEE), onSurfaceVariant = palette.muted,
        outline = palette.muted, outlineVariant = palette.hairline,
    )
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White
