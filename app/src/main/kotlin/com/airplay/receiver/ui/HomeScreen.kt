package com.airplay.receiver.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.airplay.receiver.ReceiverState

@Composable
fun HomeScreen(
    status: ReceiverState.Status,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E141C), Brand.Bg))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val connected = status is ReceiverState.Status.Connected
            AirPlayGlyph(active = status is ReceiverState.Status.Ready, connected = connected)

            Spacer(Modifier.height(36.dp))
            Text(
                text = deviceName(status),
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.Text,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = statusLine(status),
                fontSize = 17.sp,
                color = if (connected) Brand.Good else Brand.TextDim,
            )

            Spacer(Modifier.height(40.dp))
            CapabilityChips()

            Spacer(Modifier.height(40.dp))
            Button(onClick = onOpenSettings) { Text("⚙  Settings") }
        }
    }
}

@Composable
private fun CapabilityChips() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Chip("HEVC · 4K60")
        Chip("H.264 · 1080p60")
        Chip("ALAC / AAC")
    }
}

@Composable
private fun Chip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.BgElevated)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text(label, fontSize = 14.sp, color = Brand.TextDim) }
}

/**
 * The AirPlay mark (a screen with an upward triangle) drawn with Canvas, with
 * pulsing rings while advertising and a steady green glow while connected.
 */
@Composable
private fun AirPlayGlyph(active: Boolean, connected: Boolean) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "p",
    )
    val tint = if (connected) Brand.Good else Brand.Accent

    Canvas(Modifier.size(180.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        if (active || connected) {
            val maxR = size.minDimension / 2f
            val n = 3
            for (i in 0 until n) {
                val phase = ((pulse + i.toFloat() / n) % 1f)
                val r = maxR * (0.45f + 0.55f * phase)
                drawCircle(
                    color = tint.copy(alpha = (1f - phase) * 0.35f),
                    radius = r, center = Offset(cx, cy),
                    style = Stroke(width = 3f),
                )
            }
        }

        // Screen rectangle
        val w = size.width * 0.42f
        val h = size.height * 0.30f
        val left = cx - w / 2f
        val top = cy - h / 2f - size.height * 0.06f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            style = Stroke(width = 7f),
        )
        // Upward triangle
        val tw = size.width * 0.26f
        val baseY = top + h + size.height * 0.16f
        val apexY = top + h * 0.55f
        val path = Path().apply {
            moveTo(cx - tw / 2f, baseY)
            lineTo(cx + tw / 2f, baseY)
            lineTo(cx, apexY)
            close()
        }
        drawPath(path, color = tint)
    }
}

private fun deviceName(s: ReceiverState.Status): String = when (s) {
    is ReceiverState.Status.Ready -> s.deviceName
    is ReceiverState.Status.Connected -> s.sender
    else -> "AirPlay Receiver"
}

private fun statusLine(s: ReceiverState.Status): String = when (s) {
    ReceiverState.Status.Starting -> "Starting…"
    is ReceiverState.Status.Ready ->
        "Ready · select on your iPhone or Mac" + (s.ip?.let { "   ·   $it" } ?: "")
    is ReceiverState.Status.Connected -> when (s.what) {
        ReceiverState.Session.MIRRORING -> "Mirroring"
        ReceiverState.Session.AUDIO -> "Playing audio"
    }
    is ReceiverState.Status.Error -> s.message
}
