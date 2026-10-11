package com.ykasorns.ebookreader.importer

import android.util.Xml
import com.ykasorns.ebookreader.data.SpineItem
import com.ykasorns.ebookreader.data.TocEntry
import com.ykasorns.ebookreader.data.WebManifest
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** Unpacks an EPUB (2 or 3) and records its reading order, table of contents and cover. */
object EpubImporter {

    private class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String) {
        val isDocument: Boolean
            get() = mediaType.contains("html") ||
                href.endsWith(".xhtml", true) || href.endsWith(".html", true) || href.endsWith(".htm", true)
        val isImage: Boolean get() = mediaType.startsWith("image/")
    }

    private class Package(
        val title: String,
        val author: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val tocId: String?,
        val coverId: String?,
    )

    fun looksLikeEpub(file: File): Boolean = runCatching {
        ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
    }.getOrDefault(false)

    fun import(epub: File, bookDir: File, coverFile: File): ImportMeta {
        val content = File(bookDir, WebManifest.CONTENT_DIR)
        unzip(epub, content)

        val opfPath = findPackagePath(content) ?: throw ImportException("Missing EPUB package document")
        val opfDir = parentDir(opfPath)
        val pkg = File(content, opfPath).inputStream().use { parsePackage(newParser(it)) }

        val spine = pkg.spine
            .mapNotNull { pkg.manifest[it] }
            .filter { it.isDocument }
            .map { resolvePath(opfDir, it.href) }
            .distinct()
            .mapNotNull { path -> File(content, path).takeIf { it.isFile }?.let { SpineItem(path, it.length()) } }
        if (spine.isEmpty()) throw ImportException("The book has no readable chapters")

        val toc = runCatching { readToc(content, opfDir, pkg) }.getOrDefault(emptyList())
        WebManifest(spine, toc).write(bookDir)

        val cover = pkg.manifest.values.firstOrNull { it.isImage && it.properties.split(' ').contains("cover-image") }
            ?: pkg.coverId?.let { pkg.manifest[it] }?.takeIf { it.isImage }
            ?: pkg.manifest.values.firstOrNull {
                it.isImage && (it.id.contains("cover", true) || it.href.contains("cover", true))
            }
        if (cover != null) {
            runCatching { File(content, resolvePath(opfDir, cover.href)).copyTo(coverFile, overwrite = true) }
        }
        return ImportMeta(pkg.title, pkg.author)
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val out = File(dest, entry.name).canonicalFile
                if (!out.path.startsWith(root)) continue // zip-slip guard
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun newParser(input: java.io.InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Tolerate HTML entities such as &nbsp; in navigation documents.
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(input, null)
    }

    private val XmlPullParser.localName: String get() = name.substringAfter(':')

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == name || getAttributeName(i).substringAfter(':') == name) {
                return getAttributeValue(i)
            }
        }
        return null
    }

    /** Text content of the current element, including nested elements. */
    private fun XmlPullParser.readText(): String {
        val startDepth = getDepth()
        val text = StringBuilder()
        while (true) {
            when (next()) {
                XmlPullParser.TEXT -> text.append(getText())
                XmlPullParser.END_TAG -> if (getDepth() == startDepth) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return text.toString().cleanWhitespace()
    }

    private fun String.cleanWhitespace() = replace(Regex("\\s+"), " ").trim()

    private fun findPackagePath(content: File): String? {
        val container = File(content, "META-INF/container.xml")
        if (container.isFile) {
            container.inputStream().use { input ->
                val parser = newParser(input)
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.localName == "rootfile") {
                        parser.attr("full-path")?.let { return resolvePath("", it) }
                    }
                }
            }
        }
        // Some broken books have no container; look for any .opf file.
        return content.walkTopDown().firstOrNull { it.isFile && it.extension.equals("opf", true) }
            ?.relativeTo(content)?.invariantSeparatorsPath
    }

    private fun parsePackage(parser: XmlPullParser): Package {
        var title = ""
        var author = ""
        var tocId: String? = null
        var coverId: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.localName) {
                "title" -> if (title.isEmpty()) title = parser.readText()
                "creator" -> if (author.isEmpty()) author = parser.readText()
                "meta" -> if (parser.attr("name") == "cover") coverId = parser.attr("content")
                "item" -> {
                    val id = parser.attr("id")
                    val href = parser.attr("href")
                    if (id != null && href != null) {
                        manifest[id] = ManifestItem(id, href, parser.attr("media-type").orEmpty(), parser.attr("properties").orEmpty())
                    }
                }
                "spine" -> tocId = parser.attr("toc")
                "itemref" -> parser.attr("idref")?.let(spine::add)
            }
        }
        return Package(title, author, manifest, spine, tocId, coverId)
    }

    private fun readToc(content: File, opfDir: String, pkg: Package): List<TocEntry> {
        val nav = pkg.manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        if (nav != null) {
            val path = resolvePath(opfDir, nav.href)
            val entries = File(content, path).inputStream().use { parseNav(newParser(it), parentDir(path)) }
            if (entries.isNotEmpty()) return entries
        }
        val ncx = pkg.tocId?.let { pkg.manifest[it] }
            ?: pkg.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncx != null) {
            val path = resolvePath(opfDir, ncx.href)
            return File(content, path).inputStream().use { parseNcx(newParser(it), parentDir(path)) }
        }
        return emptyList()
    }

    /** EPUB 3 navigation document: <nav epub:type="toc"><ol><li><a href>…</a>. */
    private fun parseNav(parser: XmlPullParser, baseDir: String): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        var navDepth = -1
        var listDepth = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "nav" -> if (navDepth < 0) {
                        val type = parser.attr("type").orEmpty() + " " + parser.attr("role").orEmpty()
                        if (type.contains("toc")) navDepth = parser.depth
                    }
                    "ol" -> if (navDepth >= 0) listDepth++
                    "a" -> if (navDepth >= 0) {
                        val href = parser.attr("href")
                        val title = parser.readText()
                        val target = href?.let { resolveHref(baseDir, it) }
                        if (target != null && title.isNotEmpty()) {
                            entries += TocEntry(title, target, (listDepth - 1).coerceAtLeast(0))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "ol" -> if (navDepth >= 0) listDepth--
                    "nav" -> if (navDepth >= 0 && parser.depth == navDepth) return entries
                }
            }
        }
        return entries
    }

    /** EPUB 2 NCX: nested <navPoint><navLabel><text/></navLabel><content src/></navPoint>. */
    private fun parseNcx(parser: XmlPullParser, baseDir: String): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        var depth = -1
        var label: String? = null
        var inNavMap = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.localName) {
                    "navMap" -> inNavMap = true
                    "navPoint" -> if (inNavMap) {
                        depth++
                        label = null
                    }
                    "text" -> if (inNavMap && depth >= 0 && label == null) label = parser.readText()
                    "content" -> if (inNavMap && depth >= 0) {
                        val title = label
                        val target = parser.attr("src")?.let { resolveHref(baseDir, it) }
                        if (!title.isNullOrEmpty() && target != null) entries += TocEntry(title, target, depth)
                        label = ""
                    }
                }
                XmlPullParser.END_TAG -> when (parser.localName) {
                    "navPoint" -> if (inNavMap) depth--
                    "navMap" -> inNavMap = false
                }
            }
        }
        return entries
    }
}
