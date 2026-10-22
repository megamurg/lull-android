package com.lull.app.ui

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lull.app.model.Format
import com.lull.app.model.Phase
import com.lull.app.model.Schedule
import kotlin.math.max

/** The night drawn as one line: above the midline is warming, below it is cooling. Decorative: no semantics. */
@Composable
fun NightCurve(
    phases: List<Phase>,
    /** 0…1 through the schedule while it is running. */
    progress: Double? = null,
    selectionId: String? = null,
    lineWidth: Dp = 3.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // Inset so the line's round caps and the progress dot are never clipped at the edges (8 pt in Swift).
        val inset = 8.dp.toPx()
        val width = size.width - inset * 2
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        translate(left = inset) {
            drawNight(phases, progress, selectionId, lineWidth.toPx(), width, height)
        }
    }
}

private fun DrawScope.drawNight(
    phases: List<Phase>,
    progress: Double?,
    selectionId: String?,
    lineWidth: Float,
    width: Float,
    height: Float,
) {
    val total = max(phases.sumOf { it.minutes }, 1).toDouble()
    val mid = height / 2
    val amplitude = height * 0.42f
    fun point(fraction: Double) = Offset(
        x = (width * fraction).toFloat(),
        y = (mid - NightCurveMath.level(fraction * total, phases) * amplitude).toFloat(),
    )

    var elapsed = 0.0
    for (phase in phases) {
        val start = (width * elapsed / total).toFloat()
        val phaseWidth = (width * phase.minutes / total).toFloat()
        if (phase.id == selectionId) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.07f),
                topLeft = Offset(start, 0f),
                size = Size(phaseWidth, height),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
        }
        if (elapsed > 0) {
            drawRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(start, mid - 5.dp.toPx()),
                size = Size(1.dp.toPx(), 10.dp.toPx()),
            )
        }
        elapsed += phase.minutes
    }

    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = Offset(0f, mid),
        end = Offset(width, mid),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
    )

    // One sample every 2 pt, as in Swift.
    val samples = max((width / 2.dp.toPx()).toInt(), 2)
    val points = List(samples + 1) { index -> point(index.toDouble() / samples) }
    val line = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (index in 1..samples) lineTo(points[index].x, points[index].y)
    }
    val area = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (index in 1..samples) lineTo(points[index].x, points[index].y)
        lineTo(width, mid)
        lineTo(0f, mid)
        close()
    }

    val top = Offset(0f, mid - amplitude)
    val bottom = Offset(0f, mid + amplitude)
    val fill = Brush.linearGradient(
        0f to LullColors.hot.copy(alpha = 0.45f),
        0.5f to LullColors.warm.copy(alpha = 0f),
        0.5f to LullColors.cool.copy(alpha = 0f),
        1f to LullColors.deepCool.copy(alpha = 0.45f),
        start = top,
        end = bottom,
    )
    val stroke = Brush.linearGradient(
        0f to LullColors.hot,
        0.3f to LullColors.warm,
        0.5f to Color(red = 0.85f, green = 0.85f, blue = 0.85f),
        0.7f to LullColors.cool,
        1f to LullColors.deepCool,
        start = top,
        end = bottom,
    )
    val style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // `opacity` multiplies every layer, like assigning GraphicsContext.opacity in Swift.
    fun drawCurve(opacity: Float) {
        drawPath(path = area, brush = fill, alpha = opacity)
        drawGlow(line = line, brush = stroke, lineWidth = lineWidth, opacity = opacity)
        drawPath(path = line, brush = stroke, alpha = opacity, style = style)
    }

    if (progress == null) {
        drawCurve(1f)
        return
    }
    // The part of the night still ahead is drawn dimmer than what has already happened.
    drawCurve(0.3f)
    clipRect(left = 0f, top = 0f, right = (width * progress).toFloat(), bottom = height) {
        drawCurve(1f)
    }

    val now = point(progress)
    // Swift blurs an 18 pt circle at 70% white by 6 pt; a radial fade draws the same halo on every API level.
    val haloRadius = 18.dp.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.5f),
            0.5f to Color.White.copy(alpha = 0.3f),
            1f to Color.White.copy(alpha = 0f),
            center = now,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = now,
    )
    drawCircle(color = Color.White, radius = 6.dp.toPx(), center = now)
}

/**
 * The soft glow under the line: Swift draws the stroke again blurred by 8 pt at 60% opacity. Android draws a
 * blurred path through the platform paint's BlurMaskFilter, which hardware-accelerated canvases honour from
 * API 28. Below that the filter would be ignored, so a wider, fainter stroke stands in for the blur.
 */
private fun DrawScope.drawGlow(line: Path, brush: Brush, lineWidth: Float, opacity: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            brush.applyTo(size, paint, opacity * 0.6f)
            paint.style = PaintingStyle.Stroke
            paint.strokeWidth = lineWidth
            paint.strokeCap = StrokeCap.Round
            paint.strokeJoin = StrokeJoin.Round
            // Swift's blur radius is a Gaussian sigma; Skia turns a mask-filter radius r into sigma 0.57735·r + 0.5.
            val radius = (8.dp.toPx() - 0.5f) / 0.57735f
            paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(line, paint)
        }
    } else {
        drawPath(
            path = line,
            brush = brush,
            alpha = opacity * 0.3f,
            style = Stroke(width = lineWidth * 3, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Port of the static `NightCurve.level(at:in:)`, `ramp` and `blend` in NightCurve.swift. */
object NightCurveMath {
    /** Phase levels joined by short eased ramps, so the line reads as a curve rather than steps. */
    fun level(minute: Double, phases: List<Phase>): Double {
        var start = 0.0
        for ((index, phase) in phases.withIndex()) {
            val end = start + phase.minutes
            if (minute <= end || index == phases.size - 1) {
                if (index > 0) {
                    val ramp = ramp(phases[index - 1], phase)
                    if (ramp != null && minute - start < ramp) {
                        return blend(phases[index - 1].level, phase.level, 0.5 + (minute - start) / (2 * ramp))
                    }
                }
                if (index < phases.size - 1) {
                    val ramp = ramp(phase, phases[index + 1])
                    if (ramp != null && end - minute < ramp) {
                        return blend(phase.level, phases[index + 1].level, 0.5 - (end - minute) / (2 * ramp))
                    }
                }
                return phase.level
            }
            start = end
        }
        return 0.0
    }

    private fun ramp(a: Phase, b: Phase): Double? {
        val width = minOf(40.0, a.minutes.toDouble() / 2, b.minutes.toDouble() / 2)
        return if (width > 0) width else null
    }

    private fun blend(from: Double, to: Double, t: Double): Double {
        val eased = t * t * (3 - 2 * t)
        return from + (to - from) * eased
    }
}

/** Curve with bedtime and wake labels underneath. */
@Composable
fun NightTimeline(
    schedule: Schedule,
    startMinute: Int? = null,
    progress: Double? = null,
    selectionId: String? = null,
    height: Dp = 150.dp,
    modifier: Modifier = Modifier,
) {
    val start = startMinute ?: schedule.bedtime
    val end = start + schedule.totalMinutes
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "${schedule.name}, ${Format.clock(start)} to ${Format.clock(end)}"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NightCurve(
            phases = schedule.phases,
            progress = progress,
            selectionId = selectionId,
            modifier = Modifier.fillMaxWidth().height(height),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimelineLabel(Format.clock(start), Icons.Filled.Bedtime)
            Spacer(Modifier.weight(1f))
            TimelineText(Format.duration(schedule.totalMinutes))
            Spacer(Modifier.weight(1f))
            TimelineLabel(Format.clock(end), Icons.Filled.WbSunny)
        }
    }
}

@Composable
private fun TimelineLabel(text: String, icon: ImageVector) {
    // The Swift symbols are drawn at a 10 pt font size.
    val iconSize = with(LocalDensity.current) { 10.sp.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = LullColors.secondary, modifier = Modifier.size(iconSize))
        TimelineText(text)
    }
}

@Composable
private fun TimelineText(text: String) {
    Text(
        text,
        color = LullColors.secondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        style = MonospacedDigits,
    )
}
