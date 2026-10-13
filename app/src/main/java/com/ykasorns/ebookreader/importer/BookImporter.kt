package com.ykasorns.ebookreader.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.BookFormat
import com.ykasorns.ebookreader.data.LibraryRepository
import com.ykasorns.ebookreader.ui.library.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

class ImportException(message: String) : Exception(message)

/** Title/author found inside the file. Blank values fall back to the file name. */
data class ImportMeta(val title: String, val author: String)

/**
 * Copies a picked file into private storage and prepares it for reading. Books are keyed by a
 * hash of their bytes, so importing the same file twice just returns the existing book.
 */
class BookImporter(private val context: Context, private val library: LibraryRepository) {

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}")
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException("Cannot read $name")
            DigestInputStream(input, digest).use { source ->
                tmp.outputStream().use { source.copyTo(it) }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
            library.get(id)?.let { return@withContext it }

            val format = detectFormat(tmp, name, mime) ?: throw ImportException("Unsupported file $name")
            val dir = library.bookDir(id)
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                val meta = when (format) {
                    BookFormat.EPUB -> EpubImporter.import(tmp, dir, library.coverFile(id))
                    BookFormat.PDF -> PdfImporter.import(tmp, dir, library.coverFile(id))
                    BookFormat.TXT -> TxtImporter.import(tmp, dir)
                    BookFormat.MARKDOWN -> {
                        // Keep the sources (a .md file, or a .zip of episodes) so episodes can be
                        // added to this book later.
                        val sources = sourcesDir(dir)
                        MarkdownSources.store(tmp, name, sources)
                        buildMarkdown(dir, sources, id)
                    }
                    BookFormat.HTML -> HtmlBookImporter.importHtml(tmp, dir)
                }
                val book = Book(
                    id = id,
                    title = meta.title.ifBlank { name.substringBeforeLast('.').ifBlank { name } },
                    author = meta.author,
                    format = format,
                    addedAt = System.currentTimeMillis(),
                )
                library.add(book)
                book
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * True when every file is Markdown or a .zip of Markdown files, so they can become (or be
     * added to) one book. Zips are peeked at, which reads only their file names.
     */
    suspend fun allMarkdown(uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        uris.isNotEmpty() && uris.all { uri ->
            val name = displayName(uri)
            val mime = context.contentResolver.getType(uri)
            when {
                MarkdownSources.isMarkdownName(name) || mime == "text/markdown" || mime == "text/x-markdown" -> true
                name.endsWith(".zip", ignoreCase = true) || mime == "application/zip" -> runCatching {
                    MarkdownSources.zipHasMarkdown { context.contentResolver.openInputStream(uri) ?: throw ImportException(name) }
                }.getOrDefault(false)
                else -> false
            }
        }
    }

    /** Several Markdown files (one per episode) become a single book, one chapter per file. */
    suspend fun importMarkdownSeries(uris: List<Uri>): Book = withContext(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val dir = library.bookDir(id)
        dir.mkdirs()
        try {
            val sources = sourcesDir(dir).apply { mkdirs() }
            val names = uris.map { uri -> copySource(uri, sources) }
            val meta = buildMarkdown(dir, sources, id)
            val book = Book(
                id = id,
                title = meta.title.ifBlank { suggestTitle(names) },
                author = meta.author,
                format = BookFormat.MARKDOWN,
                addedAt = System.currentTimeMillis(),
            )
            library.add(book)
            book
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * Adds episodes to a Markdown book. A file with the same name as an existing episode
     * replaces it, so an edited episode can simply be added again.
     */
    suspend fun addMarkdownEpisodes(book: Book, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val dir = library.bookDir(book.id)
        val sources = sourcesDir(dir)
        if (!sources.isDirectory) throw ImportException("This book was imported before episodes could be added")
        uris.forEach { copySource(it, sources) }
        buildMarkdown(dir, sources, book.id)
        CoverCache.evict(book.id)
        library.touch(book.id)
        uris.size
    }

    /** Whether episodes can be added to [book] (it keeps its Markdown sources). */
    fun canAddEpisodes(book: Book): Boolean =
        book.format == BookFormat.MARKDOWN && sourcesDir(library.bookDir(book.id)).isDirectory

    private fun sourcesDir(bookDir: File) = File(bookDir, "sources")

    private fun buildMarkdown(dir: File, sources: File, id: String): ImportMeta {
        val meta = HtmlBookImporter.buildMarkdownBook(MarkdownSources.markdownFiles(sources), dir, assetRoot = sources)
        MarkdownSources.findCover(sources)?.let { runCatching { it.copyTo(library.coverFile(id), overwrite = true) } }
        return meta
    }

    /** Stores one shared/picked file (a .md, or a .zip of episodes) and returns its base name. */
    private fun copySource(uri: Uri, sources: File): String {
        val name = displayName(uri)
        val tmp = File(context.cacheDir, "source-${System.nanoTime()}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Cannot read $name")
            input.use { source -> tmp.outputStream().use { source.copyTo(it) } }
            MarkdownSources.store(tmp, name, sources)
        } finally {
            tmp.delete()
        }
        return name.substringBeforeLast('.')
    }

    /** "ep01", "ep02" → nothing useful; "มังกรเหนือ-01", "มังกรเหนือ-02" → "มังกรเหนือ". */
    private fun suggestTitle(names: List<String>): String {
        val prefix = names.reduce { a, b -> a.commonPrefixWith(b) }
            .trimEnd { it.isDigit() || it in " -_.#" }
            .replace(Regex("(?i)(ep|episode|chapter|ch|ตอนที่|ตอน|บทที่|บท)$"), "")
            .trimEnd { it in " -_.#" }
        return if (prefix.length >= 2) prefix else names.minWithOrNull(HtmlBookImporter.NaturalOrder).orEmpty()
    }

    fun displayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
    }

    private fun detectFormat(file: File, name: String, mime: String?): BookFormat? {
        val head = ByteArray(5)
        val read = file.inputStream().use { it.read(head) }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (read >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "%PDF") return BookFormat.PDF
        if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return when {
                ext == "epub" || mime == "application/epub+zip" || EpubImporter.looksLikeEpub(file) -> BookFormat.EPUB
                // A zip of Markdown episodes (and their images) becomes one book.
                MarkdownSources.zipHasMarkdown(file) -> BookFormat.MARKDOWN
                else -> null
            }
        }
        if (ext in MarkdownSources.MARKDOWN_EXTENSIONS || mime == "text/markdown" || mime == "text/x-markdown") {
            return BookFormat.MARKDOWN
        }
        if (ext in setOf("html", "htm", "xhtml") || mime == "text/html" || mime == "application/xhtml+xml") {
            return BookFormat.HTML
        }
        if (ext == "txt" || mime?.startsWith("text/") == true) return BookFormat.TXT
        return null
    }

}
