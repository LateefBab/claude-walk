package com.tugartech.claudewalk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val WalkLight = lightColorScheme(
    primary = WalkColors.Accent,
    onPrimary = WalkColors.BgSecondary,
    primaryContainer = WalkColors.BgTertiary,
    onPrimaryContainer = WalkColors.TextPrimary,
    background = WalkColors.BgPrimary,
    onBackground = WalkColors.TextPrimary,
    surface = WalkColors.BgSecondary,
    onSurface = WalkColors.TextPrimary,
    surfaceVariant = WalkColors.BgTertiary,
    onSurfaceVariant = WalkColors.TextSecondary,
    outline = WalkColors.BorderDefault,
    outlineVariant = WalkColors.BorderSubtle,
    error = WalkColors.Critical,
    onError = WalkColors.BgSecondary,
)

/**
 * Type scale from the global UI standards: 12 / 13 / 14 / 16 / 20 / 24, mono 13.
 *
 * Body sits at 16sp rather than the usual 14sp because this is read at arm's length
 * while moving, not at a desk.
 */
private val WalkTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Monospace style for ports, paths and other technical detail. */
val MonoSmall = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)

@Composable
fun ClaudeWalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WalkLight,
        typography = WalkTypography,
        content = content,
    )
}
