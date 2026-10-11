package com.ykasorns.ebookreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An open PDF. PdfRenderer allows one page at a time, so rendering is serialised. */
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

    /** Renders a page to fit in [maxWidth] x [maxHeight] pixels, slightly oversampled for zooming. */
    suspend fun render(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (index !in 0 until pageCount || maxWidth <= 0 || maxHeight <= 0) return null
        val key = "$index:$maxWidth:$maxHeight"
        cache.get(key)?.let { return it }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                if (closed) return@withContext null
                cache.get(key) ?: renderer.openPage(index).use { page ->
                    var scale = min(maxWidth.toFloat() / page.width, maxHeight.toFloat() / page.height) * OVERSAMPLE
                    val longest = max(page.width, page.height) * scale
                    if (longest > MAX_SIDE) scale *= MAX_SIDE / longest
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }
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
        private const val OVERSAMPLE = 1.4f
        private const val MAX_SIDE = 2800f

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
