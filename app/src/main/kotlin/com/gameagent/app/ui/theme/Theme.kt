package com.gameagent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette.
 *
 * Dark by design rather than by preference: the dashboard is meant to be readable next to a running
 * game, and a bright screen in front of the player is both distracting and a battery cost. The theme
 * is intentionally *always* dark so the UI does not change appearance when the phone's system theme
 * flips.
 */
object GameAgentColors {
    val Background = Color(0xFF0B0E14)
    val Surface = Color(0xFF141924)
    val SurfaceVariant = Color(0xFF1D2430)
    val Outline = Color(0xFF2A3342)
    val TextPrimary = Color(0xFFE6EAF2)
    val TextSecondary = Color(0xFF9AA6BC)
    val Accent = Color(0xFF62D0FF)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5C5C)
    val Neutral = Color(0xFF6B7A91)
}

private val GameAgentColorScheme = darkColorScheme(
    primary = GameAgentColors.Accent,
    onPrimary = Color(0xFF04222E),
    primaryContainer = Color(0xFF123243),
    onPrimaryContainer = GameAgentColors.Accent,
    secondary = GameAgentColors.Success,
    onSecondary = Color(0xFF042211),
    tertiary = GameAgentColors.Warning,
    background = GameAgentColors.Background,
    onBackground = GameAgentColors.TextPrimary,
    surface = GameAgentColors.Surface,
    onSurface = GameAgentColors.TextPrimary,
    surfaceVariant = GameAgentColors.SurfaceVariant,
    onSurfaceVariant = GameAgentColors.TextSecondary,
    outline = GameAgentColors.Outline,
    error = GameAgentColors.Danger,
    onError = Color(0xFF2B0606),
)

private val GameAgentTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun GameAgentTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GameAgentColorScheme,
        typography = GameAgentTypography,
        content = content,
    )
}
