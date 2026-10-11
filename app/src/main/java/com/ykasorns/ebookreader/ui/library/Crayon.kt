package com.ykasorns.ebookreader.ui.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Crayon colours for the bookshelf, in light and dark variants. */
object CrayonPalette {
    val light = listOf(Color(0xFFC4623A), Color(0xFFD9A441), Color(0xFF4F6D8F), Color(0xFF6E8B4E))
    val dark = listOf(Color(0xFFE08A63), Color(0xFFE8C06A), Color(0xFF8FB0D6), Color(0xFFA3C281))
    val woodLight = Color(0xFF8A6A4E)
    val woodDark = Color(0xFFB89A7E)
}

/**
 * A hand-drawn crayon stroke through [points]: a few wobbly passes, each broken by tiny
 * irregular gaps, which together give the waxy, grainy look of crayon on paper.
 * Sizes are in dp and scaled by the draw scope's density. The same [seed] always draws the
 * same line, so shelves do not shimmer between frames.
 */
fun DrawScope.crayon(points: List<Offset>, color: Color, widthDp: Float, seed: Int, passes: Int = 3) {
    if (points.size < 2) return
    val unit = density
    val random = Random(seed)
    val width = widthDp * unit
    repeat(passes) {
        val phase1 = random.nextFloat() * 2f * PI.toFloat()
        val phase2 = random.nextFloat() * 2f * PI.toFloat()
        val amplitude = width * 0.35f + 0.6f * unit
        val path = Path()
        var first = true
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length < 0.01f) continue
            val steps = max(2, (length / (6f * unit)).roundToInt())
            val nx = -(b.y - a.y) / length
            val ny = (b.x - a.x) / length
            for (k in (if (i == 0) 0 else 1)..steps) {
                val t = k.toFloat() / steps
                val along = t * length / unit
                val wobble = (sin(along / 23f + phase1) + 0.5f * sin(along / 9f + phase2)) * amplitude * 0.6f +
                    (random.nextFloat() - 0.5f) * amplitude * 0.5f
                val x = a.x + (b.x - a.x) * t + nx * wobble
                val y = a.y + (b.y - a.y) * t + ny * wobble
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
        }
        val dashes = floatArrayOf(
            (2f + random.nextFloat() * 7f) * unit,
            (0.6f + random.nextFloat() * 1.6f) * unit,
            (1f + random.nextFloat() * 4f) * unit,
            (0.4f + random.nextFloat() * 1.2f) * unit,
        )
        drawPath(
            path = path,
            color = color,
            alpha = 0.45f + random.nextFloat() * 0.3f,
            style = Stroke(
                width = width * (0.55f + random.nextFloat() * 0.45f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(dashes, random.nextFloat() * 10f * unit),
            ),
        )
    }
}

/** Loose diagonal crayon hatching inside the box from [topLeft] to [bottomRight]. */
fun DrawScope.crayonHatch(topLeft: Offset, bottomRight: Offset, color: Color, seed: Int, stepDp: Float = 7f) {
    val random = Random(seed)
    val height = bottomRight.y - topLeft.y
    var x = topLeft.x - height
    var n = 0
    while (x < bottomRight.x) {
        val start = Offset(maxOf(x, topLeft.x), bottomRight.y - maxOf(0f, topLeft.x - x))
        val endX = x + height
        val end = Offset(minOf(endX, bottomRight.x), topLeft.y + maxOf(0f, endX - bottomRight.x))
        if (end.x - start.x > 2f * density) crayon(listOf(start, end), color, 1.3f, seed * 31 + n, passes = 1)
        x += (stepDp + random.nextFloat() * 3f) * density
        n++
    }
}

/** A loose crayon spiral: the abstract doodle that fills empty shelf space. */
fun DrawScope.crayonSpiral(center: Offset, radius: Float, color: Color, seed: Int) {
    val points = ArrayList<Offset>()
    var t = 0f
    while (t < 14f) {
        val r = radius * t / 14f
        points += Offset(center.x + cos(t) * r, center.y + sin(t) * r)
        t += 0.35f
    }
    crayon(points, color, 2.2f, seed, passes = 2)
}
