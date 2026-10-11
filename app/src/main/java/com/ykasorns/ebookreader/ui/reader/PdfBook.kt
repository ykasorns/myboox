package com.ykasorns.ebookreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** An open PDF. PdfRenderer allows one page at a time, so all page access is serialised. */
class PdfBook private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val contentBounds = HashMap<Int, RectF>()
    private val fullPage = RectF(0f, 0f, 1f, 1f)

    /** Height / width of a page as it will be shown (after trimming margins when [crop] is on). */
    suspend fun aspect(index: Int, crop: Boolean): Float = access(index) { page, w, h ->
        val area = if (crop) bounds(index, page) else fullPage
        (area.height() * h) / (area.width() * w).coerceAtLeast(1f)
    } ?: 1.414f

    /** Renders a page to fit inside [maxWidth] x [maxHeight] pixels. */
    suspend fun render(index: Int, maxWidth: Int, maxHeight: Int, crop: Boolean): Bitmap? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        val key = "$index:$maxWidth:$maxHeight:$crop"
        cache.get(key)?.let { return it }
        return access(index) { page, w, h ->
            cache.get(key) ?: run {
                val area = if (crop) bounds(index, page) else fullPage
                val areaW = area.width() * w
                val areaH = area.height() * h
                var scale = min(maxWidth / areaW, maxHeight / areaH)
                val longest = maxOf(areaW, areaH) * scale
                if (longest > MAX_SIDE) scale *= MAX_SIDE / longest
                val bitmap = Bitmap.createBitmap(
                    (areaW * scale).roundToInt().coerceAtLeast(1),
                    (areaH * scale).roundToInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-area.left * w * scale, -area.top * h * scale)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    /**
     * Draws one or two pages onto a screen-sized image, the way the paged reader shows them.
     * Two pages meet at the middle like an open book. [padding] keeps pages out of the cutout.
     */
    suspend fun renderSpread(
        pages: List<Int>,
        width: Int,
        height: Int,
        padding: Rect,
        crop: Boolean,
        background: Int,
        filter: ColorFilter?,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val areaW = width - padding.left - padding.right
        val areaH = height - padding.top - padding.bottom
        val slotW = areaW / pages.size.coerceAtLeast(1)
        val images = pages.map { render(it, slotW, areaH, crop) }
        return withContext(Dispatchers.Default) {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.eraseColor(background)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter }
            images.forEachIndexed { i, image ->
                if (image == null) return@forEachIndexed
                val top = padding.top + (areaH - image.height) / 2f
                val left = when {
                    pages.size == 1 -> padding.left + (areaW - image.width) / 2f
                    i == 0 -> padding.left + slotW - image.width.toFloat()
                    else -> padding.left + slotW.toFloat()
                }
                canvas.drawBitmap(image, left, top, paint)
            }
            out
        }
    }

    /** Runs [block] with page [index] open (width/height in points), or returns null once closed. */
    private suspend fun <T> access(index: Int, block: (page: PdfRenderer.Page, width: Float, height: Float) -> T): T? {
        if (index !in 0 until pageCount) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                if (closed) {
                    null
                } else {
                    renderer.openPage(index).use { page -> block(page, page.width.toFloat(), page.height.toFloat()) }
                }
            }
        }
    }

    /** Bounding box of the printed content, as fractions of the page, with a little breathing room. */
    private fun bounds(index: Int, page: PdfRenderer.Page): RectF {
        contentBounds[index]?.let { return it }
        val w = 140
        val h = (w * page.height.toFloat() / page.width).roundToInt().coerceIn(1, 600)
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        probe.eraseColor(Color.WHITE)
        page.render(probe, null, Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val pixels = IntArray(w * h)
        probe.getPixels(pixels, 0, w, 0, 0, w, h)
        probe.recycle()
        val rows = IntArray(h)
        val cols = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (Color.red(p) < 225 || Color.green(p) < 225 || Color.blue(p) < 225) {
                    rows[y]++
                    cols[x]++
                }
            }
        }
        // Ignore specks: a row/column counts only with at least two inked pixels.
        val top = rows.indexOfFirst { it >= 2 }
        val bottom = rows.indexOfLast { it >= 2 }
        val left = cols.indexOfFirst { it >= 2 }
        val right = cols.indexOfLast { it >= 2 }
        val result = if (top < 0 || left < 0) {
            fullPage
        } else {
            val pad = 0.025f
            var l = (left.toFloat() / w - pad).coerceAtLeast(0f)
            var r = ((right + 1f) / w + pad).coerceAtMost(1f)
            var t = (top.toFloat() / h - pad).coerceAtLeast(0f)
            var b = ((bottom + 1f) / h + pad).coerceAtMost(1f)
            // Never zoom in on a nearly empty page by more than 2x.
            if (r - l < 0.5f) {
                val c = (l + r) / 2f
                l = (c - 0.25f).coerceIn(0f, 0.5f)
                r = l + 0.5f
            }
            if (b - t < 0.5f) {
                val c = (t + b) / 2f
                t = (c - 0.25f).coerceIn(0f, 0.5f)
                b = t + 0.5f
            }
            RectF(l, t, r, b)
        }
        contentBounds[index] = result
        return result
    }

    fun closeAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                cache.evictAll()
            }
        }
    }

    companion object {
        private const val MAX_SIDE = 3200f

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()

        fun open(file: File): PdfBook {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfBook(descriptor, PdfRenderer(descriptor))
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}
