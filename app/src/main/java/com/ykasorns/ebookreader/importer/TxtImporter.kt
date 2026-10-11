package com.ykasorns.ebookreader.importer

import com.ykasorns.ebookreader.data.SpineItem
import com.ykasorns.ebookreader.data.TocEntry
import com.ykasorns.ebookreader.data.WebManifest
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns a plain-text file into a few HTML documents so it can be paginated exactly like an
 * EPUB. Lines that look like chapter headings ("Chapter 1", "บทที่ 1" …) become the contents.
 */
object TxtImporter {
    private const val CHUNK_CHARS = 40_000
    private const val MIN_CHAPTER_CHARS = 2_000

    private val heading = Regex(
        "^(chapter|part|prologue|epilogue|บทที่|ตอนที่|บทนำ|บทส่งท้าย|ปฐมบท)(\\s.*|[0-9๐-๙].*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun import(source: File, bookDir: File): ImportMeta {
        val text = decode(source.readBytes())
        val content = File(bookDir, WebManifest.CONTENT_DIR).apply { mkdirs() }
        val spine = mutableListOf<SpineItem>()
        val toc = mutableListOf<TocEntry>()
        val body = StringBuilder()
        var headings = 0

        fun chunkName() = "part%03d.html".format(spine.size + 1)
        fun flush() {
            if (body.isEmpty()) return
            val file = File(content, chunkName())
            file.writeText(page(body), Charsets.UTF_8)
            spine += SpineItem(file.name, file.length())
            body.setLength(0)
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val isHeading = line.length <= 80 && heading.matches(line)
            if ((isHeading && body.length >= MIN_CHAPTER_CHARS) || body.length >= CHUNK_CHARS) flush()
            if (isHeading) {
                val anchor = "h${headings++}"
                toc += TocEntry(line, "${chunkName()}#$anchor", 0)
                body.append("<h2 id=\"").append(anchor).append("\">").append(escape(line)).append("</h2>\n")
            } else {
                body.append("<p>").append(escape(line)).append("</p>\n")
            }
        }
        flush()
        if (spine.isEmpty()) throw ImportException("The file is empty")
        WebManifest(spine, toc).write(bookDir)
        return ImportMeta(title = "", author = "")
    }

    private fun page(body: CharSequence) = buildString {
        append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title></title><style>")
        append("p{margin:0;text-indent:2em}p+p{margin-top:.4em}")
        append("h2{text-align:center;font-size:1.3em;margin:1.5em 0 1em;text-indent:0}")
        append("</style></head><body>\n")
        append(body)
        append("</body></html>\n")
    }

    private fun escape(text: String) = buildString(text.length) {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c < ' ' && c != '\t' -> Unit
                else -> append(c)
            }
        }
    }

    /** UTF-8/UTF-16 first, then the legacy Thai code page, then Windows-1252. */
    internal fun decode(bytes: ByteArray): String {
        fun has(vararg prefix: Int) = bytes.size >= prefix.size &&
            prefix.indices.all { bytes[it] == prefix[it].toByte() }
        when {
            has(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            has(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            has(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        val thai = listOf("windows-874", "x-windows-874", "TIS-620")
            .firstNotNullOfOrNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
        if (thai != null && looksThai(bytes)) return String(bytes, thai)
        return String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Most high bytes in TIS-620 text fall in the Thai letter range 0xA1..0xFB. */
    private fun looksThai(bytes: ByteArray): Boolean {
        var high = 0
        var thai = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v >= 0x80) {
                high++
                if (v in 0xA1..0xFB) thai++
            }
        }
        return high > 0 && thai * 10 >= high * 8
    }
}
