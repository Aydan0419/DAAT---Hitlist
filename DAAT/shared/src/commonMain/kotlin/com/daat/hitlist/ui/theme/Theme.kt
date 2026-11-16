package com.daat.hitlist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Red = Color(0xFFE53935)
val RedDeep = Color(0xFF9A0007)
val Ink = Color(0xFF0E0E10)
val Panel = Color(0xFF1A1A1E)
val PanelHigh = Color(0xFF26262C)
val Mist = Color(0xFFB4B4BC)
val Gold = Color(0xFFFFC53D)
val Green = Color(0xFF3DDC84)

private val Colors = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = RedDeep,
    onPrimaryContainer = Color.White,
    secondary = Gold,
    onSecondary = Ink,
    tertiary = Green,
    onTertiary = Ink,
    background = Ink,
    onBackground = Color.White,
    surface = Ink,
    onSurface = Color.White,
    surfaceVariant = Panel,
    onSurfaceVariant = Mist,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Panel,
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF2A2A30),
    error = Color(0xFFFF6B6B),
)

private val Type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
}

/** Hitlist is always dark: it reads better outdoors at night and saves battery on OLED screens. */
@Composable
fun HitlistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
