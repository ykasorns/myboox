package com.ykasorns.ebookreader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ykasorns.ebookreader.AppGraph
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.ReaderSettings
import com.ykasorns.ebookreader.data.ReaderTheme
import com.ykasorns.ebookreader.importer.PdfImporter
import com.ykasorns.ebookreader.util.VolumeKeysEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data object Failed : PdfLoad
    class Ready(val book: PdfBook) : PdfLoad
}

@Composable
fun PdfReader(book: Book, settings: ReaderSettings, chrome: ReaderChromeState) {
    val file = remember(book.id) { File(AppGraph.library.bookDir(book.id), PdfImporter.FILE_NAME) }
    val load by produceState<PdfLoad>(PdfLoad.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfLoad.Ready(PdfBook.open(file))
            } catch (e: Exception) {
                PdfLoad.Failed
            }
        }
    }
    val pdf = (load as? PdfLoad.Ready)?.book
    DisposableEffect(pdf) { onDispose { pdf?.closeAsync() } }

    when (val state = load) {
        PdfLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(settings.theme.secondary))
        }
        PdfLoad.Failed -> ReaderMessage(stringResource(R.string.open_failed), settings.theme, chrome.onBack)
        is PdfLoad.Ready -> PdfPager(book, state.book, settings, chrome)
    }
}

@Composable
private fun PdfPager(book: Book, pdf: PdfBook, settings: ReaderSettings, chrome: ReaderChromeState) {
    val context = LocalContext.current
    val library = AppGraph.library
    val scope = rememberCoroutineScope()
    val lastPage = (pdf.pageCount - 1).coerceAtLeast(0)
    var currentPage by rememberSaveable(book.id) {
        mutableIntStateOf(book.position.toIntOrNull()?.coerceIn(0, lastPage) ?: 0)
    }
    val colorFilter = remember(settings.theme) { pdfColorFilter(settings.theme) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets and unfolded foldables in landscape show two pages side by side, cover alone.
        val spread = settings.twoPageLandscape && maxWidth > maxHeight && maxWidth >= 600.dp
        val spreads = remember(pdf.pageCount, spread) { buildSpreads(pdf.pageCount, spread) }
        val pagerState = key(spread) {
            rememberPagerState(initialPage = spreads.indexOfFirst { currentPage in it }.coerceAtLeast(0)) { spreads.size }
        }
        var zoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState, spreads) {
            snapshotFlow { pagerState.currentPage }.collect { index ->
                zoomed = false
                val pages = spreads.getOrNull(index) ?: return@collect
                currentPage = pages.first()
                val progress = (pages.last() + 1).toFloat() / pdf.pageCount.coerceAtLeast(1)
                withContext(Dispatchers.IO) { library.updatePosition(book.id, progress, currentPage.toString()) }
            }
        }

        fun turn(delta: Int) {
            val target = (pagerState.currentPage + delta).coerceIn(0, spreads.lastIndex)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }
        VolumeKeysEffect(settings.volumeKeys) { forward -> turn(if (forward) 1 else -1) }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { spreads[it].first() },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PdfSpread(
                pdf = pdf,
                pages = spreads[index],
                colorFilter = colorFilter,
                onZoomChanged = { value -> if (index == pagerState.currentPage) zoomed = value },
                onTap = { zone -> chrome.handleTap(zone, previous = { turn(-1) }, next = { turn(1) }) },
            )
        }

        val pages = spreads.getOrNull(pagerState.currentPage) ?: listOf(0)
        val pageLabel = if (pages.size == 2) "${pages[0] + 1}–${pages[1] + 1}" else "${pages[0] + 1}"
        ReaderChrome(
            visible = chrome.controlsVisible,
            theme = settings.theme,
            title = book.title,
            header = book.title,
            footer = stringResource(R.string.page_of, pageLabel, pdf.pageCount),
            progress = if (lastPage == 0) 0f else currentPage.toFloat() / lastPage,
            sliderLabel = { fraction ->
                context.getString(R.string.page_of, ((fraction * lastPage).roundToInt() + 1).toString(), pdf.pageCount)
            },
            onSeek = { fraction ->
                val page = (fraction * lastPage).roundToInt()
                val target = spreads.indexOfFirst { page in it }.coerceAtLeast(0)
                scope.launch { pagerState.scrollToPage(target) }
            },
            onBack = chrome.onBack,
            onContents = null,
            onAppearance = chrome.onAppearance,
        )
    }
}

private fun buildSpreads(pageCount: Int, spread: Boolean): List<List<Int>> {
    if (pageCount <= 0) return listOf(listOf(0))
    if (!spread) return List(pageCount) { listOf(it) }
    val result = mutableListOf(listOf(0))
    var page = 1
    while (page < pageCount) {
        result += if (page + 1 < pageCount) listOf(page, page + 1) else listOf(page)
        page += 2
    }
    return result
}

@Composable
private fun PdfSpread(
    pdf: PdfBook,
    pages: List<Int>,
    colorFilter: ColorFilter?,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (TapZone) -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChanged)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clampOffset((center - position) * (scale - 1f), scale, size)
                        }
                        zoomChanged(scale > 1.01f)
                    },
                    onTap = { position ->
                        tap(
                            when {
                                position.x < size.width * 0.3f -> TapZone.LEFT
                                position.x > size.width * 0.7f -> TapZone.RIGHT
                                else -> TapZone.CENTER
                            },
                        )
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch to zoom, and pan while zoomed. A one-finger swipe at normal size is left
                // to the pager so it turns the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1.01f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (newScale <= 1.01f) Offset.Zero else clampOffset(offset + event.calculatePan(), newScale, size)
                            scale = newScale
                            zoomChanged(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(vertical = 30.dp, horizontal = 4.dp),
    ) {
        val width = constraints.maxWidth / pages.size
        val height = constraints.maxHeight
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, page ->
                val alignment = when {
                    pages.size == 1 -> Alignment.Center
                    i == 0 -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                }
                PdfPageImage(pdf, page, width, height, colorFilter, alignment, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = (scale - 1f) * size.width / 2f
    val maxY = (scale - 1f) * size.height / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

@Composable
private fun PdfPageImage(
    pdf: PdfBook,
    index: Int,
    width: Int,
    height: Int,
    colorFilter: ColorFilter?,
    alignment: Alignment,
    modifier: Modifier,
) {
    val image by produceState<ImageBitmap?>(null, pdf, index, width, height) {
        value = pdf.render(index, width, height)?.asImageBitmap()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = alignment,
                colorFilter = colorFilter,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Maps white paper to the theme background and black ink to the theme text colour, so PDFs
 * follow the Paper, Gray and Night themes too (images are recoloured as well).
 */
private fun pdfColorFilter(theme: ReaderTheme): ColorFilter? {
    if (theme == ReaderTheme.WHITE) return null
    val bg = Color(theme.background)
    val fg = Color(theme.text)
    fun row(channel: Int, b: Float, f: Float): FloatArray {
        val values = FloatArray(5)
        // out = f + (b - f) * in, with in/out in 0..255.
        values[channel] = b - f
        values[4] = f * 255f
        return values
    }
    val r = row(0, bg.red, fg.red)
    val g = row(1, bg.green, fg.green)
    val b = row(2, bg.blue, fg.blue)
    val matrix = r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(matrix))
}
