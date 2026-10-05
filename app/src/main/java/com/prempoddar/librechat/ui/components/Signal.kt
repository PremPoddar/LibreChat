package com.prempoddar.librechat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prempoddar.librechat.engine.MsgStatus
import com.prempoddar.librechat.ui.theme.LocalSignal
import com.prempoddar.librechat.ui.theme.Mono
import com.prempoddar.librechat.ui.theme.MonoSmall
import kotlin.math.cos
import kotlin.math.sin

/** How strong a link is: four bars from RSSI for direct neighbours, or the hop count. */
@Composable
fun SignalGlyph(hops: Int, rssi: Int?, modifier: Modifier = Modifier) {
    val s = LocalSignal.current
    val bars = when {
        hops != 0 -> 0
        rssi == null -> 2
        rssi > -60 -> 4
        rssi > -70 -> 3
        rssi > -82 -> 2
        else -> 1
    }
    val label = when {
        hops == 0 -> "direct"
        hops == Int.MAX_VALUE -> "away"
        else -> "$hops hop${if (hops > 1) "s" else ""}"
    }
    Row(modifier.semantics { contentDescription = "Signal: $label" }, verticalAlignment = Alignment.Bottom) {
        if (hops == 0) {
            (1..4).forEach { i ->
                Box(
                    Modifier.padding(end = 2.dp).width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                        .background(if (i <= bars) s.phosphor else s.line),
                )
            }
        } else {
            Text(label.uppercase(), style = MonoSmall, color = if (hops == Int.MAX_VALUE) s.faint else s.amber)
        }
    }
}

/** Initials in a ring; the ring is green while the person is reachable right now. */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 44.dp, live: Boolean = false, group: Boolean = false) {
    val s = LocalSignal.current
    val hue = (seed.hashCode().toLong() and 0xFFFF).toFloat() / 0xFFFF * 360f
    val tint = Color.hsl(hue, 0.35f, 0.42f)
    Box(
        Modifier.size(size).clip(if (group) RoundedCornerShape(size / 3.2f) else CircleShape).background(tint.copy(alpha = 0.22f))
            .border(1.5.dp, if (live) s.phosphor else Color.Transparent, if (group) RoundedCornerShape(size / 3.2f) else CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name), fontFamily = Mono, fontWeight = FontWeight(600), fontSize = (size.value * 0.34f).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun initials(name: String): String {
    val w = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return (if (w.size > 1) "${w[0][0]}${w[1][0]}" else w.firstOrNull()?.take(2) ?: "?").uppercase()
}

@Composable
fun Ticks(status: MsgStatus, modifier: Modifier = Modifier) {
    val s = LocalSignal.current
    val (icon, tint, label) = when (status) {
        MsgStatus.SENDING -> Triple(Icons.Rounded.Schedule, s.faint, "Waiting for the mesh")
        MsgStatus.SENT -> Triple(Icons.Rounded.Done, s.faint, "Sent")
        MsgStatus.DELIVERED -> Triple(Icons.Rounded.DoneAll, s.faint, "Delivered")
        MsgStatus.READ -> Triple(Icons.Rounded.DoneAll, s.phosphor, "Read")
        MsgStatus.FAILED -> Triple(Icons.Rounded.ErrorOutline, s.danger, "Not delivered")
        else -> return
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = modifier.size(15.dp))
}

/** The signature: a slow radar sweep with rings for hop distance. */
@Composable
fun Radar(modifier: Modifier = Modifier, blips: List<RadarBlip> = emptyList(), sweeping: Boolean = true) {
    val s = LocalSignal.current
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        for (i in 1..4) drawCircle(s.line, radius = r * i / 4f, center = c, style = Stroke(1.dp.toPx()))
        drawLine(s.line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(s.line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        if (sweeping) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, s.radarGlow, s.phosphor.copy(alpha = 0.35f)), c),
                startAngle = angle - 60f, sweepAngle = 60f, useCenter = true,
            )
        }
        blips.forEach { b ->
            val rad = Math.toRadians(b.angle.toDouble())
            val p = Offset(c.x + (r * b.distance * cos(rad)).toFloat(), c.y + (r * b.distance * sin(rad)).toFloat())
            drawCircle(if (b.direct) s.phosphor else s.amber, radius = 5.dp.toPx(), center = p)
            drawCircle((if (b.direct) s.phosphor else s.amber).copy(alpha = 0.25f), radius = 11.dp.toPx(), center = p)
        }
        drawCircle(s.phosphor, radius = 4.dp.toPx(), center = c)
    }
}

/** A person on the radar: [distance] 0..1 from the centre, [angle] in degrees. */
data class RadarBlip(val angle: Float, val distance: Float, val direct: Boolean)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalSignal.current.faint,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))
