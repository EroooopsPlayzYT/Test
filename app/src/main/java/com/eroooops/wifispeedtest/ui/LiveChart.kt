package com.eroooops.wifispeedtest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * A scrolling trace of the last few seconds of throughput.
 *
 * The single averaged number at the end of a test hides everything interesting:
 * a link that ramps and holds looks identical to one that sawtooths between
 * full speed and nothing. The shape is the diagnosis.
 */
@Composable
fun LiveChart(
    samples: List<Double>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillBrush = Brush.verticalGradient(
        listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0f)),
    )
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Headroom above the peak so the trace never touches the ceiling.
            val peak = max(samples.maxOrNull() ?: 0.0, 0.001) * 1.15

            repeat(4) { index ->
                val y = size.height * index / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            if (samples.size < 2) return@Canvas

            val step = size.width / (samples.size - 1).toFloat()
            fun pointAt(index: Int): Offset {
                val value = samples[index].coerceIn(0.0, peak)
                val y = size.height - (value / peak * size.height).toFloat()
                return Offset(index * step, y)
            }

            val line = Path().apply {
                moveTo(pointAt(0).x, pointAt(0).y)
                for (i in 1 until samples.size) {
                    val point = pointAt(i)
                    lineTo(point.x, point.y)
                }
            }

            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }

            drawPath(area, brush = fillBrush)
            drawPath(
                line,
                color = lineColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )
        }

        samples.maxOrNull()?.takeIf { it > 0 }?.let { peak ->
            Text(
                text = "peak ${formatSpeed(peak)} Mbps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}
