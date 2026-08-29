package com.eroooops.beamformer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private const val HISTORY_COLUMNS = 220

/**
 * Bearing against time: every scan becomes one column, oldest at the left.
 *
 * A single needle shows where a sound is now; this shows where it has been. A
 * talker crossing the room draws a diagonal, a fixed machine draws a straight
 * line, and a room with nothing directional in it draws mush — which is the
 * quickest way to tell a real bearing from a hopeful one.
 *
 * Held as a pixel buffer rather than as thousands of rectangles: one new column
 * per frame is a few hundred bytes of work, where redrawing a grid of cells
 * would be tens of thousands of draw calls a second.
 */
@Composable
fun BearingWaterfall(
    surface: List<Float>,
    angles: List<Float>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp,
) {
    val rows = angles.size.coerceAtLeast(1)
    val state = remember(rows) { WaterfallBuffer(HISTORY_COLUMNS, rows) }
    val backgroundColor = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (surface.size == rows) state.push(surface)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(backgroundColor)
            val image = state.image() ?: return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(state.columns, state.rows),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Low,
            )
            drawAxis(labelColor)
        }

        Text(
            text = "0°",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
        Text(
            text = "180°",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
    }
}

private fun DrawScope.drawAxis(color: Color) {
    // A line at broadside, the one bearing worth having a reference for.
    val y = size.height / 2f
    drawLine(
        color = color.copy(alpha = 0.3f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
    )
}

/**
 * Scrolling pixel store for the waterfall.
 *
 * Row 0 is 180 degrees and the last row is 0, so the display reads the same way
 * up as the polar plot above it.
 */
private class WaterfallBuffer(val columns: Int, val rows: Int) {

    private val pixels = IntArray(columns * rows) { BACKGROUND }
    private val bitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    private var dirty = true

    fun push(surface: List<Float>) {
        for (row in 0 until rows) {
            val base = row * columns
            System.arraycopy(pixels, base + 1, pixels, base, columns - 1)
            // Flip so that low row index is a high bearing.
            val value = surface[rows - 1 - row].coerceIn(0f, 1f)
            pixels[base + columns - 1] = colourFor(value)
        }
        dirty = true
    }

    fun image(): androidx.compose.ui.graphics.ImageBitmap? {
        val target = bitmap ?: return null
        if (dirty) {
            target.setPixels(pixels, 0, columns, 0, 0, columns, rows)
            dirty = false
        }
        return target.asImageBitmap()
    }

    /**
     * Dark to blue to amber. Perceptually monotonic enough that a brighter,
     * warmer band always means a stronger response, which is the only property
     * the reader needs from it.
     */
    private fun colourFor(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val r: Int
        val g: Int
        val b: Int
        if (v < 0.6f) {
            val t = v / 0.6f
            r = (11 + (56 - 11) * t).toInt()
            g = (18 + (189 - 18) * t).toInt()
            b = (32 + (248 - 32) * t).toInt()
        } else {
            val t = (v - 0.6f) / 0.4f
            r = (56 + (251 - 56) * t).toInt()
            g = (189 + (191 - 189) * t).toInt()
            b = (248 + (36 - 248) * t).toInt()
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val BACKGROUND = (0xFF shl 24) or (11 shl 16) or (18 shl 8) or 32
    }
}
