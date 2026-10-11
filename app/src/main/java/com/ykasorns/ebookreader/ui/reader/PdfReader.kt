package com.ykasorns.ebookreader.ui.reader

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs
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
        is PdfLoad.Ready -> PdfContent(book, state.book, settings, chrome)
    }
}

@Composable
private fun PdfContent(book: Book, pdf: PdfBook, settings: ReaderSettings, chrome: ReaderChromeState) {
    val lastPage = (pdf.pageCount - 1).coerceAtLeast(0)
    var currentPage by rememberSaveable(book.id) {
        mutableIntStateOf(book.position.toIntOrNull()?.coerceIn(0, lastPage) ?: 0)
    }
    LaunchedEffect(currentPage) {
        val progress = (currentPage + 1).toFloat() / pdf.pageCount.coerceAtLeast(1)
        withContext(Dispatchers.IO) { AppGraph.library.updatePosition(book.id, progress, currentPage.toString()) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        // Tall phone screens are much narrower than a printed page, so by default PDFs scroll
        // continuously at full width there; tablets turn page by page like a book.
        val scroll = settings.pdfLayoutIsScroll(wide)
        if (scroll) {
            PdfScrollReader(book, pdf, settings, chrome, currentPage) { currentPage = it }
        } else {
            PdfPagedReader(book, pdf, settings, chrome, currentPage, wide) { currentPage = it }
        }
    }
}

// ---- Continuous scrolling ------------------------------------------------------------------

@Composable
private fun PdfScrollReader(
    book: Book,
    pdf: PdfBook,
    settings: ReaderSettings,
    chrome: ReaderChromeState,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
    val horizontal = rememberScrollState()
    val colorFilter = remember(settings.theme) { pdfColorMatrix(settings.theme)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } }
    var zoom by remember { mutableFloatStateOf(1f) }
    val lastPage = (pdf.pageCount - 1).coerceAtLeast(0)
    val currentChrome by rememberUpdatedState(chrome)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onPageChange(it) }
    }

    fun setZoom(value: Float, focusX: Float) {
        val old = zoom
        val new = value.coerceIn(1f, 4f)
        if (new == old) return
        zoom = new
        // Keep the point under the fingers in place horizontally.
        val target = ((horizontal.value + focusX) * new / old - focusX).roundToInt()
        scope.launch { horizontal.scrollTo(target.coerceAtLeast(0)) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { position -> setZoom(if (zoom > 1.01f) 1f else 2f, position.x) },
                    onTap = { currentChrome.handleTap(TapZone.CENTER, previous = {}, next = {}) },
                )
            }
            .pointerInput(Unit) {
                // Two fingers pinch to zoom; one finger scrolls as usual.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // Initial pass: see the pinch before the list starts scrolling with it.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) {
                            val centroid = event.changes.filter { it.pressed }.map { it.position }
                                .fold(Offset.Zero) { acc, p -> acc + p } / event.changes.count { it.pressed }.toFloat()
                            setZoom(zoom * event.calculateZoom(), centroid.x)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val baseWidth = maxWidth
        // Render sharper images once zoomed in, without going overboard on memory.
        val renderScale = if (zoom > 1.3f) 2f else 1f
        val renderWidth = with(density) { (baseWidth.toPx() * renderScale).roundToInt() }
        val cutout = WindowInsets.displayCutout
        val topPadding = with(density) { cutout.getTop(this).toDp() } + 8.dp
        val bottomPadding = with(density) { cutout.getBottom(this).toDp() } + 24.dp
        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(horizontal, enabled = zoom > 1.01f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(baseWidth * zoom)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(pdf.pageCount, key = { it }) { index ->
                    PdfScrollPage(pdf, index, renderWidth, settings.pdfCrop, colorFilter)
                }
            }
        }

        ReaderChrome(
            visible = chrome.controlsVisible,
            theme = settings.theme,
            title = book.title,
            header = book.title,
            headerPlacement = null,
            footer = "",
            footerBottom = null,
            progress = if (lastPage == 0) 0f else currentPage.toFloat() / lastPage,
            sliderLabel = { fraction -> pageLabel(context, (fraction * lastPage).roundToInt(), pdf.pageCount) },
            onSeek = { fraction -> scope.launch { listState.scrollToItem((fraction * lastPage).roundToInt()) } },
            onBack = chrome.onBack,
            onContents = null,
            onAppearance = chrome.onAppearance,
        )
    }
}

@Composable
private fun PdfScrollPage(pdf: PdfBook, index: Int, renderWidth: Int, crop: Boolean, colorFilter: ColorFilter?) {
    val aspect by produceState(1.414f, pdf, index, crop) { value = pdf.aspect(index, crop) }
    val image by produceState<ImageBitmap?>(null, pdf, index, renderWidth, crop) {
        value = pdf.render(index, renderWidth, (renderWidth * 6), crop)?.asImageBitmap()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect.coerceIn(0.2f, 5f))
            .background(Color.White),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- Page by page, with the page-turn animation ----------------------------------------------

@Composable
private fun PdfPagedReader(
    book: Book,
    pdf: PdfBook,
    settings: ReaderSettings,
    chrome: ReaderChromeState,
    currentPage: Int,
    wide: Boolean,
    onPageChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val curl = rememberPageCurlState(scope)
    val currentChrome by rememberUpdatedState(chrome)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Tablets and unfolded foldables in landscape show two pages side by side, cover alone.
        val spreadMode = settings.twoPageLandscape && maxWidth > maxHeight && maxWidth >= 600.dp
        val spreads = remember(pdf.pageCount, spreadMode) { buildSpreads(pdf.pageCount, spreadMode) }
        var index by remember(spreads) {
            mutableIntStateOf(spreads.indexOfFirst { currentPage in it }.coerceAtLeast(0))
        }
        LaunchedEffect(index, spreads) { spreads.getOrNull(index)?.let { onPageChange(it.first()) } }

        val frame = rememberPageFrame(settings.fullScreenText, maxWidth, wide)
        val padding = with(density) {
            Rect(
                (frame.left + 6f).dp.roundToPx(),
                frame.top.dp.roundToPx(),
                (frame.right + 6f).dp.roundToPx(),
                frame.bottom.dp.roundToPx(),
            )
        }
        val background = settings.theme.background.toInt()
        val filter = remember(settings.theme) { pdfColorMatrix(settings.theme)?.let { ColorMatrixColorFilter(it) } }
        val cache = remember(pdf, width, height, spreads, padding, settings.theme, settings.pdfCrop) { SpreadCache() }

        suspend fun spread(i: Int): Bitmap? {
            if (i !in spreads.indices) return null
            cache[i]?.let { return it }
            val bitmap = pdf.renderSpread(spreads[i], width, height, padding, settings.pdfCrop, background, filter)
            if (bitmap != null) cache.put(i, bitmap, keepNear = index)
            return bitmap
        }

        var shown by remember(cache) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(cache, index) {
            if (!curl.active) shown = spread(index)
            // Have the neighbours ready so a turn can start instantly.
            spread(index + 1)
            spread(index - 1)
        }

        SideEffect {
            curl.mode = settings.pageTurn
            curl.spread = spreadMode
            curl.paperColor = background
        }

        var scale by remember(index) { mutableFloatStateOf(1f) }
        var offset by remember(index) { mutableStateOf(Offset.Zero) }
        // How the turn started by the current drag reports back when the finger lifts.
        var pendingFinish by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

        fun startTurn(forward: Boolean, x: Float, y: Float, auto: Boolean): Boolean {
            if (curl.busy) return false
            val target = index + if (forward) 1 else -1
            if (target !in spreads.indices) return false
            val current = shown
            val other = cache[target]
            val front = if (forward) current else other
            val under = if (forward) other else current
            val finish: (Boolean) -> Unit = { navigated ->
                if (navigated) {
                    shown = cache[target] ?: shown
                    index = target
                }
                curl.end()
            }
            if (auto) {
                if (other == null) {
                    scope.launch {
                        val loaded = spread(target)
                        curl.auto(forward, if (forward) current else loaded, if (forward) loaded else current, finish)
                    }
                } else {
                    curl.auto(forward, front, under, finish)
                }
            } else {
                curl.begin(forward, x, y, front, under)
                if (other == null) {
                    scope.launch {
                        val loaded = spread(target)
                        if (curl.active) curl.setBitmaps(if (forward) current else loaded, if (forward) loaded else current)
                    }
                }
            }
            pendingFinish = finish
            return true
        }

        fun turn(forward: Boolean) {
            startTurn(forward, 0f, 0f, auto = true)
        }
        VolumeKeysEffect(settings.volumeKeys) { forward -> turn(forward) }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(spreads, cache) {
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
                        },
                        onTap = { position ->
                            val zone = when {
                                position.x < size.width * 0.3f -> TapZone.LEFT
                                position.x > size.width * 0.7f -> TapZone.RIGHT
                                else -> TapZone.CENTER
                            }
                            currentChrome.handleTap(zone, previous = { turn(false) }, next = { turn(true) })
                        },
                    )
                }
                .pointerInput(spreads, cache) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var zooming = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val change = event.changes.first()
                            when {
                                pressed > 1 || zooming -> {
                                    // Pinch to zoom, and pan while zoomed.
                                    zooming = true
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    offset = if (newScale <= 1.01f) Offset.Zero else clampOffset(offset + event.calculatePan(), newScale, size)
                                    scale = newScale
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                dragging -> {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    curl.drag(change.position.x, change.position.y)
                                    change.consume()
                                }
                                scale > 1.01f -> {
                                    offset = clampOffset(offset + event.calculatePan(), scale, size)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                else -> {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    val delta = change.position - down.position
                                    if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.3f) {
                                        if (startTurn(delta.x < 0, down.position.x, down.position.y, auto = false)) {
                                            dragging = true
                                            curl.drag(change.position.x, change.position.y)
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (dragging) {
                            val finish = pendingFinish
                            curl.release(tracker.calculateVelocity().x) { navigated -> finish?.invoke(navigated) }
                        }
                    }
                },
        ) {
            shown?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: CircularProgressIndicator(
                color = Color(settings.theme.secondary),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        PageCurlOverlay(curl)

        val pages = spreads.getOrNull(index) ?: listOf(0)
        val label = if (pages.size == 2) "${pages[0] + 1}–${pages[1] + 1}" else "${pages[0] + 1}"
        val lastPage = (pdf.pageCount - 1).coerceAtLeast(0)
        ReaderChrome(
            visible = chrome.controlsVisible,
            theme = settings.theme,
            title = book.title,
            header = book.title,
            headerPlacement = frame.header,
            footer = stringResource(R.string.page_of, label, pdf.pageCount),
            footerBottom = frame.footerBottom,
            progress = if (lastPage == 0) 0f else pages.first().toFloat() / lastPage,
            sliderLabel = { fraction -> pageLabel(context, (fraction * lastPage).roundToInt(), pdf.pageCount) },
            onSeek = { fraction ->
                val page = (fraction * lastPage).roundToInt()
                val target = spreads.indexOfFirst { page in it }.coerceAtLeast(0)
                scope.launch {
                    shown = spread(target)
                    index = target
                }
            },
            onBack = chrome.onBack,
            onContents = null,
            onAppearance = chrome.onAppearance,
        )
    }
}

/** The few rendered spreads around the current one. */
private class SpreadCache {
    private val items = HashMap<Int, Bitmap>()

    operator fun get(index: Int): Bitmap? = items[index]

    fun put(index: Int, bitmap: Bitmap, keepNear: Int) {
        items[index] = bitmap
        items.keys.filter { abs(it - keepNear) > 2 && it != index }.forEach { items.remove(it) }
    }
}

private fun pageLabel(context: android.content.Context, page: Int, count: Int) =
    context.getString(R.string.page_of, (page + 1).toString(), count)

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

private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = (scale - 1f) * size.width / 2f
    val maxY = (scale - 1f) * size.height / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Colour matrix (4x5, offsets in 0..255) mapping white paper to the theme background and black
 * ink to the theme text colour, so PDFs follow the Paper, Gray and Night themes too.
 */
private fun pdfColorMatrix(theme: ReaderTheme): FloatArray? {
    if (theme == ReaderTheme.WHITE) return null
    val bg = Color(theme.background)
    val fg = Color(theme.text)
    fun row(channel: Int, b: Float, f: Float): FloatArray {
        val values = FloatArray(5)
        // out = f + (b - f) * in
        values[channel] = b - f
        values[4] = f * 255f
        return values
    }
    return row(0, bg.red, fg.red) + row(1, bg.green, fg.green) + row(2, bg.blue, fg.blue) +
        floatArrayOf(0f, 0f, 0f, 1f, 0f)
}
