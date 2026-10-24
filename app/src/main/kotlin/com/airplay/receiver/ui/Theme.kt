package com.airplay.receiver.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Brand {
    val Bg = Color(0xFF0B0E11)
    val BgElevated = Color(0xFF151A20)
    val Accent = Color(0xFF4C8BF5)
    val AccentSoft = Color(0xFF2B4A7E)
    val Good = Color(0xFF34C759)
    val Text = Color(0xFFF2F4F7)
    val TextDim = Color(0xFF8A94A6)
    val Danger = Color(0xFFFF5A5F)
}

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand.Accent,
            onPrimary = Brand.Text,
            background = Brand.Bg,
            onBackground = Brand.Text,
            surface = Brand.BgElevated,
            onSurface = Brand.Text,
        ),
        content = content,
    )
}
