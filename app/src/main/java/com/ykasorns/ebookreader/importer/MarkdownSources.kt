package com.ykasorns.ebookreader.importer

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The original files of a Markdown book, kept under books/<id>/sources so the book can be
 * rebuilt when episodes are added. Files arrive one by one or inside a .zip (with folders and
 * images); either way the folder layout is kept so relative image links still work.
 */
object MarkdownSources {
    val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
    private val ASSET_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif")

    fun isMarkdownName(name: String) = name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS

    fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(2)
        input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    /**
     * File-name encodings to try. Zips made on Windows often store Thai names in the old Thai
     * code page instead of UTF-8, which Java rejects as malformed.
     */
    private val zipCharsets: List<Charset> = listOf("UTF-8", "windows-874", "x-windows-874", "TIS-620", "IBM437")
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
        .distinct()

    /** Whether a zip holds Markdown files (and is not an EPUB). Reads only entry names. */
    fun zipHasMarkdown(open: () -> InputStream): Boolean {
        for (charset in zipCharsets) {
            val result = runCatching {
                open().use { stream ->
                    ZipInputStream(stream, charset).use { zip ->
                        var markdown = false
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name.replace('\\', '/')
                            if (name == "META-INF/container.xml" || name == "mimetype") return@use false
                            if (!entry.isDirectory && !isJunk(name) && isMarkdownName(name)) markdown = true
                        }
                        markdown
                    }
                }
            }
            if (result.isSuccess) return result.getOrDefault(false)
        }
        return false
    }

    fun zipHasMarkdown(file: File): Boolean = zipHasMarkdown { file.inputStream() }

    private fun openZip(file: File): ZipFile {
        var error: Exception? = null
        for (charset in zipCharsets) {
            try {
                val zip = ZipFile(file, charset)
                // Names are decoded lazily; walk them once so a wrong charset fails here.
                zip.entries().toList().forEach { it.name }
                return zip
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: ImportException("Not a zip file")
    }

    /** Adds a Markdown file, or every Markdown file and image in a zip, to [sources]. */
    fun store(file: File, name: String, sources: File) {
        sources.mkdirs()
        if (isZip(file)) extractZip(file, sources) else placeMarkdown(file, safeName(name), sources)
    }

    /**
     * All Markdown files of a book in reading order: by file name in natural order (ep2 before
     * ep10), whichever folder or zip they came from, then by path.
     */
    fun markdownFiles(sources: File): List<File> =
        sources.walkTopDown()
            .filter { it.isFile && isMarkdownName(it.name) && !isJunk(it.relativeTo(sources).invariantSeparatorsPath) }
            .sortedWith(
                compareBy<File, String>(HtmlBookImporter.NaturalOrder) { it.name }
                    .thenBy(HtmlBookImporter.NaturalOrder) { it.relativeTo(sources).invariantSeparatorsPath },
            )
            .toList()

    /** A cover image shipped with the book (cover.jpg / cover.png …), if any. */
    fun findCover(sources: File): File? =
        sources.walkTopDown().firstOrNull {
            it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) &&
                it.extension.lowercase() in ASSET_EXTENSIONS
        }

    private fun isJunk(path: String): Boolean =
        path.split('/').any { it.startsWith(".") || it == "__MACOSX" || it.equals("Thumbs.db", true) }

    /** An episode with the same file name as an existing one replaces it, wherever it lives. */
    private fun placeMarkdown(file: File, fileName: String, sources: File) {
        val target = sources.walkTopDown().firstOrNull { it.isFile && it.name == fileName } ?: File(sources, fileName)
        target.parentFile?.mkdirs()
        file.copyTo(target, overwrite = true)
    }

    private fun extractZip(zip: File, sources: File) {
        val root = sources.canonicalPath + File.separator
        openZip(zip).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (isJunk(path)) continue
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext !in MARKDOWN_EXTENSIONS && ext !in ASSET_EXTENSIONS) continue
                val fileName = path.substringAfterLast('/')
                var target = File(sources, path).canonicalFile
                if (!target.path.startsWith(root)) continue // zip-slip guard
                if (ext in MARKDOWN_EXTENSIONS) {
                    // Updating a book with a new zip: replace the same episode where it already is.
                    sources.walkTopDown().firstOrNull { it.isFile && it.name == fileName }?.let { target = it }
                }
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "episode" }
        return if (isMarkdownName(cleaned)) cleaned else "$cleaned.md"
    }
}
