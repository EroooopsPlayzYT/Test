package com.eroooops.wifispeedtest.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val GAUGE_MAX_MBPS = 1000.0

/**
 * Maps a speed onto the dial logarithmically, so 5 Mbps and 500 Mbps are both
 * readable positions instead of everything slow bunching up near zero.
 */
private fun gaugeFraction(mbps: Double): Float {
    val clamped = mbps.coerceIn(0.0, GAUGE_MAX_MBPS)
    return (ln(1 + clamped) / ln(1 + GAUGE_MAX_MBPS)).toFloat()
}

@Composable
fun SpeedGauge(
    mbps: Double,
    caption: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val target = gaugeFraction(mbps)
    val animated by animateFloatAsState(targetValue = target, label = "gauge")

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val arcBrush = Brush.sweepGradient(
        0f to MaterialTheme.colorScheme.secondary,
        0.5f to MaterialTheme.colorScheme.primary,
        1f to MaterialTheme.colorScheme.tertiary,
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(20.dp)
        ) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (animated > 0.001f) {
                drawArc(
                    brush = arcBrush,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // Decade ticks: 1, 10, 100, 1000 Mbps.
            val radius = arcSize.minDimension / 2
            val centre = Offset(size.width / 2, size.height / 2)
            listOf(1.0, 10.0, 100.0, 1000.0).forEach { mark ->
                val angle = Math.toRadians(
                    (START_ANGLE + SWEEP_ANGLE * gaugeFraction(mark)).toDouble()
                )
                val outer = radius + stroke * 0.75f
                val inner = radius + stroke * 0.2f
                drawLine(
                    color = tickColor,
                    start = Offset(
                        centre.x + (inner * cos(angle)).toFloat(),
                        centre.y + (inner * sin(angle)).toFloat(),
                    ),
                    end = Offset(
                        centre.x + (outer * cos(angle)).toFloat(),
                        centre.y + (outer * sin(angle)).toFloat(),
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatSpeed(mbps),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = caption.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

/** Signal strength as four bars, drawn rather than pulled from an icon font. */
@Composable
fun SignalBars(
    bars: Int?,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Canvas(modifier = modifier) {
        val count = 4
        val gap = size.width * 0.12f / (count - 1)
        val barWidth = (size.width - gap * (count - 1)) / count
        for (index in 0 until count) {
            val heightFactor = 0.35f + 0.65f * (index + 1) / count
            val barHeight = size.height * heightFactor
            drawRoundRect(
                color = if (bars != null && index < bars) activeColor else inactiveColor,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3),
            )
        }
    }
}
