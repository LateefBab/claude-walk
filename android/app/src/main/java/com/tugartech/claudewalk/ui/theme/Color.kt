package com.tugartech.claudewalk.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Light palette, in the style of Apple's system grays. Light rather than dark on
 * purpose: this screen is read outdoors at arm's length, where a bright surface
 * stays legible in sunlight and a dark one does not.
 */
object WalkColors {
    val TextPrimary = Color(0xFF1C1C1E)
    val TextSecondary = Color(0xA81C1C1E) // rgba(28,28,30,0.66)
    val TextTertiary = Color(0x591C1C1E) // rgba(28,28,30,0.35)

    val BgPrimary = Color(0xFFF5F5F7)
    val BgSecondary = Color(0xFFFFFFFF)
    val BgTertiary = Color(0xFFE5E5EA)

    val BorderSubtle = Color(0x1A3C3C43) // rgba(60,60,67,0.10)
    val BorderDefault = Color(0x333C3C43) // rgba(60,60,67,0.20)

    val Accent = Color(0xFF007AFF)
    val AccentHover = Color(0xFF0056D6)

    val Success = Color(0xFF15803D)
    val Warning = Color(0xFFB45309)
    val Critical = Color(0xFFB91C1C)
    val Info = Color(0xFF0369A1)
}
