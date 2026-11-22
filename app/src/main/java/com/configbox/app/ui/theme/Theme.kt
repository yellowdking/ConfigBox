package com.configbox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PureWhite = Color(0xFFFFFFFF)
val SoftSurface = Color(0xFFF8FAFC)
val BorderGray = Color(0xFFE2E8F0)
val SlateInk = Color(0xFF0F172A)
val SlateMuted = Color(0xFF64748B)
val SkyAccent = Color(0xFF0284C7)
val EmeraldActive = Color(0xFF059669)
val AmberBadge = Color(0xFFD97706)
val RoseDanger = Color(0xFFDC2626)

private val MinimalLightColorScheme = lightColorScheme(
    primary = SkyAccent,
    onPrimary = PureWhite,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = EmeraldActive,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    background = SoftSurface,
    onBackground = SlateInk,
    surface = PureWhite,
    onSurface = SlateInk,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = SlateMuted,
    outline = BorderGray,
    error = RoseDanger,
    onError = PureWhite
)

private val ConfigBoxTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = SlateInk
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = SlateInk
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SlateInk
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = SlateMuted
    )
)

@Composable
fun ConfigBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MinimalLightColorScheme,
        typography = ConfigBoxTypography,
        content = content
    )
}
