package com.airplay.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Apple-inspired dark palette, tuned for a 10-foot display in a dark room.
 * Colors echo iOS/tvOS system values (system blue / green / red in dark mode)
 * for a calm, familiar, premium feel with strong contrast on a TV.
 */
object Brand {
    // Backgrounds — layered, not flat black, for a sense of depth.
    val Bg = Color(0xFF07090D)          // deepest base
    val BgTop = Color(0xFF12161F)       // top of the ambient gradient
    val Surface = Color(0xFF16191F)     // resting card / row
    val SurfaceFocused = Color(0xFF23272F) // row lifted on focus
    val Hairline = Color(0xFF2A2E37)    // subtle separators / inert borders

    // Accent — iOS system blue (dark), with a soft companion for glows.
    val Accent = Color(0xFF0A84FF)
    val AccentSoft = Color(0x330A84FF)
    val Good = Color(0xFF30D158)        // iOS system green (dark)
    val GoodSoft = Color(0x3330D158)
    val Danger = Color(0xFFFF453A)      // iOS system red (dark)

    // Typography
    val Text = Color(0xFFF5F5F7)        // primary label
    val TextDim = Color(0xFF9A9AA2)     // secondary label
    val TextMuted = Color(0xFF65656D)   // tertiary label
    val OnAccent = Color(0xFFFFFFFF)
}

/** Shared corner radii for a consistent, soft, Apple-like geometry. */
object Shapes {
    val Row = RoundedCornerShape(18.dp)
    val Card = RoundedCornerShape(24.dp)
    val Chip = RoundedCornerShape(14.dp)
    val Button = RoundedCornerShape(16.dp)
}

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Accent,
            onPrimary = Brand.OnAccent,
            primaryContainer = Brand.Accent,
            onPrimaryContainer = Brand.OnAccent,
            secondary = Brand.Good,
            onSecondary = Brand.OnAccent,
            background = Brand.Bg,
            onBackground = Brand.Text,
            surface = Brand.Surface,
            onSurface = Brand.Text,
            surfaceVariant = Brand.SurfaceFocused,
            onSurfaceVariant = Brand.TextDim,
            border = Brand.Hairline,
            error = Brand.Danger,
        ),
        content = content,
    )
}

/**
 * The ambient screen background: a soft vertical gradient from a faintly
 * blue-lit top down to near-black, plus a gentle accent bloom in the upper
 * area. Gives the flat panel a sense of depth without any visible banding.
 */
@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.BgTop, Brand.Bg)))
            .background(
                Brush.radialGradient(
                    colors = listOf(Brand.AccentSoft, Color.Transparent),
                    center = Offset(960f, 120f),
                    radius = 1100f,
                )
            ),
    ) { content() }
}
