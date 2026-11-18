package com.cairn.securepocket.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cairn.securepocket.R

// Vendored OFL variable fonts (see third_party/fonts/). Static fallback is platform default.
@OptIn(ExperimentalTextApi::class)
val PlusJakartaSans = FontFamily(
    Font(R.font.plusjakartasans_var, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.plusjakartasans_var, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.plusjakartasans_var, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
@OptIn(ExperimentalTextApi::class)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrainsmono_var, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrainsmono_var, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

// Design-system styles (obsidian_whisper/DESIGN.md typography)
val MonoTimer = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 26.sp,
    lineHeight = 32.sp,
    letterSpacing = (-0.03).em,
)
val LabelCaps = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.12.em,
)
val LabelSm = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.02.em,
)

val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.025).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.015).em,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.01.em,
    ),
    labelLarge = LabelCaps,
    labelMedium = LabelSm,
    labelSmall = LabelSm,
)
