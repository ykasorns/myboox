package com.ykasorns.ebookreader.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.BookFormat
import com.ykasorns.ebookreader.data.LibraryRepository
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
            return if (ext == "epub" || mime == "application/epub+zip" || EpubImporter.looksLikeEpub(file)) {
                BookFormat.EPUB
            } else {
                null
            }
        }
        if (ext == "txt" || mime?.startsWith("text/") == true) return BookFormat.TXT
        return null
    }
}
