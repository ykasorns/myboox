package com.ykasorns.ebookreader.export

import com.ykasorns.ebookreader.data.TocEntry
import com.ykasorns.ebookreader.data.WebManifest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Entities
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a book that the app renders from HTML chapters (Markdown, HTML and TXT imports) as a
 * standard EPUB 3 file, with an EPUB 2 table of contents too so older readers and Kindle's
 * converter understand it. EPUB imports are re-packed as they came.
 */
object EpubWriter {
    class Metadata(val id: String, val title: String, val author: String)

    class Cover(val bytes: ByteArray, val mediaType: String, val extension: String)

    private class Item(val id: String, val href: String, val mediaType: String, val properties: String? = null)

    private val mediaTypes = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "css" to "text/css",
    )

    /** Re-packs an imported EPUB (kept unzipped in the book's content folder). */
    fun repack(contentDir: File, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            writeMimetype(zip)
            contentDir.walkTopDown()
                .filter { it.isFile }
                .map { it to it.relativeTo(contentDir).invariantSeparatorsPath }
                .filter { (_, path) -> path != "mimetype" }
                .forEach { (file, path) -> putFile(zip, path, file) }
        }
    }

    fun write(bookDir: File, metadata: Metadata, cover: Cover?, out: OutputStream) {
        val manifest = WebManifest.read(bookDir) ?: throw IllegalStateException("Book has no content")
        val content = File(bookDir, WebManifest.CONTENT_DIR)
        val language = if (Regex("[฀-๿]").containsMatchIn(metadata.title + manifest.toc.joinToString { it.title })) "th" else "en"
        val items = mutableListOf<Item>()
        val spine = mutableListOf<String>()

        // Chapter files keep their names, with .xhtml instead of .html.
        val renamed = manifest.spine.associate { it.href to xhtmlName(it.href) }
        // Images get plain names (img001.png): Thai letters or spaces in file names trip up
        // some reading apps.
        val assets = content.walkTopDown()
            .filter { it.isFile && it.relativeTo(content).invariantSeparatorsPath.startsWith("assets/") }
            .filter { mediaTypes.containsKey(it.extension.lowercase()) }
            .toList()
        val assetNames = assets.withIndex().associate { (i, file) ->
            file.relativeTo(content).invariantSeparatorsPath to "images/img%03d.%s".format(i + 1, file.extension.lowercase())
        }
        val titles = manifest.chapterTitles(manifest.toc)

        ZipOutputStream(out).use { zip ->
            writeMimetype(zip)
            putText(zip, "META-INF/container.xml", CONTAINER)

            if (cover != null) {
                val imageHref = "images/cover.${cover.extension}"
                putBytes(zip, "OEBPS/$imageHref", cover.bytes)
                items += Item("cover-image", imageHref, cover.mediaType, "cover-image")
                putText(zip, "OEBPS/cover.xhtml", coverPage(metadata.title, imageHref, language))
                items += Item("cover", "cover.xhtml", "application/xhtml+xml", "svg")
                spine += "cover"
            }

            manifest.spine.forEachIndexed { index, item ->
                val source = File(content, item.href)
                if (!source.isFile) return@forEachIndexed
                val name = renamed.getValue(item.href)
                val chapterTitle = titles.getOrNull(index) ?: metadata.title
                putText(zip, "OEBPS/$name", toXhtml(source, chapterTitle, language, renamed, assetNames))
                val id = "c%03d".format(index + 1)
                items += Item(id, encodeHref(name), "application/xhtml+xml")
                spine += id
            }

            // Images the chapters use (assets/… for zipped Markdown books).
            assets.forEachIndexed { index, file ->
                val name = assetNames.getValue(file.relativeTo(content).invariantSeparatorsPath)
                putFile(zip, "OEBPS/$name", file)
                items += Item("a%03d".format(index + 1), name, mediaTypes.getValue(file.extension.lowercase()))
            }

            val toc = manifest.toc.ifEmpty {
                manifest.spine.mapIndexed { i, item -> TocEntry(titles.getOrNull(i) ?: "${i + 1}", item.href, 0) }
            }.map { TocEntry(it.title, renamed[it.path]?.let { name -> encodeHref(name) + fragment(it) } ?: it.href, it.depth) }

            putText(zip, "OEBPS/nav.xhtml", nav(metadata.title, toc, language))
            items += Item("nav", "nav.xhtml", "application/xhtml+xml", "nav")
            putText(zip, "OEBPS/toc.ncx", ncx(metadata, toc))
            items += Item("ncx", "toc.ncx", "application/x-dtbncx+xml")
            putText(zip, "OEBPS/content.opf", opf(metadata, language, items, spine, cover != null))
        }
    }

    private fun xhtmlName(href: String) = href.substringBeforeLast('.') + ".xhtml"

    private fun fragment(entry: TocEntry) = if (entry.fragment.isEmpty()) "" else "#" + entry.fragment

    /** Turns one of our HTML chapters into well-formed XHTML for the EPUB. */
    private fun toXhtml(
        source: File,
        title: String,
        language: String,
        renamed: Map<String, String>,
        assetNames: Map<String, String>,
    ): String {
        val document = Jsoup.parse(source, "UTF-8")
        document.select("img[src]").forEach { image ->
            val src = runCatching { java.net.URLDecoder.decode(image.attr("src").replace("+", "%2B"), "UTF-8") }
                .getOrDefault(image.attr("src"))
            assetNames[src]?.let { image.attr("src", it) }
        }
        document.select("script, noscript, iframe, object, embed").remove()
        document.childNodes().filterIsInstance<DocumentType>().forEach { it.remove() }
        // Links between chapters point at the .xhtml files.
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val path = href.substringBefore('#')
            renamed[path]?.let { link.attr("href", encodeHref(it) + href.substring(path.length)) }
        }
        document.title(title)
        val html = document.selectFirst("html")!!
        html.attr("xmlns", "http://www.w3.org/1999/xhtml")
        html.attr("xmlns:epub", "http://www.idpf.org/2007/ops")
        html.attr("lang", language)
        html.attr("xml:lang", language)
        document.select("meta[charset]").remove()
        document.head().prependElement("meta").attr("charset", "utf-8")
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" + html.outerHtml()
    }

    private fun coverPage(title: String, image: String, language: String) = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE html>
        |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$language" xml:lang="$language">
        |<head><meta charset="utf-8"/><title>${xml(title)}</title>
        |<style>html,body{margin:0;padding:0;height:100%;text-align:center}svg{width:100%;height:100%}</style></head>
        |<body epub:type="cover"><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" width="100%" height="100%" viewBox="0 0 1200 1800" preserveAspectRatio="xMidYMid meet">
        |<image width="1200" height="1800" xlink:href="$image"/></svg></body></html>
    """.trimMargin()

    /** EPUB 3 navigation document. Flat entries with depths become nested lists. */
    private fun nav(title: String, toc: List<TocEntry>, language: String): String {
        val list = StringBuilder("<ol>")
        var level = 0
        toc.forEachIndexed { i, entry ->
            val depth = if (i == 0) 0 else minOf(entry.depth, level + 1).coerceAtLeast(0)
            if (i > 0) {
                if (depth > level) {
                    list.append("<ol>")
                } else {
                    list.append("</li>")
                    repeat(level - depth) { list.append("</ol></li>") }
                }
            }
            level = depth
            list.append("<li><a href=\"").append(xml(entry.href)).append("\">").append(xml(entry.title)).append("</a>")
        }
        list.append("</li>")
        repeat(level) { list.append("</ol></li>") }
        list.append("</ol>")
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$language" xml:lang="$language">
            |<head><meta charset="utf-8"/><title>${xml(title)}</title></head>
            |<body><nav epub:type="toc" id="toc"><h1>${if (language == "th") "สารบัญ" else "Contents"}</h1>$list</nav></body></html>
        """.trimMargin()
    }

    /** EPUB 2 table of contents (still used by Kindle conversion and older readers). */
    private fun ncx(metadata: Metadata, toc: List<TocEntry>): String {
        val points = StringBuilder()
        var order = 0
        var level = -1
        toc.forEachIndexed { i, entry ->
            val depth = if (i == 0) 0 else minOf(entry.depth, level + 1).coerceAtLeast(0)
            if (i > 0) repeat(level - depth + 1) { points.append("</navPoint>") }
            level = depth
            order++
            points.append("<navPoint id=\"p$order\" playOrder=\"$order\"><navLabel><text>")
                .append(xml(entry.title)).append("</text></navLabel><content src=\"")
                .append(xml(entry.href)).append("\"/>")
        }
        repeat(level + 1) { points.append("</navPoint>") }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            |<head><meta name="dtb:uid" content="${uuid(metadata.id)}"/><meta name="dtb:depth" content="${(toc.maxOfOrNull { it.depth } ?: 0) + 1}"/>
            |<meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
            |<docTitle><text>${xml(metadata.title)}</text></docTitle>
            |<navMap>$points</navMap></ncx>
        """.trimMargin()
    }

    private fun opf(metadata: Metadata, language: String, items: List<Item>, spine: List<String>, hasCover: Boolean): String {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val manifest = items.joinToString("\n") { item ->
            val properties = item.properties?.let { " properties=\"$it\"" }.orEmpty()
            "<item id=\"${item.id}\" href=\"${xml(item.href)}\" media-type=\"${item.mediaType}\"$properties/>"
        }
        val itemrefs = spine.joinToString("\n") { "<itemref idref=\"$it\"/>" }
        val creator = if (metadata.author.isBlank()) "" else "<dc:creator>${xml(metadata.author)}</dc:creator>"
        val coverMeta = if (hasCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$language">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            |<dc:identifier id="bookid">${uuid(metadata.id)}</dc:identifier>
            |<dc:title>${xml(metadata.title)}</dc:title>
            |$creator
            |<dc:language>$language</dc:language>
            |<meta property="dcterms:modified">$modified</meta>
            |$coverMeta
            |</metadata>
            |<manifest>
            |$manifest
            |</manifest>
            |<spine toc="ncx">
            |$itemrefs
            |</spine>
            |</package>
        """.trimMargin()
    }

    private fun uuid(id: String) = "urn:uuid:" + UUID.nameUUIDFromBytes(("myboox:$id").toByteArray())

    private fun xml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Percent-encodes each path segment (spaces, Thai letters …) for use in hrefs. */
    private fun encodeHref(path: String) = path.split('/').joinToString("/") { segment ->
        buildString {
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if ((c < 0x80 && ch.isLetterOrDigit()) || ch in "-._~") append(ch) else append("%%%02X".format(c))
            }
        }
    }

    /** The first entry must be "mimetype", uncompressed, so readers can recognise the file. */
    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putText(zip: ZipOutputStream, path: String, text: String) = putBytes(zip, path, text.toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private val CONTAINER = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        |<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        |</container>
    """.trimMargin()
}
