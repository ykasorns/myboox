package com.ykasorns.ebookreader.export

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.BookFormat
import com.ykasorns.ebookreader.data.LibraryRepository
import com.ykasorns.ebookreader.data.WebManifest
import com.ykasorns.ebookreader.importer.PdfImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.absoluteValue

/** Turns books into files other people and other apps can read: EPUB, or PDF via printing. */
class BookExporter(private val context: Context, private val library: LibraryRepository) {

    enum class Kind(val mimeType: String, val extension: String) {
        EPUB("application/epub+zip", "epub"),
        PDF("application/pdf", "pdf"),
    }

    fun canExportEpub(book: Book) = book.format != BookFormat.PDF

    /** Writes the book to a file in the app's cache, ready to share or save. */
    suspend fun export(book: Book, kind: Kind): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, fileName(book, kind))
        val bookDir = library.bookDir(book.id)
        when {
            kind == Kind.PDF && book.format == BookFormat.PDF ->
                File(bookDir, PdfImporter.FILE_NAME).copyTo(out, overwrite = true)
            kind == Kind.EPUB && book.format == BookFormat.EPUB ->
                out.outputStream().use { EpubWriter.repack(File(bookDir, WebManifest.CONTENT_DIR), it) }
            kind == Kind.EPUB ->
                out.outputStream().use {
                    EpubWriter.write(bookDir, EpubWriter.Metadata(book.id, book.title, book.author), cover(book), it)
                }
            else -> throw IllegalArgumentException("Use print() for PDFs of text books")
        }
        out
    }

    fun fileName(book: Book, kind: Kind): String {
        val base = book.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "book" }.take(80)
        return "$base.${kind.extension}"
    }

    /** Opens the share sheet (LINE, Gmail, Drive …) for an exported file. */
    fun share(activity: Activity, file: File, kind: Kind, title: String) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(kind.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        activity.startActivity(Intent.createChooser(send, title))
    }

    suspend fun copyTo(file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Cannot write $target")
    }

    /** The book's cover for the EPUB: its own cover image, or a simple one drawn from the title. */
    private fun cover(book: Book): EpubWriter.Cover {
        val file = library.coverFile(book.id)
        if (file.isFile) {
            val bytes = file.readBytes()
            when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                    return EpubWriter.Cover(bytes, "image/jpeg", "jpg")
                bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() ->
                    return EpubWriter.Cover(bytes, "image/png", "png")
                else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                    return EpubWriter.Cover(jpeg(bitmap), "image/jpeg", "jpg")
                }
            }
        }
        return EpubWriter.Cover(jpeg(drawCover(book)), "image/jpeg", "jpg")
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    private fun drawCover(book: Book): Bitmap {
        val width = 1200
        val height = 1800
        val colors = listOf(
            0xFF2E4A62 to 0xFF1C3144,
            0xFF7A3E2E to 0xFF52281D,
            0xFF3E5E4A to 0xFF263D2F,
            0xFF5B4A7A to 0xFF3B2F52,
            0xFF8A6A2E to 0xFF5E471C,
            0xFF6B3550 to 0xFF472236,
        )
        val (top, bottom) = colors[book.id.hashCode().absoluteValue % colors.size]
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        })
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 104f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val title = StaticLayout.Builder.obtain(book.title, 0, book.title.length, titlePaint, width - 200)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(6)
            .build()
        canvas.save()
        canvas.translate(100f, height * 0.36f - title.height / 2f)
        title.draw(canvas)
        canvas.restore()
        if (book.author.isNotBlank()) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255)
                textSize = 56f
                typeface = Typeface.SERIF
            }
            val author = StaticLayout.Builder.obtain(book.author, 0, book.author.length, authorPaint, width - 200)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .build()
            canvas.save()
            canvas.translate(100f, height * 0.82f)
            author.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    // ---- PDF through Android's print system ----------------------------------------------------

    /**
     * Lays the whole book out as one printable page (cover, contents, chapters each starting on
     * a new page) and opens the system print dialog, where "Save as PDF" writes the file and the
     * paper size can be chosen. Text stays real text, so Thai prints sharp and is searchable.
     */
    suspend fun print(activity: Activity, book: Book) {
        val page = withContext(Dispatchers.IO) { printPage(book) }
        printWith(activity, book, page)
    }

    private fun printPage(book: Book): String {
        val bookDir = library.bookDir(book.id)
        val manifest = WebManifest.read(bookDir) ?: throw IllegalStateException("Book has no content")
        val content = File(bookDir, WebManifest.CONTENT_DIR)
        val styles = LinkedHashSet<String>()
        val chapters = StringBuilder()
        val chapterIds = manifest.spine.withIndex().associate { (i, item) -> item.href to "chap-${i + 1}" }

        manifest.spine.forEachIndexed { index, item ->
            val file = File(content, item.href)
            if (!file.isFile) return@forEachIndexed
            val document = Jsoup.parse(file, "UTF-8")
            document.select("script").remove()
            document.head().select("style").forEach { styles += it.data() }
            // Links to other chapters become links inside the one long page.
            document.select("a[href]").forEach { link ->
                val href = link.attr("href")
                val path = href.substringBefore('#')
                val fragment = href.substringAfter('#', "")
                chapterIds[path]?.let { link.attr("href", "#" + fragment.ifEmpty { it }) }
            }
            chapters.append("<section class=\"chapter\" id=\"").append(chapterIds.getValue(item.href)).append("\">")
                .append(document.body().html())
                .append("</section>\n")
        }

        val coverFile = library.coverFile(book.id)
        val coverHtml = if (coverFile.isFile) {
            // Give the copy a real extension so the WebView knows it is an image.
            val png = coverFile.inputStream().use { it.read() } == 0x89
            val name = if (png) "print-cover.png" else "print-cover.jpg"
            coverFile.copyTo(File(content, name), overwrite = true)
            "<section class=\"cover\"><img src=\"$name\" alt=\"\"></section>"
        } else {
            "<section class=\"cover titlepage\"><h1>${esc(book.title)}</h1><p>${esc(book.author)}</p></section>"
        }
        val contentsTitle = if (Regex("[฀-๿]").containsMatchIn(book.title)) "สารบัญ" else "Contents"
        val toc = manifest.toc.joinToString("") { entry ->
            val target = if (entry.fragment.isNotEmpty()) entry.fragment else chapterIds[entry.path].orEmpty()
            "<li style=\"margin-left:${entry.depth * 1.2}em\"><a href=\"#${esc(target)}\">${esc(entry.title)}</a></li>"
        }
        val tocHtml = if (toc.isEmpty()) "" else "<section class=\"toc\"><h2>$contentsTitle</h2><ol>$toc</ol></section>"

        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(esc(book.title)).append("</title><style>")
            styles.forEach { append(it).append('\n') }
            append(PRINT_CSS)
            append("</style></head><body>")
            append(coverHtml).append(tocHtml).append(chapters)
            append("</body></html>")
        }
        val page = File(content, "print.html")
        page.writeText(html, Charsets.UTF_8)
        return "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/${LibraryRepository.BOOKS_DIR}/${book.id}/${WebManifest.CONTENT_DIR}/print.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun printWith(activity: Activity, book: Book, url: String) {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/${LibraryRepository.BOOKS_DIR}/",
                WebViewAssetLoader.InternalStoragePathHandler(activity, File(activity.filesDir, LibraryRepository.BOOKS_DIR)),
            )
            .build()
        val view = WebView(activity)
        // Keep the view alive until the print job has its content.
        printing = view
        view.settings.allowFileAccess = false
        view.webViewClient = object : WebViewClient() {
            private var printed = false

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (printed) return
                printed = true
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = book.title.ifBlank { "Myboox" }
                manager.print(
                    name,
                    view.createPrintDocumentAdapter(name),
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A5).build(),
                )
            }
        }
        view.loadUrl(url)
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var printing: WebView? = null

        private const val PRINT_CSS = """
@page{margin:16mm 14mm 18mm 14mm}
html,body{background:#fff!important;color:#000!important}
body{margin:0;font-family:serif;font-size:11.5pt;line-height:1.6}
.cover{break-after:page;text-align:center}
.cover img{display:block;margin:0 auto;max-width:100%;max-height:180mm}
.titlepage h1{font-size:26pt;margin:45mm 0 6mm}
.titlepage p{font-size:13pt}
.toc{break-after:page}
.toc ol{list-style:none;padding:0}
.toc li{margin:.35em 0}
.toc a{color:#000;text-decoration:none}
.chapter{break-before:page}
img{max-width:100%;break-inside:avoid}
p{orphans:2;widows:2}
"""
    }
}
