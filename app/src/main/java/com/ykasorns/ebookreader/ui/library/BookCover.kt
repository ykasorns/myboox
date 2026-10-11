package com.ykasorns.ebookreader.ui.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ykasorns.ebookreader.AppGraph
import com.ykasorns.ebookreader.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/** Decoded cover thumbnails, kept small so a large library scrolls smoothly. */
object CoverCache {
    private val cache = LruCache<String, ImageBitmap>(60)

    fun peek(id: String): ImageBitmap? = cache.get(id)

    fun evict(id: String) {
        cache.remove(id)
    }

    fun load(id: String, file: File, maxHeight: Int = 720): ImageBitmap? {
        cache.get(id)?.let { return it }
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return bitmap.asImageBitmap().also { cache.put(id, it) }
    }
}

private val coverShape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp)

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val image by produceState(initialValue = CoverCache.peek(book.id), book.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { CoverCache.load(book.id, AppGraph.library.coverFile(book.id)) }
        }
    }
    Box(
        modifier
            .shadow(6.dp, coverShape, clip = false)
            .clip(coverShape)
            .background(Color(0xFFE9E5E0)),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(book)
        }
        // A thin spine highlight makes flat covers read as books.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(4.dp)
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent))),
        )
    }
}

private val coverPalette = listOf(
    Color(0xFF2E4A62) to Color(0xFF1C3144),
    Color(0xFF7A3E2E) to Color(0xFF52281D),
    Color(0xFF3E5E4A) to Color(0xFF263D2F),
    Color(0xFF5B4A7A) to Color(0xFF3B2F52),
    Color(0xFF8A6A2E) to Color(0xFF5E471C),
    Color(0xFF6B3550) to Color(0xFF472236),
)

@Composable
private fun GeneratedCover(book: Book) {
    val (top, bottom) = coverPalette[book.id.hashCode().absoluteValue % coverPalette.size]
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = book.title,
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        if (book.author.isNotBlank()) {
            Text(
                text = book.author,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = book.format.name,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
