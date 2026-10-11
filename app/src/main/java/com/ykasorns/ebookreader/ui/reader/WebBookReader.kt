package com.ykasorns.ebookreader.ui.reader

import android.webkit.WebView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ykasorns.ebookreader.AppGraph
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.PageTurn
import com.ykasorns.ebookreader.data.ReaderSettings
import com.ykasorns.ebookreader.data.TocEntry
import com.ykasorns.ebookreader.data.WebManifest
import com.ykasorns.ebookreader.util.VolumeKeysEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Page margins handed to reader.js, in CSS pixels (= dp). */
data class PagePadding(val top: Float, val right: Float, val bottom: Float, val left: Float)

/** Reader for EPUB and TXT books, rendered and paginated in a WebView. */
@Composable
fun WebBookReader(book: Book, settings: ReaderSettings, chrome: ReaderChromeState) {
    val context = LocalContext.current
    val library = AppGraph.library
    val manifest = remember(book.id) { WebManifest.read(library.bookDir(book.id)) }
    if (manifest == null || manifest.spine.isEmpty()) {
        ReaderMessage(stringResource(R.string.open_failed), settings.theme, chrome.onBack)
        return
    }

    val scope = rememberCoroutineScope()
    val curl = rememberPageCurlState(scope)
    val controller = remember(book.id) {
        WebBookController(context.applicationContext, book.id, manifest, book.position, scope, curl) { progress, position ->
            scope.launch(Dispatchers.IO) { library.updatePosition(book.id, progress, position) }
        }
    }
    if (controller.crashed) {
        ReaderMessage(stringResource(R.string.open_failed), settings.theme, chrome.onBack)
        return
    }

    val toc = remember(manifest) {
        manifest.toc.ifEmpty {
            manifest.spine.mapIndexed { i, item -> TocEntry(context.getString(R.string.section_n, i + 1), item.href, 0) }
        }
    }
    val chapterTitles = remember(toc) { manifest.chapterTitles(toc) }
    var showToc by rememberSaveable { mutableStateOf(false) }

    val currentChrome by rememberUpdatedState(chrome)
    DisposableEffect(controller) {
        controller.onTap = { zone ->
            val tap = when (zone) {
                "left" -> TapZone.LEFT
                "right" -> TapZone.RIGHT
                else -> TapZone.CENTER
            }
            currentChrome.handleTap(tap, previous = controller::prev, next = controller::next)
        }
        onDispose {
            controller.onTap = null
            controller.detach()
        }
    }
    VolumeKeysEffect(settings.volumeKeys) { forward -> if (forward) controller.next() else controller.prev() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The page colour runs to every edge of the screen; text comes as close to the top as
        // the camera cutout allows (see rememberPageFrame).
        val wide = maxWidth >= 600.dp
        val frame = rememberPageFrame(settings.fullScreenText, maxWidth, wide)
        val side = settings.margin.dp * if (wide) 1.6f else 1f
        val padding = PagePadding(
            top = frame.top,
            right = frame.right + side,
            bottom = frame.bottom,
            left = frame.left + side,
        )
        val styleJson = remember(settings, padding) { webStyleJson(settings, padding) }
        val background = settings.theme.background.toInt()
        // Same rule as reader.js: two columns on wide landscape screens.
        val spread = settings.twoPageLandscape && maxWidth > maxHeight && maxWidth >= 700.dp
        SideEffect {
            controller.pageTurn = settings.pageTurn
            curl.mode = settings.pageTurn
            curl.spread = spread
            curl.paperColor = background
            controller.applyStyle(styleJson, background)
        }

        AndroidView(
            factory = { ctx -> controller.createView(ctx) },
            onRelease = { frameView -> (frameView.getChildAt(0) as? WebView)?.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        PageCurlOverlay(curl)

        val label = { fraction: Float -> "${(fraction * 100).roundToInt()}%" }
        ReaderChrome(
            visible = chrome.controlsVisible,
            theme = settings.theme,
            title = book.title,
            header = chapterTitles.getOrNull(controller.chapter) ?: book.title,
            headerPlacement = frame.header,
            footer = stringResource(R.string.page_of, (controller.page + 1).toString(), controller.pageCount),
            footerBottom = frame.footerBottom,
            progress = controller.progress,
            sliderLabel = label,
            onSeek = controller::seek,
            onBack = chrome.onBack,
            onContents = { showToc = true },
            onAppearance = chrome.onAppearance,
        )
    }

    if (showToc) {
        val current = toc.indexOfLast { manifest.indexOf(it.path) in 0..controller.chapter }
        TocSheet(
            entries = toc,
            currentIndex = current,
            theme = settings.theme,
            onSelect = { entry ->
                showToc = false
                chrome.setControlsVisible(false)
                controller.openHref(entry.href)
            },
            onDismiss = { showToc = false },
        )
    }
}

private fun cssColor(argb: Long) = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

private fun webStyleJson(settings: ReaderSettings, padding: PagePadding): String {
    val theme = settings.theme
    return JSONObject()
        .put("bg", cssColor(theme.background))
        .put("fg", cssColor(theme.text))
        .put("link", cssColor(theme.link))
        .put("selection", if (theme.isDark) "rgba(255,214,10,0.35)" else "rgba(255,204,0,0.4)")
        .put("fontScale", settings.fontScale)
        .put("font", settings.font.css)
        .put("lineHeight", settings.lineSpacing.value.toDouble())
        .put("justify", settings.justify)
        .put("twoPage", settings.twoPageLandscape)
        // With the curl animation the app handles swipes; otherwise reader.js does.
        .put("swipe", settings.pageTurn != PageTurn.CURL)
        .put("animate", settings.pageTurn == PageTurn.SLIDE)
        .put(
            "padding",
            JSONObject()
                .put("t", padding.top.roundToInt())
                .put("r", padding.right.roundToInt())
                .put("b", padding.bottom.roundToInt())
                .put("l", padding.left.roundToInt()),
        )
        .toString()
}
