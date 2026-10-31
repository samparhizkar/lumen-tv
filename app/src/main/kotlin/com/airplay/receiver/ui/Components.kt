package com.airplay.receiver.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.LocalContentColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Uppercase, tracked section label — the quiet header above a group of rows. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = Brand.TextMuted,
        modifier = modifier.padding(start = 6.dp, bottom = 12.dp),
    )
}

/**
 * A focusable settings row backed by a TV [Surface]. It is the single focus
 * target for the row (so D-pad up/down steps cleanly from one row to the next),
 * and on focus it brightens, scales slightly and picks up an accent hairline —
 * the Apple "spotlight" feel, tuned to stay calm. No glow: the blurred shadow
 * cost a full blur pass per focused frame on the dongle's GPU.
 */
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = Shapes.Row),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Brand.Surface,
            contentColor = Brand.Text,
            focusedContainerColor = Brand.SurfaceFocused,
            focusedContentColor = Brand.Text,
            pressedContainerColor = Brand.SurfaceFocused,
            pressedContentColor = Brand.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Brand.Accent),
                shape = Shapes.Row,
            ),
        ),
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) { content() }
    }
}

/** Title + subtitle on the left; a trailing control/value slot on the right. */
@Composable
private fun RowBody(
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = Brand.Text)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 14.sp, color = Brand.TextDim)
            }
        }
        Spacer(Modifier.width(20.dp))
        trailing()
    }
}

/** A row that opens something (e.g. the name editor). Shows a value + chevron. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String?,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableRow(onClick = onClick, modifier = modifier) {
        RowBody(title, subtitle) {
            Text(value, fontSize = 19.sp, color = Brand.TextDim)
            Spacer(Modifier.width(14.dp))
            Text("›", fontSize = 26.sp, color = Brand.TextMuted)
        }
    }
}

/** A row that toggles a boolean. The whole row is the focus/click target. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableRow(onClick = { onCheckedChange(!checked) }, modifier = modifier) {
        RowBody(title, subtitle) { SwitchVisual(checked) }
    }
}

/**
 * A non-interactive switch indicator. Drawn ourselves (rather than the tv
 * Switch) so the row stays the one and only focus target — the thumb slides and
 * the track crossfades to accent, matching the iOS control.
 */
@Composable
fun SwitchVisual(on: Boolean) {
    val track by animateColorAsState(
        if (on) Brand.Good else Brand.Hairline, tween(220), label = "track",
    )
    val thumbOffset by animateDpAsState(if (on) 22.dp else 2.dp, tween(220), label = "thumb")
    // Shape-aware fills rather than clip(): a clip costs an anti-aliased mask on
    // every frame the row repaints.
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .background(track, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = thumbOffset)
                .size(26.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** A static, non-focusable information row for the About group. */
@Composable
fun InfoRow(title: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brand.Surface, Shapes.Row)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 19.sp, color = Brand.TextDim, modifier = Modifier.width(200.dp))
            Text(value, fontSize = 19.sp, color = Brand.Text, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A primary pill button with clear TV focus (accent fill + lift) used for the
 * main call to action on each screen.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    accent: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = Shapes.Button),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (accent) Brand.Accent else Brand.Surface,
            contentColor = if (accent) Brand.OnAccent else Brand.Text,
            focusedContainerColor = if (accent) Brand.Accent else Brand.SurfaceFocused,
            focusedContentColor = if (accent) Brand.OnAccent else Brand.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (accent) Brand.OnAccent else Brand.Accent),
                shape = Shapes.Button,
            ),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 30.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(12.dp))
            } else if (leading != null) {
                Text(leading, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A solid eight-tooth gear drawn on a Canvas, in the current content colour.
 * Font gear glyphs (⚙) fall back to a thin, sun-like outline on Android TV.
 */
@Composable
fun GearIcon(size: Dp = 22.dp) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension / 2f
        val root = outer * 0.74f
        val teeth = 8
        val step = (2 * PI / teeth).toFloat()
        val gear = Path().apply {
            fillType = PathFillType.EvenOdd
            for (i in 0 until teeth) {
                val a = i * step
                // root → tooth flank → flat tip → flank → root, tip narrower than base
                val pts = listOf(
                    root to a - step * 0.30f, outer to a - step * 0.17f,
                    outer to a + step * 0.17f, root to a + step * 0.30f,
                )
                pts.forEachIndexed { j, (r, t) ->
                    val x = c.x + r * cos(t)
                    val y = c.y + r * sin(t)
                    if (i == 0 && j == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            addOval(Rect(c, outer * 0.32f))
        }
        drawPath(gear, color)
    }
}

/** Small static capability chip, e.g. "HEVC · 4K60". */
@Composable
fun Chip(label: String) {
    Box(
        Modifier
            .background(Brand.Surface, Shapes.Chip)
            .border(BorderStroke(1.dp, Brand.Hairline), Shapes.Chip)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 15.sp, color = Brand.TextDim, fontWeight = FontWeight.Medium)
    }
}
