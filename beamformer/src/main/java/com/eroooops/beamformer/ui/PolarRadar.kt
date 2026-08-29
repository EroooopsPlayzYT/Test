package com.eroooops.beamformer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eroooops.beamformer.ui.theme.Amber
import com.eroooops.beamformer.ui.theme.Mint
import com.eroooops.beamformer.ui.theme.Sky
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The steered-response display: how strongly the array hears each bearing.
 *
 * Drawn as a half disc because that is genuinely all a two-microphone array can
 * report. Every direction at the same angle to the line through the capsules
 * produces identical arrival times, so a bearing is a cone about that line, and
 * front cannot be told from back. Showing a full circle would imply a resolution
 * the hardware does not have.
 *
 * Zero degrees is off the top edge of the phone, 180 off the bottom, 90 broadside.
 * Tap or drag anywhere to steer the beam there.
 */
@Composable
fun PolarRadar(
    surface: List<Float>,
    angles: List<Float>,
    steerDeg: Double,
    doaDeg: Double?,
    doaConfidence: Double,
    simulated: Boolean,
    directionAvailable: Boolean,
    modifier: Modifier = Modifier,
    onSteer: (Double) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val steerCallback = rememberUpdatedState(onSteer)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.75f)
            .clip(RoundedCornerShape(20.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.75f)
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        bearingAt(position, size.width.toFloat(), size.height.toFloat())
                            ?.let { steerCallback.value(it) }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        bearingAt(change.position, size.width.toFloat(), size.height.toFloat())
                            ?.let { steerCallback.value(it) }
                    }
                },
        ) {
            drawRect(surfaceColor)

            val origin = Offset(size.width / 2f, size.height - PADDING)
            val radius = minOf(size.width / 2f - PADDING, size.height - 2 * PADDING)

            drawGrid(origin, radius, gridColor, labelColor, measurer)

            if (surface.size == angles.size && surface.size >= 3) {
                drawResponse(origin, radius, surface, angles)
            }

            drawSteerWedge(origin, radius, steerDeg)

            if (directionAvailable && doaDeg != null && doaConfidence > 0.05) {
                drawBearingNeedle(origin, radius, doaDeg, doaConfidence)
            }
        }

        if (simulated) {
            Text(
                text = "SIMULATED",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }

        if (!directionAvailable) {
            Text(
                text = "no direction available",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}

private const val PADDING = 28f

/** Converts a touch point to a bearing, or null if it landed below the array. */
private fun bearingAt(position: Offset, width: Float, height: Float): Double? {
    val origin = Offset(width / 2f, height - PADDING)
    val dx = (position.x - origin.x).toDouble()
    val dy = (origin.y - position.y).toDouble()
    if (hypot(dx, dy) < 12.0) return null
    val degrees = Math.toDegrees(atan2(dy, dx))
    return degrees.coerceIn(0.0, 180.0)
}

/** Screen position for a bearing at a given fraction of full radius. */
private fun pointFor(origin: Offset, radius: Float, degrees: Double, fraction: Float): Offset {
    val radians = degrees * PI / 180.0
    return Offset(
        origin.x + (cos(radians) * radius * fraction).toFloat(),
        origin.y - (sin(radians) * radius * fraction).toFloat(),
    )
}

private fun DrawScope.drawGrid(
    origin: Offset,
    radius: Float,
    gridColor: Color,
    labelColor: Color,
    measurer: TextMeasurer,
) {
    for (ring in 1..3) {
        val fraction = ring / 3f
        drawArc(
            color = gridColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(origin.x - radius * fraction, origin.y - radius * fraction),
            size = Size(radius * 2 * fraction, radius * 2 * fraction),
            style = Stroke(width = 1f),
        )
    }

    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    for (degrees in 0..180 step 30) {
        val end = pointFor(origin, radius, degrees.toDouble(), 1f)
        drawLine(gridColor, origin, end, strokeWidth = 1f)

        val label = "$degrees°"
        val layout = measurer.measure(label, style)
        val labelPoint = pointFor(origin, radius + 16f, degrees.toDouble(), 1f)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                labelPoint.x - layout.size.width / 2f,
                labelPoint.y - layout.size.height / 2f,
            ),
        )
    }
}

/** The response curve itself, filled from the array outward. */
private fun DrawScope.drawResponse(
    origin: Offset,
    radius: Float,
    surface: List<Float>,
    angles: List<Float>,
) {
    val path = Path()
    path.moveTo(origin.x, origin.y)
    for (index in angles.indices) {
        val fraction = surface[index].coerceIn(0f, 1f)
        val point = pointFor(origin, radius, angles[index].toDouble(), fraction)
        path.lineTo(point.x, point.y)
    }
    path.close()

    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            listOf(Sky.copy(alpha = 0.55f), Sky.copy(alpha = 0.12f)),
            startY = origin.y - radius,
            endY = origin.y,
        ),
    )

    val outline = Path()
    var started = false
    for (index in angles.indices) {
        val point = pointFor(origin, radius, angles[index].toDouble(), surface[index].coerceIn(0f, 1f))
        if (!started) {
            outline.moveTo(point.x, point.y)
            started = true
        } else {
            outline.lineTo(point.x, point.y)
        }
    }
    drawPath(outline, color = Sky, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

/** Where the beam is pointed, as a wedge rather than a line — it has a width. */
private fun DrawScope.drawSteerWedge(origin: Offset, radius: Float, steerDeg: Double) {
    val halfWidth = 9.0
    val wedge = Path()
    wedge.moveTo(origin.x, origin.y)
    var angle = steerDeg - halfWidth
    while (angle <= steerDeg + halfWidth) {
        val point = pointFor(origin, radius, angle.coerceIn(0.0, 180.0), 1f)
        wedge.lineTo(point.x, point.y)
        angle += 1.5
    }
    wedge.close()
    drawPath(wedge, color = Mint.copy(alpha = 0.16f))

    val tip = pointFor(origin, radius, steerDeg, 1f)
    drawLine(Mint, origin, tip, strokeWidth = 2.5f, cap = StrokeCap.Round)
    drawCircle(Mint, radius = 6f, center = tip)
}

/**
 * The live bearing estimate. Its opacity is the confidence, so a flat scan
 * surface — silence, or diffuse noise with no direction in it — fades the
 * needle out instead of parking it somewhere arbitrary and looking certain.
 */
private fun DrawScope.drawBearingNeedle(
    origin: Offset,
    radius: Float,
    doaDeg: Double,
    confidence: Double,
) {
    val alpha = (0.25 + 0.75 * confidence).coerceIn(0.0, 1.0).toFloat()
    val tip = pointFor(origin, radius * 0.94f, doaDeg, 1f)
    drawLine(
        color = Amber.copy(alpha = alpha),
        start = origin,
        end = tip,
        strokeWidth = 3f,
        cap = StrokeCap.Round,
    )
    drawCircle(Amber.copy(alpha = alpha), radius = 5f, center = tip)
}
