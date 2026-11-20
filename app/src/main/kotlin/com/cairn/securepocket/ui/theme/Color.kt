package com.cairn.securepocket.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class AppPalette(
    val background: Color, val surface: Color, val hairline: Color, val ghost: Color,
    val text: Color, val muted: Color, val emerald: Color, val crimson: Color, val error: Color,
)

internal val DarkPalette = AppPalette(Color(0xFF0A0A0C), Color(0xFF121216), Color(0xFF27272A),
    Color(0xFF18181B), Color(0xFFF4F4F6), Color(0xFF9A9AA3), Color(0xFF10B981), Color(0xFFE11D48), Color(0xFFFFB4AB))
internal val LightPalette = AppPalette(Color(0xFFF6F7F9), Color(0xFFFFFFFF), Color(0xFFD5D8DE),
    Color(0xFFECEEF2), Color(0xFF191B20), Color(0xFF626873), Color(0xFF087F5B), Color(0xFFBD243D), Color(0xFFB3261E))
internal val LocalAppPalette = compositionLocalOf { DarkPalette }

val Obsidian: Color @Composable get() = LocalAppPalette.current.background
val ElevatedCanvas: Color @Composable get() = LocalAppPalette.current.surface
val PanelGlass: Color @Composable get() = LocalAppPalette.current.ghost
val Hairline: Color @Composable get() = LocalAppPalette.current.hairline
val HairlineStrong: Color @Composable get() = LocalAppPalette.current.hairline
val GhostFill: Color @Composable get() = LocalAppPalette.current.ghost
val OpticalWhite: Color @Composable get() = LocalAppPalette.current.text
val SlateText: Color @Composable get() = LocalAppPalette.current.muted
val Emerald: Color @Composable get() = LocalAppPalette.current.emerald
val Crimson: Color @Composable get() = LocalAppPalette.current.crimson
val TintError: Color @Composable get() = LocalAppPalette.current.error
