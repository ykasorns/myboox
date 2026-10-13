package com.ykasorns.ebookreader.ui.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.ykasorns.ebookreader.data.LibraryRepository
import com.ykasorns.ebookreader.data.PageTurn
import com.ykasorns.ebookreader.data.WebManifest
import com.ykasorns.ebookreader.util.findActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives the WebView that renders EPUB and TXT books: loads one chapter at a time, injects
 * reader.js, receives page/tap events from it and saves the reading position.
 */
class WebBookController(
    private val context: Context,
    private val bookId: String,
    private val manifest: WebManifest,
    initialPosition: String,
    private val scope: CoroutineScope,
    private val curl: PageCurlState,
    private val onPositionChanged: (progress: Float, position: String) -> Unit,
) : ReaderFrame.TurnHandler {
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
    private var frame: ReaderFrame? = null
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

    /** How pages turn; with [PageTurn.CURL] the app animates the page instead of reader.js. */
    var pageTurn: PageTurn = PageTurn.CURL
        set(value) {
            field = value
            frame?.curlEnabled = value == PageTurn.CURL
        }

    /** Builds the view hierarchy for the reader: a [ReaderFrame] holding the WebView. */
    fun createView(context: Context): ReaderFrame {
        val frame = ReaderFrame(context)
        val view = WebView(context)
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.turnHandler = this
        frame.curlEnabled = pageTurn == PageTurn.CURL
        this.frame = frame
        attach(view)
        return frame
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attach(view: WebView) {
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Honour the viewport tag added to every chapter (width=device-width, scale 1).
            // Without it the WebView may lay the page out wider than the screen and leave
            // parts of it unpainted.
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            // Paint the whole page, not just what the WebView guesses is visible, so no blank
            // blocks are left behind after page turns and snapshots.
            offscreenPreRaster = true
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
        // Wait until the view has its real size (and the first style from composition is in
        // place) before the chapter is requested, so it is never laid out for a wrong width.
        whenSized(view) { if (webView === view) openChapter(initialChapter, initialTarget) }
    }

    private fun whenSized(view: View, action: () -> Unit) {
        if (view.width > 0 && view.height > 0) {
            view.post(action)
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                v.post(action)
            }
        })
    }

    fun detach() {
        webView = null
        frame?.turnHandler = null
        frame = null
        curl.end()
        turning = false
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

    fun next() = turn(forward = true)

    fun prev() = turn(forward = false)

    private fun turn(forward: Boolean) {
        if (pageTurn == PageTurn.CURL) {
            autoTurn(forward)
        } else {
            js(if (forward) "window.reader&&reader.next()" else "window.reader&&reader.prev()")
        }
    }

    // ---- Page curl ---------------------------------------------------------------------------
    //
    // A turn works on one snapshot: the current page is copied from the screen, the overlay
    // shows that copy, and the WebView moves to the neighbouring page underneath it. The overlay
    // leaves the area of the neighbouring page transparent, so the live WebView shows through
    // there. If the turn is abandoned the WebView moves back before the overlay goes away.
    //
    // The snapshot comes from the window (PixelCopy) rather than WebView.draw(), which renders
    // blank pages on many devices.

    private var turning = false
    private var turnToken = 0
    private var lastTurnActivity = 0L
    private var chapterBefore = 0
    private var pageBefore = 0
    private var pageCountBefore = 1
    private var onChapterReady: (() -> Unit)? = null

    override val isTurning: Boolean get() = turning

    private fun atStart() = chapter == 0 && page == 0

    private fun atEnd() = chapter == lastChapter && page >= pageCount - 1

    /** Copies what is on screen over the WebView, header and page number included. */
    private fun captureScreen(onCaptured: (Bitmap?) -> Unit) {
        val view = webView
        val window = frame?.context?.findActivity()?.window
        if (view == null || window == null || view.width <= 0 || view.height <= 0) {
            onCaptured(null)
            return
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val source = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, source, bitmap, { result ->
                onCaptured(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, main)
        } catch (e: IllegalArgumentException) {
            onCaptured(null)
        }
    }

    /**
     * Starts a turn: snapshots the current page, lets [show] put it in the overlay, then moves
     * the WebView to the neighbouring page. [onReady] runs once the neighbouring page is drawn.
     */
    private fun prepareTurn(forward: Boolean, show: (current: Bitmap) -> Unit, onReady: () -> Unit): Boolean {
        if (turning || webView == null) return false
        if (if (forward) atEnd() else atStart()) return false
        turning = true
        val token = ++turnToken
        chapterBefore = chapter
        pageBefore = page
        pageCountBefore = pageCount
        lastTurnActivity = SystemClock.uptimeMillis()
        main.postDelayed({ checkStuck(token) }, STUCK_TURN_MS)
        captureScreen { current ->
            if (token != turnToken) return@captureScreen
            if (current == null) {
                // No snapshot: turn the page without the animation.
                endTurn()
                js(if (forward) "window.reader&&reader.next()" else "window.reader&&reader.prev()")
                return@captureScreen
            }
            show(current)
            scope.launch {
                // Let the overlay reach the screen before the WebView changes underneath it.
                withFrameNanos { }
                withFrameNanos { }
                if (token != turnToken) return@launch
                webView?.evaluateJavascript("window.reader?reader.step(${if (forward) 1 else -1}):'missing'") { result ->
                    if (token != turnToken) return@evaluateJavascript
                    when (result) {
                        "\"ok\"" -> whenDrawn(token, onReady)
                        "\"boundary\"" -> {
                            onChapterReady = { whenDrawn(token, onReady) }
                            if (forward) openChapter(chapter + 1, TARGET_START) else openChapter(chapter - 1, TARGET_END)
                        }
                        else -> onReady()
                    }
                }
            }
        }
        return true
    }

    private fun whenDrawn(token: Int, action: () -> Unit) {
        val view = webView ?: return action()
        view.postVisualStateCallback(token.toLong(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (token == turnToken) action()
            }
        })
    }

    private var dragX = Float.NaN
    private var dragY = Float.NaN
    private var pendingRelease: Float? = null

    // A null page in the overlay is a transparent hole onto the live WebView.
    override fun beginDrag(forward: Boolean, x: Float, y: Float): Boolean {
        dragX = Float.NaN
        pendingRelease = null
        return prepareTurn(forward, show = { current ->
            if (forward) curl.begin(true, x, y, front = current, under = null) else curl.begin(false, x, y, front = null, under = current)
            // Catch up with the finger, which kept moving while the snapshot was taken.
            if (dragX.isFinite()) curl.drag(dragX, dragY)
            pendingRelease?.let { velocity ->
                pendingRelease = null
                curl.release(velocity) { navigated -> finishTurn(navigated) }
            }
        }, onReady = {})
    }

    override fun drag(x: Float, y: Float) {
        lastTurnActivity = SystemClock.uptimeMillis()
        dragX = x
        dragY = y
        curl.drag(x, y)
    }

    override fun release(velocityX: Float) {
        lastTurnActivity = SystemClock.uptimeMillis()
        if (!curl.active) {
            // Let go before the snapshot was ready: the WebView may already be moving.
            pendingRelease = velocityX
            return
        }
        curl.release(velocityX) { navigated -> finishTurn(navigated) }
    }

    private fun autoTurn(forward: Boolean) {
        var started = false
        var current: Bitmap? = null
        val token = turnToken + 1
        val start = {
            if (!started && token == turnToken && turning) {
                started = true
                if (forward) {
                    curl.auto(true, front = current, under = null) { navigated -> finishTurn(navigated) }
                } else {
                    curl.auto(false, front = null, under = current) { navigated -> finishTurn(navigated) }
                }
            }
        }
        prepareTurn(
            forward,
            show = { snapshot ->
                current = snapshot
                // Hold the current page on screen until the other page is ready, then turn.
                curl.begin(forward, 0f, 0f, front = snapshot, under = snapshot)
                // Never leave the reader waiting if the neighbouring page is slow to appear.
                main.postDelayed({ start() }, 700)
            },
            onReady = { start() },
        )
    }

    private fun finishTurn(navigated: Boolean) {
        val token = turnToken
        if (navigated) {
            endTurn()
            return
        }
        // The page fell back: return the WebView to where it was, then drop the overlay.
        if (chapter == chapterBefore) {
            js("window.reader&&reader.goTo($pageBefore)")
            webView?.postVisualStateCallback(token.toLong(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (token == turnToken) endTurn()
                }
            }) ?: endTurn()
        } else {
            onChapterReady = { if (token == turnToken) endTurn() }
            val fraction = pageBefore.toDouble() / pageCountBefore.coerceAtLeast(1)
            openChapter(chapterBefore, if (pageBefore == 0) TARGET_START else fractionTarget(fraction))
        }
    }

    /** A turn that never finishes (a page that never draws) must not block the reader. */
    private fun checkStuck(token: Int) {
        if (!turning || token != turnToken) return
        // Still being dragged, or just let go and animating: check again later.
        val idle = SystemClock.uptimeMillis() - lastTurnActivity
        if (frame?.isDragging == true || idle < STUCK_TURN_MS) {
            main.postDelayed({ checkStuck(token) }, STUCK_TURN_MS)
            return
        }
        endTurn()
    }

    private fun endTurn() {
        turnToken++
        onChapterReady = null
        pendingRelease = null
        curl.end()
        turning = false
    }

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
                onChapterReady?.let { ready ->
                    onChapterReady = null
                    ready()
                }
            }
        }

        @JavascriptInterface
        fun onSelection(active: Boolean) {
            main.post { frame?.selectionActive = active }
        }

        @JavascriptInterface
        fun onTap(zone: String) {
            main.post { if (!turning) this@WebBookController.onTap?.invoke(zone) }
        }

        @JavascriptInterface
        fun onBoundary(direction: String) {
            main.post {
                if (turning) return@post
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
            curl.end()
            turning = false
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
        val tags = "$VIEWPORT_META<script src=\"/__reader/init.js?t=${System.nanoTime()}\"></script>" +
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
        private const val STUCK_TURN_MS = 3000L
        private const val VIEWPORT_META = "<meta name=\"viewport\" content=\"width=device-width, " +
            "initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/>"

        private fun fractionTarget(fraction: Double) = "{\"type\":\"fraction\",\"value\":$fraction}"

        private fun anchorTarget(id: String) = "{\"type\":\"anchor\",\"value\":${JSONObject.quote(id)}}"
    }
}

/**
 * Hosts the WebView. When the page-curl animation is on it takes over horizontal drags (so the
 * page follows the finger) while taps, long presses and text selection still reach the WebView.
 */
class ReaderFrame(context: Context) : FrameLayout(context) {
    interface TurnHandler {
        val isTurning: Boolean
        fun beginDrag(forward: Boolean, x: Float, y: Float): Boolean
        fun drag(x: Float, y: Float)
        fun release(velocityX: Float)
    }

    var turnHandler: TurnHandler? = null
    var curlEnabled = false
    var selectionActive = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    val isDragging: Boolean get() = dragging
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val handler = turnHandler
        if (!curlEnabled || handler == null) return false
        if (handler.isTurning && !dragging) return true // ignore touches while a page is animating
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = true
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (tracking && !selectionActive && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                    tracking = false
                    if (handler.beginDrag(forward = dx < 0, x = downX, y = downY)) {
                        dragging = true
                        handler.drag(ev.x, ev.y)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                velocity?.recycle()
                velocity = null
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return turnHandler?.isTurning == true
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> turnHandler?.drag(ev.x, ev.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = if (ev.actionMasked == MotionEvent.ACTION_UP) velocity?.xVelocity ?: 0f else 0f
                dragging = false
                velocity?.recycle()
                velocity = null
                turnHandler?.release(vx)
            }
        }
        return true
    }
}
