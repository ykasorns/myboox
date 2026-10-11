package com.ykasorns.ebookreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.ykasorns.ebookreader.data.PageTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * State of one page turn, shared by the EPUB and PDF readers.
 *
 * A turn always runs "forward" geometrically: the [front] page lifts off the spine side and
 * reveals [under]. Turning back is the same motion played in reverse (the previous page is
 * the front, the current page is under it), which keeps the drawing code to one case.
 *
 * Progress is expressed by the finger x position [fx]: at the outer page edge the page lies flat,
 * at `spine - pageWidth` it has turned over completely.
 */
class PageCurlState(private val scope: CoroutineScope) {
    var active by mutableStateOf(false)
        private set
    var front by mutableStateOf<Bitmap?>(null)
        private set
    var under by mutableStateOf<Bitmap?>(null)
        private set
    var fx by mutableFloatStateOf(0f)
        private set
    var fy by mutableFloatStateOf(0f)
        private set
    var grabY by mutableFloatStateOf(0f)
        private set

    var mode = PageTurn.CURL
    var spread = false
    var paperColor = android.graphics.Color.WHITE

    /** Size of the overlay in pixels, set when it is laid out. */
    var width = 0f
    var height = 0f

    val busy: Boolean get() = active
    private var forward = true
    private var startX = 0f
    private var animation: Job? = null
    private val renderer = PageCurlRenderer()

    private val spine get() = if (spread) width / 2f else 0f
    private val pageWidth get() = if (spread) width / 2f else width
    private val flatX get() = spine + pageWidth
    private val turnedX get() = spine - pageWidth

    /** Start a turn. [x]/[y] is where the finger went down (or a synthetic grab point). */
    fun begin(forward: Boolean, x: Float, y: Float, front: Bitmap?, under: Bitmap?) {
        animation?.cancel()
        this.forward = forward
        this.front = front
        this.under = under
        startX = x
        grabY = y
        fy = y
        fx = if (forward) flatX else turnedX
        active = true
    }

    fun setBitmaps(front: Bitmap?, under: Bitmap?) {
        this.front = front
        this.under = under
    }

    /** Follow the finger. The whole drag across the page maps onto one full turn. */
    fun drag(x: Float, y: Float) {
        if (!active) return
        fy = y
        fx = if (forward) {
            val reach = (startX - spine).coerceAtLeast(pageWidth * 0.5f)
            flatX - (startX - x).coerceAtLeast(0f) * (2f * pageWidth) / reach
        } else {
            val reach = (flatX - startX).coerceAtLeast(pageWidth * 0.5f)
            turnedX + (x - startX).coerceAtLeast(0f) * (2f * pageWidth) / reach
        }.coerceIn(turnedX, flatX)
    }

    /** 0 = page flat, 1 = page fully turned. */
    private val turned: Float get() = ((flatX - fx) / (flatX - turnedX)).coerceIn(0f, 1f)

    /**
     * Finish a drag. [onEnd] reports whether the reader moved to the other page (true) or the
     * page fell back. [velocityX] is in pixels per second; a quick flick decides on its own.
     */
    fun release(velocityX: Float, onEnd: (navigated: Boolean) -> Unit) {
        if (!active) return
        val flick = abs(velocityX) > 900f
        // "turn" means the page ends up turned over (lying on the spine side).
        val turn = when {
            flick -> velocityX < 0
            forward -> turned > 0.25f
            else -> turned > 0.75f
        }
        animateTo(turn, onEnd)
    }

    /** Turn without a finger (tap zones, volume keys). */
    fun auto(forward: Boolean, front: Bitmap?, under: Bitmap?, onEnd: (navigated: Boolean) -> Unit) {
        begin(forward, if (forward) flatX else turnedX, height * 0.86f, front, under)
        animateTo(turn = forward, onEnd = onEnd, lift = true)
    }

    private fun animateTo(turn: Boolean, onEnd: (Boolean) -> Unit, lift: Boolean = false) {
        animation?.cancel()
        val targetX = if (turn) turnedX else flatX
        val startFx = fx
        val startFy = fy
        val distance = abs(targetX - startFx) / (flatX - turnedX).coerceAtLeast(1f)
        val duration = if (mode == PageTurn.NONE) 0 else (180 + 420 * distance).toInt()
        animation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(
                1f,
                tween(duration, easing = if (lift) FastOutSlowInEasing else LinearOutSlowInEasing),
            ) {
                fx = startFx + (targetX - startFx) * value
                // An automatic turn lifts the corner a little on the way, like a real page.
                fy = if (lift) grabY - sin(PI * value).toFloat() * height * 0.12f else startFy + (grabY - startFy) * value
            }
            onEnd(if (forward) turn else !turn)
        }
    }

    /** Remove the overlay; call once the real view underneath shows the right page. */
    fun end() {
        animation?.cancel()
        active = false
        front = null
        under = null
    }

    internal fun draw(canvas: NativeCanvas) {
        if (!active || width <= 0f || height <= 0f) return
        when (mode) {
            PageTurn.CURL -> renderer.drawCurl(canvas, width, height, front, under, fx, fy, grabY, spread, paperColor)
            else -> renderer.drawSlide(canvas, width, height, front, under, turned, paperColor)
        }
    }
}

/** Draws [state] on top of the reader while a page is turning. Purely visual: no touch handling. */
@Composable
fun PageCurlOverlay(state: PageCurlState, modifier: Modifier = Modifier) {
    // Always present (it draws nothing when idle) so the page size is known before a turn starts.
    Canvas(modifier.fillMaxSize()) {
        state.width = size.width
        state.height = size.height
        if (!state.active) return@Canvas
        // Reading these keeps the overlay redrawing while the page moves.
        state.fx
        state.fy
        state.front
        state.under
        drawIntoCanvas { state.draw(it.nativeCanvas) }
    }
}

/** Remembers a [PageCurlState] for the given coroutine scope. */
@Composable
fun rememberPageCurlState(scope: CoroutineScope): PageCurlState = remember(scope) { PageCurlState(scope) }

/**
 * Paper-fold drawing. The page is folded along the perpendicular bisector of the grabbed point
 * and the finger: everything past that line is reflected over it and shows the back of the
 * paper, and the next page shows through where the paper has lifted.
 */
class PageCurlRenderer {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x1F000000
    }
    private val reflection = Matrix()
    private val spineMirror = Matrix()
    private val path = Path()
    private val screen = RectF()

    private fun drawPage(canvas: NativeCanvas, bitmap: Bitmap?, w: Float, h: Float, paper: Int) {
        screen.set(0f, 0f, w, h)
        if (bitmap != null && !bitmap.isRecycled) {
            canvas.drawBitmap(bitmap, null, screen, bitmapPaint)
        } else {
            fillPaint.color = paper
            canvas.drawRect(screen, fillPaint)
        }
    }

    fun drawSlide(canvas: NativeCanvas, w: Float, h: Float, front: Bitmap?, under: Bitmap?, turned: Float, paper: Int) {
        canvas.save()
        canvas.translate((1f - turned) * w, 0f)
        drawPage(canvas, under, w, h, paper)
        canvas.restore()
        canvas.save()
        canvas.translate(-turned * w, 0f)
        drawPage(canvas, front, w, h, paper)
        canvas.restore()
    }

    fun drawCurl(
        canvas: NativeCanvas,
        w: Float,
        h: Float,
        front: Bitmap?,
        under: Bitmap?,
        fingerX: Float,
        fingerY: Float,
        grabY: Float,
        spread: Boolean,
        paper: Int,
    ) {
        val spine = if (spread) w / 2f else 0f
        val pw = if (spread) w / 2f else w

        // Grabbed near the top or bottom: lift that corner. Otherwise lift the edge and keep the
        // fold close to vertical, because tall phone screens exaggerate any finger drift.
        val gx = spine + pw
        val gy: Float
        var fx = fingerX
        var fy: Float
        when {
            grabY > h * 0.72f -> {
                gy = h
                fy = min(h, h + (fingerY - grabY))
            }
            grabY < h * 0.28f -> {
                gy = 0f
                fy = maxOf(0f, fingerY - grabY)
            }
            else -> {
                gy = grabY
                fy = grabY + (fingerY - grabY) * 0.2f
            }
        }
        // The page stays attached at the spine: the corner can be no farther from the spine than
        // the page is wide (measured from both ends of the spine).
        run {
            val dx = fx - spine
            val dy = fy - gy
            val d = hypot(dx, dy)
            if (d > pw) {
                fx = spine + dx * pw / d
                fy = gy + dy * pw / d
            }
            val oy = h - gy
            val limit = hypot(pw, abs(gy - oy))
            val ex = fx - spine
            val ey = fy - oy
            val e = hypot(ex, ey)
            if (e > limit) {
                fx = spine + ex * limit / e
                fy = oy + ey * limit / e
            }
        }

        val vx = gx - fx
        val vy = gy - fy
        val len = hypot(vx, vy)
        if (len < 0.5f) {
            drawPage(canvas, front, w, h, paper)
            return
        }
        val nx = vx / len
        val ny = vy / len
        val mx = (gx + fx) / 2f
        val my = (gy + fy) / 2f

        val page = floatArrayOf(spine, 0f, spine + pw, 0f, spine + pw, h, spine, h)
        val flat = clip(page, mx, my, nx, ny, keepFolded = false)
        val folded = clip(page, mx, my, nx, ny, keepFolded = true)

        // Reflection across the fold line: R = I - 2nnᵀ about the point M.
        val a = 1f - 2f * nx * nx
        val b = -2f * nx * ny
        val d = 1f - 2f * ny * ny
        val k = 2f * (mx * nx + my * ny)
        reflection.setValues(floatArrayOf(a, b, k * nx, b, d, k * ny, 0f, 0f, 1f))
        val flap = folded.copyOf()
        reflection.mapPoints(flap)

        val shadowWidth = min(len * 0.35f, 60f)

        // The other half of a two-page spread stays as it is until the flap lands on it.
        if (spread) drawPage(canvas, front, w, h, paper)

        // Next page, revealed where the paper has lifted, with a soft shadow along the fold.
        if (folded.size >= 6) {
            canvas.save()
            canvas.clipPath(polygon(folded))
            drawPage(canvas, under, w, h, paper)
            shade(canvas, mx, my, mx + nx * shadowWidth, my + ny * shadowWidth, intArrayOf(0x59000000, 0))
            canvas.restore()
        }

        // The part of the page that is still flat, darkened slightly under the flap.
        if (flat.size >= 6) {
            canvas.save()
            canvas.clipPath(polygon(flat))
            drawPage(canvas, front, w, h, paper)
            shade(canvas, mx, my, mx - nx * shadowWidth * 0.6f, my - ny * shadowWidth * 0.6f, intArrayOf(0x33000000, 0))
            canvas.restore()
        }

        // The flap: the back of the paper. On a single page it shows the page's own text faintly
        // through the paper; in a spread it is the left page of the next spread.
        if (flap.size >= 6) {
            val flapPath = polygon(flap)
            canvas.save()
            canvas.clipPath(flapPath)
            canvas.save()
            canvas.concat(reflection)
            if (spread) {
                spineMirror.setValues(floatArrayOf(-1f, 0f, 2f * spine, 0f, 1f, 0f, 0f, 0f, 1f))
                canvas.concat(spineMirror)
                drawPage(canvas, under, w, h, paper)
            } else {
                drawPage(canvas, front, w, h, paper)
                fillPaint.color = paper
                fillPaint.alpha = 220
                canvas.drawRect(spine, 0f, spine + pw, h, fillPaint)
                fillPaint.alpha = 255
            }
            canvas.restore()
            val flapWidth = min(len * 0.5f, 140f)
            shade(
                canvas, mx, my, mx - nx * flapWidth, my - ny * flapWidth,
                intArrayOf(0x2E000000, 0x1FFFFFFF, 0x0F000000), floatArrayOf(0f, 0.35f, 1f),
            )
            canvas.restore()
            canvas.drawPath(flapPath, edgePaint)
        }
    }

    private fun shade(
        canvas: NativeCanvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        colors: IntArray,
        positions: FloatArray? = null,
    ) {
        if (abs(x1 - x0) + abs(y1 - y0) < 0.5f) return
        shadePaint.shader = LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
        canvas.drawPaint(shadePaint)
        shadePaint.shader = null
    }

    private fun polygon(points: FloatArray): Path {
        path.reset()
        path.moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        path.close()
        return Path(path)
    }

    /** Clips a convex polygon (x,y pairs) to one side of the fold line through (mx,my) with normal n. */
    private fun clip(poly: FloatArray, mx: Float, my: Float, nx: Float, ny: Float, keepFolded: Boolean): FloatArray {
        val sign = if (keepFolded) -1f else 1f
        val out = ArrayList<Float>(poly.size + 4)
        val count = poly.size / 2
        for (i in 0 until count) {
            val ax = poly[2 * i]
            val ay = poly[2 * i + 1]
            val bx = poly[(2 * i + 2) % poly.size]
            val by = poly[(2 * i + 3) % poly.size]
            val fa = sign * ((ax - mx) * nx + (ay - my) * ny)
            val fb = sign * ((bx - mx) * nx + (by - my) * ny)
            if (fa <= 0f) {
                out += ax
                out += ay
            }
            if ((fa < 0f && fb > 0f) || (fa > 0f && fb < 0f)) {
                val t = fa / (fa - fb)
                out += ax + (bx - ax) * t
                out += ay + (by - ay) * t
            }
        }
        return out.toFloatArray()
    }
}
