package com.airplay.receiver.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.airplay.receiver.ReceiverState

@Composable
fun HomeScreen(
    status: ReceiverState.Status,
    onOpenSettings: () -> Unit,
    needsOverlayPermission: Boolean = false,
    onAllowOverlay: () -> Unit = {},
) {
    val settingsFocus = remember { FocusRequester() }
    val allowFocus = remember { FocusRequester() }
    val kind = statusKind(status, needsOverlayPermission)
    // Land on the one thing to do next: "Allow" during setup, else Settings.
    LaunchedEffect(kind == Kind.SETUP) {
        runCatching { (if (kind == Kind.SETUP) allowFocus else settingsFocus).requestFocus() }
    }

    ScreenBackground {
        val connected = status is ReceiverState.Status.Connected
        val ready = status is ReceiverState.Status.Ready
        val error = status is ReceiverState.Status.Error

        // Top bar: wordmark left, Settings right (tvOS style). Keeping Settings out
        // of the centre column is what lets the hero fit the 540dp-tall screen;
        // at the bottom it was squeezed until its label clipped away.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 72.dp, end = 64.dp, top = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Beam", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Brand.Text, letterSpacing = 0.5.sp)
            Spacer(Modifier.width(12.dp))
            Text("Beam your screen to any TV", fontSize = 15.sp, color = Brand.TextMuted)
            Spacer(Modifier.weight(1f))
            PillButton(
                label = "Settings",
                leadingIcon = { GearIcon() },
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(settingsFocus),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 80.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AirPlayGlyph(active = ready, connected = connected, error = error)

            Spacer(Modifier.height(14.dp))
            Text(
                text = headline(status),
                fontSize = 40.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Brand.Text,
                letterSpacing = (-0.5).sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusLine(status),
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    connected -> Brand.Good
                    error -> Brand.Danger
                    else -> Brand.TextDim
                },
            )

            Spacer(Modifier.height(22.dp))

            // Animate on the status itself (keyed by kind) and render from the
            // lambda's value: the outgoing card keeps composing during its fade,
            // and reading the *current* status there cast a new Ready to the old
            // card's Connected and crashed.
            AnimatedContent(
                targetState = status,
                contentKey = { statusKind(it, needsOverlayPermission) },
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                label = "hero",
            ) { shown ->
                when (shown) {
                    is ReceiverState.Status.Ready ->
                        if (needsOverlayPermission) PermissionCard(onAllow = onAllowOverlay, focus = allowFocus)
                        else InstructionCard(deviceName = shown.deviceName)
                    is ReceiverState.Status.Connected -> ConnectedCard(shown)
                    is ReceiverState.Status.Error -> MessageCard(shown.message, danger = true)
                    ReceiverState.Status.Starting -> MessageCard("Getting ready to receive…", danger = false)
                }
            }
        }
    }
}

private enum class Kind { STARTING, SETUP, READY, CONNECTED, ERROR }

private fun statusKind(s: ReceiverState.Status, needsOverlayPermission: Boolean) = when (s) {
    ReceiverState.Status.Starting -> Kind.STARTING
    is ReceiverState.Status.Ready -> if (needsOverlayPermission) Kind.SETUP else Kind.READY
    is ReceiverState.Status.Connected -> Kind.CONNECTED
    is ReceiverState.Status.Error -> Kind.ERROR
}

/**
 * First-run step: without "display over other apps", Android won't let Beam
 * bring the mirror screen forward while another app is on the TV.
 */
@Composable
private fun PermissionCard(onAllow: () -> Unit, focus: FocusRequester) {
    Column(
        Modifier
            .widthIn(max = 900.dp)
            .clip(Shapes.Card)
            .background(Brand.Surface.copy(alpha = 0.72f))
            .padding(horizontal = 40.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "One quick step before your first mirror",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Brand.Text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow Beam to appear over other apps, so your screen shows up the " +
                "moment you connect, even while you're watching something else.",
            fontSize = 16.sp,
            color = Brand.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PillButton(
            label = "Allow",
            accent = true,
            onClick = onAllow,
            modifier = Modifier.focusRequester(focus),
        )
    }
}

/** "How to connect" — a compact, inviting, non-technical three-step strip. */
@Composable
private fun InstructionCard(deviceName: String) {
    Column(
        Modifier
            .widthIn(max = 900.dp)
            .clip(Shapes.Card)
            .background(Brand.Surface.copy(alpha = 0.72f))
            .padding(horizontal = 40.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Mirror from your iPhone, iPad, or Mac",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Brand.Text,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Step(Modifier.weight(1f), 1, "Open", "Control Center")
            Step(Modifier.weight(1f), 2, "Tap", "Screen Mirroring")
            Step(Modifier.weight(1f), 3, "Choose", deviceName)
        }
    }
}

@Composable
private fun Step(modifier: Modifier, number: Int, action: String, target: String) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Brand.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Brand.Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(action, fontSize = 14.sp, color = Brand.TextDim)
            Text(target, fontSize = 17.sp, color = Brand.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ConnectedCard(s: ReceiverState.Status.Connected) {
    val what = when (s.what) {
        ReceiverState.Session.MIRRORING -> "is mirroring to this TV"
        ReceiverState.Session.AUDIO -> "is playing audio on this TV"
    }
    Row(
        Modifier
            .widthIn(max = 640.dp)
            .clip(Shapes.Card)
            .background(Brand.GoodSoft)
            .padding(horizontal = 36.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Brand.Good),
            contentAlignment = Alignment.Center,
        ) { Text("✓", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(s.sender, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text)
            Text(what, fontSize = 16.sp, color = Brand.TextDim)
        }
    }
}

@Composable
private fun MessageCard(message: String, danger: Boolean) {
    Box(
        Modifier
            .widthIn(max = 640.dp)
            .clip(Shapes.Card)
            .background(if (danger) Color(0x33FF453A) else Brand.Surface.copy(alpha = 0.6f))
            .padding(horizontal = 36.dp, vertical = 24.dp),
    ) {
        Text(
            message,
            fontSize = 18.sp,
            color = if (danger) Brand.Danger else Brand.TextDim,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The AirPlay mark — a rounded screen with an upward triangle — drawn on a
 * Canvas. Soft pulsing rings and a glow while advertising; a steady green mark
 * while connected. Stroke widths scale with the canvas so it stays crisp on a
 * big panel.
 */
@Composable
private fun AirPlayGlyph(active: Boolean, connected: Boolean, error: Boolean) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "p",
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b",
    )
    val tint = when {
        error -> Brand.Danger
        connected -> Brand.Good
        else -> Brand.Accent
    }

    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        // Soft ambient bloom behind the mark (radial gradient — no hard edges,
        // matched to the container so it never adds to the layout footprint).
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            tint.copy(alpha = if (active || connected) 0.32f else 0.16f),
                            Color.Transparent,
                        ),
                    )
                ),
        )
        Canvas(Modifier.size(130.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = size.minDimension

            if (active) {
                val maxR = unit / 2f
                for (i in 0 until 3) {
                    val phase = (pulse + i / 3f) % 1f
                    drawCircle(
                        color = tint.copy(alpha = (1f - phase) * 0.30f),
                        radius = maxR * (0.4f + 0.6f * phase),
                        center = Offset(cx, cy),
                        style = Stroke(width = unit * 0.012f),
                    )
                }
            }

            val scale = if (connected) 1f else if (active) breathe else 0.92f

            // Screen rectangle
            val w = unit * 0.44f * scale
            val h = unit * 0.32f * scale
            val left = cx - w / 2f
            val top = cy - h / 2f - unit * 0.07f
            val r = unit * 0.035f
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = unit * 0.038f),
            )
            // Upward triangle
            val tw = unit * 0.28f * scale
            val baseY = top + h + unit * 0.17f
            val apexY = top + h * 0.52f
            drawPath(
                Path().apply {
                    moveTo(cx - tw / 2f, baseY)
                    lineTo(cx + tw / 2f, baseY)
                    lineTo(cx, apexY)
                    close()
                },
                color = tint,
            )
        }
    }
}

private fun headline(s: ReceiverState.Status): String = when (s) {
    ReceiverState.Status.Starting -> "Beam"
    is ReceiverState.Status.Ready -> s.deviceName
    is ReceiverState.Status.Connected -> s.sender
    is ReceiverState.Status.Error -> "Beam"
}

private fun statusLine(s: ReceiverState.Status): String = when (s) {
    ReceiverState.Status.Starting -> "Starting up…"
    is ReceiverState.Status.Ready ->
        "Ready to connect" + (s.ip?.let { "  ·  $it" } ?: "")
    is ReceiverState.Status.Connected -> when (s.what) {
        ReceiverState.Session.MIRRORING -> "Mirroring now"
        ReceiverState.Session.AUDIO -> "Playing audio"
    }
    is ReceiverState.Status.Error -> "Needs attention"
}
