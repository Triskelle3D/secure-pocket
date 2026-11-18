package com.cairn.securepocket.ui.theme

import androidx.compose.ui.graphics.Color

// Obsidian Whisper - strict dark, from stitch_pocket_recorder/obsidian_whisper/DESIGN.md
// Base canvas
val Obsidian = Color(0xFF0A0A0C)
val ElevatedCanvas = Color(0xFF121216)
val PanelGlass = Color(0x08FFFFFF) // rgba(255,255,255,0.03)
val Hairline = Color(0x0FFFFFFF) // rgba(255,255,255,0.06)
val HairlineStrong = Color(0x1AFFFFFF) // rgba(255,255,255,0.10)
val GhostFill = Color(0x0DFFFFFF) // rgba(255,255,255,0.05)

// Roles
val OpticalWhite = Color(0xFFF4F4F6) // primary text / glyphs
val SlateText = Color(0xFF71717A) // subdued text (Slate 500)
val Emerald = Color(0xFF10B981) // live telemetry / online / isolated
val Crimson = Color(0xFFE11D48) // armed / recording / violation
val TintError = Color(0xFFFFB4AB)

// Material3 scheme mappings (dark only)
val PrimaryDark = OpticalWhite
val OnPrimaryDark = Color(0xFF2F3132)
val SecondaryDark = Emerald
val OnSecondaryDark = Color(0xFF003824)
val TertiaryDark = Crimson
val OnTertiaryDark = Color(0xFFFFDADA)
val ErrorDark = TintError
val OnErrorDark = Color(0xFF690005)
val BackgroundDark = Obsidian
val OnBackgroundDark = OpticalWhite
val SurfaceDark = ElevatedCanvas
val OnSurfaceDark = OpticalWhite
val SurfaceVariantDark = Color(0xFF353437)
val OnSurfaceVariantDark = Color(0xFFC5C7C9)
val OutlineDark = Color(0xFF8F9194)
val OutlineVariantDark = Color(0xFF44474A)
val SurfaceContainerLowestDark = Color(0xFF0E0E10)
val SurfaceContainerLowDark = Color(0xFF1C1B1D)
val SurfaceContainerDark = Color(0xFF201F21)
val SurfaceContainerHighDark = Color(0xFF2A2A2C)
val SurfaceContainerHighestDark = Color(0xFF353437)
