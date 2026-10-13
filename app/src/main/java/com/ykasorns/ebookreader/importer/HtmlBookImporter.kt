package com.ykasorns.ebookreader.importer

import com.ykasorns.ebookreader.data.SpineItem
import com.ykasorns.ebookreader.data.TocEntry
import com.ykasorns.ebookreader.data.WebManifest
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File

/**
 * Turns a Markdown or HTML manuscript into a book: it is split into chapters at its headings
 * (# / <h1>, or ## / <h2> when there is only one top heading), the headings become the table
 * of contents, and the chapters are paginated exactly like an EPUB.
 */
object HtmlBookImporter {
    private const val MAX_CHAPTER_CHARS = 250_000
    private val containers = setOf("div", "article", "main", "section")
    private val headingTags = (1..6).map { "h$it" }.toSet()

    private class Markdown(val html: String, val title: String, val author: String)

    private fun renderMarkdown(source: File): Markdown {
        val text = TxtImporter.decode(source.readBytes())
        val extensions = listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            YamlFrontMatterExtension.create(),
        )
        val document = Parser.builder().extensions(extensions).build().parse(text)
        // Optional front matter: ---\ntitle: ...\nauthor: ...\n---
        val frontMatter = YamlFrontMatterVisitor().also { document.accept(it) }.data
        fun field(name: String) = frontMatter[name]?.firstOrNull().orEmpty().trim().trim('"', '\'')
        // A single line break starts a new line, as in Obsidian, Typora and most writing apps.
        val html = HtmlRenderer.builder().extensions(extensions).softbreak("<br>\n").build().render(document)
        return Markdown(html, field("title"), field("author"))
    }

    fun importMarkdown(source: File, bookDir: File): ImportMeta = buildMarkdownBook(listOf(source), bookDir)

    /**
     * Builds a book from one or more Markdown files. With several files (one per episode,
     * e.g. ep01.md, ep02.md …) every file becomes its own chapter, in natural file-name order.
     * Rebuilding replaces the previous content, so episodes can be added or updated later.
     */
    fun buildMarkdownBook(sources: List<File>, bookDir: File): ImportMeta {
        if (sources.isEmpty()) throw ImportException("No Markdown files")
        File(bookDir, WebManifest.CONTENT_DIR).deleteRecursively()
        val ordered = sources.sortedWith(compareBy(NaturalOrder) { it.name })
        val parts = ordered.map { renderMarkdown(it) }
        val title = parts.firstNotNullOfOrNull { part -> part.title.takeIf { it.isNotBlank() } }.orEmpty()
        val author = parts.firstNotNullOfOrNull { part -> part.author.takeIf { it.isNotBlank() } }.orEmpty()
        if (parts.size == 1) {
            return split(Jsoup.parseBodyFragment(parts[0].html), extraHead = "", title = title, author = author, bookDir = bookDir)
        }
        val joined = parts.joinToString("\n") { "<section class=\"md-part\">${it.html}</section>" }
        return split(Jsoup.parseBodyFragment(joined), extraHead = "", title = title, author = author, bookDir = bookDir, filesAreChapters = true)
    }

    /** Orders "ep2" before "ep10" by comparing runs of digits as numbers. */
    object NaturalOrder : Comparator<String> {
        private val chunk = Regex("\\d+|\\D+")

        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val result = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                        ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else {
                    p.compareTo(q)
                }
                if (result != 0) return result
            }
            return x.size.compareTo(y.size)
        }
    }

    fun importHtml(source: File, bookDir: File): ImportMeta {
        // Jsoup picks the charset from a BOM or <meta charset>, defaulting to UTF-8.
        val document = Jsoup.parse(source, null)
        document.select("script, noscript, iframe, object, embed, link").remove()
        val styles = document.head().select("style").joinToString("\n") { it.outerHtml() }
        val author = document.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        return split(document, extraHead = styles, title = document.title(), author = author, bookDir = bookDir)
    }

    private fun split(
        document: Document,
        extraHead: String,
        title: String,
        author: String,
        bookDir: File,
        filesAreChapters: Boolean = false,
    ): ImportMeta {
        // Step into wrapper elements such as <div id="book"><main>…</main></div>.
        var root: Element = document.body()
        while (!filesAreChapters) {
            val elements = root.children()
            val onlyChild = elements.singleOrNull() ?: break
            val hasOtherText = root.childNodes().any { it is TextNode && !it.isBlank }
            if (onlyChild.tagName() !in containers || hasOtherText) break
            root = onlyChild
        }

        // Give every heading an id the table of contents can point to.
        var counter = 0
        val usedIds = HashSet<String>()
        root.select(headingTags.joinToString(",")).forEach { heading ->
            val id = heading.id()
            if (id.isBlank() || !usedIds.add(id)) {
                val fresh = "h${counter++}"
                heading.id(fresh)
                usedIds += fresh
            }
        }

        val h1Count = root.select("h1").size
        val h2Count = root.select("h2").size
        val splitLevel = when {
            h1Count >= 2 -> 1
            h2Count >= 2 -> 2
            else -> 0
        }
        // A single <h1> above several <h2> chapters is the book's title. A single <h1> on its
        // own is just the heading of the only chapter (e.g. "ตอนที่ 1").
        val bookTitle = title.ifBlank { if (h1Count == 1 && splitLevel == 2) root.selectFirst("h1")?.text().orEmpty() else "" }

        val chapters = mutableListOf<MutableList<Node>>()
        if (filesAreChapters) {
            root.children().filter { it.hasClass("md-part") }.forEach { part ->
                if (part.childNodes().any { it.hasContent() }) chapters += part.childNodes().toMutableList()
            }
        }
        var current = mutableListOf<Node>()
        var currentSize = 0
        for (node in if (filesAreChapters) emptyList() else root.childNodes().toList()) {
            val size = node.outerHtml().length
            val boundary = (splitLevel > 0 && startsChapter(node, splitLevel)) || currentSize + size > MAX_CHAPTER_CHARS
            if (boundary && current.any { it.hasContent() }) {
                chapters += current
                current = mutableListOf()
                currentSize = 0
            }
            current += node
            currentSize += size
        }
        if (current.any { it.hasContent() }) chapters += current
        if (chapters.isEmpty()) throw ImportException("The file is empty")

        // In-book links (#id) must name the chapter file the target ended up in.
        val fileOf = HashMap<String, String>()
        chapters.forEachIndexed { index, nodes ->
            val name = chapterName(index)
            for (node in nodes) {
                if (node !is Element) continue
                if (node.id().isNotBlank()) fileOf.putIfAbsent(node.id(), name)
                node.select("[id]").forEach { fileOf.putIfAbsent(it.id(), name) }
            }
        }
        for (nodes in chapters) {
            for (node in nodes) {
                if (node !is Element) continue
                val links = if (node.tagName() == "a") listOf(node) else node.select("a[href^=#]")
                for (link in links) {
                    val href = link.attr("href")
                    if (!href.startsWith("#")) continue
                    fileOf[href.substring(1)]?.let { link.attr("href", "$it$href") }
                }
            }
        }

        val content = File(bookDir, WebManifest.CONTENT_DIR).apply { mkdirs() }
        val baseLevel = if (splitLevel > 0 && !filesAreChapters) {
            splitLevel
        } else {
            (1..6).firstOrNull { root.select("h$it").isNotEmpty() } ?: 1
        }
        val spine = mutableListOf<SpineItem>()
        val toc = mutableListOf<TocEntry>()
        chapters.forEachIndexed { index, nodes ->
            val name = chapterName(index)
            val body = nodes.joinToString("") { it.outerHtml() }
            val file = File(content, name)
            file.writeText(page(bookTitle, extraHead, body), Charsets.UTF_8)
            spine += SpineItem(name, file.length())
            // Headings at the chapter level and one level below go into the contents.
            for (node in nodes) {
                if (node !is Element) continue
                val headings = if (node.tagName() in headingTags) listOf(node) else node.select(headingTags.joinToString(","))
                for (heading in headings) {
                    val level = heading.tagName().substring(1).toInt()
                    val text = heading.text().trim()
                    if (level < baseLevel || level > baseLevel + 1 || text.isEmpty()) continue
                    toc += TocEntry(text, "$name#${heading.id()}", level - baseLevel)
                }
            }
        }
        WebManifest(spine, toc).write(bookDir)
        return ImportMeta(title = bookTitle, author = author)
    }

    private fun chapterName(index: Int) = "chapter%03d.html".format(index + 1)

    private fun Node.hasContent(): Boolean = when (this) {
        is TextNode -> !isBlank
        is Element -> hasText() || select("img, svg, hr, table").isNotEmpty()
        else -> false
    }

    private fun startsChapter(node: Node, level: Int): Boolean {
        if (node !is Element) return false
        val tag = "h$level"
        if (node.tagName() == tag) return true
        // <section><h2>Chapter</h2>…</section>
        if (node.tagName() in containers) {
            return node.children().firstOrNull { it.tagName() in headingTags }?.tagName() == tag
        }
        return false
    }

    private fun page(title: String, extraHead: String, body: String) = buildString {
        append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>")
        append(org.jsoup.nodes.Entities.escape(title))
        append("</title><style>")
        append(BOOK_CSS)
        append("</style>")
        // The manuscript's own styles come after ours so they win.
        append(extraHead)
        append("</head><body>\n")
        append(body)
        append("\n</body></html>\n")
    }

    /** Book typography for manuscripts that bring no styling of their own. */
    private const val BOOK_CSS = """
p{margin:0 0 .85em}
h1,h2,h3,h4{line-height:1.3;margin:1.4em 0 .6em;break-after:avoid}
h1{font-size:1.6em;text-align:center;margin-top:1.6em}
h2{font-size:1.3em}
h3{font-size:1.12em}
blockquote{margin:1em 1.4em;font-style:italic}
pre,code{font-family:monospace;font-size:.88em}
pre{white-space:pre-wrap;word-wrap:break-word}
table{border-collapse:collapse;margin:1em 0}
th,td{border:1px solid currentColor;padding:.3em .6em}
hr{border:none;margin:1.6em 0;text-align:center}
hr:after{content:"⁂";letter-spacing:.5em}
img{max-width:100%;height:auto}
li{margin:.2em 0}
"""
}
