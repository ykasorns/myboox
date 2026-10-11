package com.ykasorns.ebookreader.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One HTML document in reading order. [href] is relative to the book's content folder. */
data class SpineItem(val href: String, val size: Long)

/** A table-of-contents entry. [href] may carry a "#fragment". */
data class TocEntry(val title: String, val href: String, val depth: Int) {
    val path: String get() = href.substringBefore('#')
    val fragment: String get() = href.substringAfter('#', "")
}

/**
 * Reading order and table of contents for books rendered in the WebView (EPUB and TXT).
 * Written once at import time so opening a book never has to re-parse the EPUB.
 */
data class WebManifest(val spine: List<SpineItem>, val toc: List<TocEntry>) {
    private val indexByPath: Map<String, Int> by lazy {
        spine.withIndex().associate { it.value.href to it.index }
    }

    fun indexOf(path: String): Int = indexByPath[path] ?: -1

    /** The title of the chapter that each spine item belongs to. */
    fun chapterTitles(entries: List<TocEntry>): List<String?> {
        val first = arrayOfNulls<String>(spine.size)
        entries.forEach { entry ->
            val index = indexOf(entry.path)
            if (index >= 0 && first[index] == null) first[index] = entry.title
        }
        var last: String? = null
        return first.map { title ->
            if (title != null) last = title
            last
        }
    }

    fun write(bookDir: File) {
        val json = JSONObject()
            .put("spine", JSONArray().apply {
                spine.forEach { put(JSONObject().put("href", it.href).put("size", it.size)) }
            })
            .put("toc", JSONArray().apply {
                toc.forEach {
                    put(JSONObject().put("title", it.title).put("href", it.href).put("depth", it.depth))
                }
            })
        File(bookDir, FILE_NAME).writeText(json.toString(), Charsets.UTF_8)
    }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val CONTENT_DIR = "content"

        fun read(bookDir: File): WebManifest? = try {
            val json = JSONObject(File(bookDir, FILE_NAME).readText(Charsets.UTF_8))
            val spine = json.getJSONArray("spine").let { array ->
                (0 until array.length()).map {
                    val o = array.getJSONObject(it)
                    SpineItem(o.getString("href"), o.optLong("size"))
                }
            }
            val toc = json.optJSONArray("toc")?.let { array ->
                (0 until array.length()).map {
                    val o = array.getJSONObject(it)
                    TocEntry(o.getString("title"), o.getString("href"), o.optInt("depth"))
                }
            }.orEmpty()
            WebManifest(spine, toc)
        } catch (e: Exception) {
            null
        }
    }
}
