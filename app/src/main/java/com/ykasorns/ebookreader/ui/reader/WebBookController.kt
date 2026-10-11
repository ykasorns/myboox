package com.ykasorns.ebookreader.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.ykasorns.ebookreader.data.LibraryRepository
import com.ykasorns.ebookreader.data.WebManifest
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Drives the WebView that renders EPUB and TXT books: loads one chapter at a time, injects
 * reader.js, receives page/tap events from it and saves the reading position.
 */
class WebBookController(
    private val context: Context,
    private val bookId: String,
    private val manifest: WebManifest,
    initialPosition: String,
    private val onPositionChanged: (progress: Float, position: String) -> Unit,
) {
    var chapter by mutableIntStateOf(0)
        private set
    var page by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(1)
        private set
    var crashed by mutableStateOf(false)
        private set

    /** Called with "left", "center" or "right" when the reader taps the page. */
    var onTap: ((String) -> Unit)? = null

    val progress: Float get() = progressOf(chapter, page, pageCount)

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var appliedStyle: String? = null
    private var backgroundColor = Color.WHITE

    // Read on WebView's background thread when chapters are requested.
    @Volatile private var styleJson = "{}"
    @Volatile private var pendingTarget = TARGET_START

    private val booksRoot = File(context.filesDir, LibraryRepository.BOOKS_DIR)
    private val contentPrefix = "/${LibraryRepository.BOOKS_DIR}/$bookId/${WebManifest.CONTENT_DIR}/"
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(
            "/${LibraryRepository.BOOKS_DIR}/",
            WebViewAssetLoader.InternalStoragePathHandler(context, booksRoot),
        )
        .build()
    private val readerScript: String by lazy {
        context.assets.open("reader.js").bufferedReader().use { it.readText() }
    }

    private val weights = DoubleArray(manifest.spine.size) { maxOf(1L, manifest.spine[it].size).toDouble() }
    private val offsets = DoubleArray(weights.size).also { array ->
        var sum = 0.0
        for (i in array.indices) {
            array[i] = sum
            sum += weights[i]
        }
    }
    private val totalWeight = weights.sum()
    private val lastChapter = manifest.spine.lastIndex

    private val initialChapter: Int
    private val initialTarget: String

    init {
        val chapterPart = initialPosition.substringBefore(':').toIntOrNull()
        val fraction = initialPosition.substringAfter(':', "").toDoubleOrNull()
        if (chapterPart != null && chapterPart in manifest.spine.indices) {
            initialChapter = chapterPart
            initialTarget = if (fraction != null && fraction > 0) fractionTarget(fraction) else TARGET_START
        } else {
            initialChapter = 0
            initialTarget = TARGET_START
        }
        chapter = initialChapter
    }

    private fun progressOf(chapter: Int, page: Int, count: Int): Float {
        if (chapter !in weights.indices) return 0f
        if (chapter == lastChapter && page >= count - 1) return 1f
        val within = weights[chapter] * page / count.coerceAtLeast(1)
        return ((offsets[chapter] + within) / totalWeight).toFloat().coerceIn(0f, 1f)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setBackgroundColor(backgroundColor)
        view.addJavascriptInterface(Bridge(), "Android")
        view.webViewClient = Client()
        // Post so the first style from composition is in place before the chapter is requested.
        view.post { if (webView === view) openChapter(initialChapter, initialTarget) }
    }

    fun detach() {
        webView = null
    }

    fun applyStyle(json: String, background: Int) {
        styleJson = json
        if (background != backgroundColor) {
            backgroundColor = background
            webView?.setBackgroundColor(background)
        }
        if (json != appliedStyle) {
            appliedStyle = json
            js("window.reader&&reader.applySettings($json)")
        }
    }

    fun next() = js("window.reader&&reader.next()")

    fun prev() = js("window.reader&&reader.prev()")

    /** Jump to an overall position in the book (0..1), as chosen on the progress slider. */
    fun seek(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f) * totalWeight
        val index = offsets.indexOfLast { it <= target }.coerceIn(0, lastChapter)
        val within = ((target - offsets[index]) / weights[index]).coerceIn(0.0, 0.9999)
        if (index == chapter) {
            js("window.reader&&reader.goToFraction($within)")
        } else {
            openChapter(index, fractionTarget(within))
        }
    }

    /** Open a table-of-contents or link target, e.g. "OEBPS/ch3.xhtml#section2". */
    fun openHref(href: String) {
        val path = href.substringBefore('#')
        val fragment = href.substringAfter('#', "")
        val index = manifest.indexOf(path)
        if (index < 0) return
        when {
            index == chapter && fragment.isNotEmpty() ->
                js("window.reader&&reader.goToAnchor(${JSONObject.quote(fragment)})")
            index == chapter -> js("window.reader&&reader.goToFraction(0)")
            else -> openChapter(index, if (fragment.isEmpty()) TARGET_START else anchorTarget(fragment))
        }
    }

    private fun openChapter(index: Int, target: String) {
        val view = webView ?: return
        if (index !in manifest.spine.indices) return
        chapter = index
        page = 0
        pageCount = 1
        pendingTarget = target
        view.loadUrl(chapterUrl(index))
    }

    private fun chapterUrl(index: Int): String =
        "https://${WebViewAssetLoader.DEFAULT_DOMAIN}$contentPrefix" +
            manifest.spine[index].href.split('/').joinToString("/") { Uri.encode(it) }

    private fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    private fun initScript() = "window.__readerInit={\"settings\":$styleJson,\"target\":$pendingTarget};"

    private fun savePosition() {
        val fraction = page.toDouble() / pageCount.coerceAtLeast(1)
        onPositionChanged(progress, "$chapter:$fraction")
    }

    private fun handleLink(url: String) {
        val uri = Uri.parse(url)
        if (uri.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
            val path = uri.path ?: return
            if (path.startsWith(contentPrefix)) {
                val relative = path.removePrefix(contentPrefix)
                val fragment = uri.fragment
                openHref(if (fragment.isNullOrEmpty()) relative else "$relative#$fragment")
            }
            return
        }
        when (uri.scheme) {
            "http", "https", "mailto" -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // No app can open the link; ignore the tap.
            }
        }
    }

    /** Called from reader.js on the WebView's JavaBridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPage(page: Int, count: Int) {
            main.post {
                this@WebBookController.pageCount = count.coerceAtLeast(1)
                this@WebBookController.page = page.coerceIn(0, this@WebBookController.pageCount - 1)
                savePosition()
            }
        }

        @JavascriptInterface
        fun onTap(zone: String) {
            main.post { this@WebBookController.onTap?.invoke(zone) }
        }

        @JavascriptInterface
        fun onBoundary(direction: String) {
            main.post {
                if (direction == "next" && chapter < lastChapter) {
                    openChapter(chapter + 1, TARGET_START)
                } else if (direction == "prev" && chapter > 0) {
                    openChapter(chapter - 1, TARGET_END)
                }
            }
        }

        @JavascriptInterface
        fun onLink(url: String) {
            main.post { handleLink(url) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != WebViewAssetLoader.DEFAULT_DOMAIN) return null
            val path = url.path ?: return null
            return when {
                path == "/__reader/reader.js" -> javascript(readerScript)
                path == "/__reader/init.js" -> javascript(initScript())
                request.isForMainFrame && isDocument(path) -> chapterWithReader(path)
                else -> null
            } ?: assetLoader.shouldInterceptRequest(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            handleLink(request.url.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Fallback for documents where the script tags could not be injected.
            view.evaluateJavascript("typeof window.reader") { result ->
                if (result == "\"undefined\"") view.evaluateJavascript(initScript() + "\n" + readerScript, null)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (webView === view) webView = null
            crashed = true
            return true
        }
    }

    private fun isDocument(path: String): Boolean {
        if (!path.startsWith(contentPrefix)) return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "xhtml" || ext == "html" || ext == "htm" || ext == "xml"
    }

    /** Serves a chapter with our init + reader scripts added at the end of <head>. */
    private fun chapterWithReader(path: String): WebResourceResponse? {
        val file = File(context.filesDir, path.removePrefix("/")).canonicalFile
        if (!file.path.startsWith(booksRoot.canonicalPath + File.separator) || !file.isFile) return null
        val html = file.readText(Charsets.UTF_8)
        val tags = "<script src=\"/__reader/init.js?t=${System.nanoTime()}\"></script>" +
            "<script src=\"/__reader/reader.js\"></script>"
        val headEnd = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)
        val bodyStart = if (headEnd == null) Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html) else null
        val injected = when {
            headEnd != null -> html.substring(0, headEnd.range.first) + tags + html.substring(headEnd.range.first)
            bodyStart != null -> html.substring(0, bodyStart.range.last + 1) + tags + html.substring(bodyStart.range.last + 1)
            else -> html
        }
        val ext = file.extension.lowercase()
        val isXhtml = ext == "xhtml" || ext == "xml" || html.trimStart().startsWith("<?xml")
        return WebResourceResponse(
            if (isXhtml) "application/xhtml+xml" else "text/html",
            "utf-8",
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun javascript(source: String) = WebResourceResponse(
        "application/javascript",
        "utf-8",
        ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        private const val TARGET_START = "{\"type\":\"start\"}"
        private const val TARGET_END = "{\"type\":\"end\"}"

        private fun fractionTarget(fraction: Double) = "{\"type\":\"fraction\",\"value\":$fraction}"

        private fun anchorTarget(id: String) = "{\"type\":\"anchor\",\"value\":${JSONObject.quote(id)}}"
    }
}
