package com.ykasorns.ebookreader.data

import org.json.JSONObject

enum class BookFormat { EPUB, PDF, TXT }

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val addedAt: Long,
    val lastOpenedAt: Long = 0L,
    /** Overall reading progress, 0..1. */
    val progress: Float = 0f,
    /** Format-specific location: "chapter:fraction" for EPUB/TXT, page index for PDF. */
    val position: String = "",
) {
    val isNew: Boolean get() = lastOpenedAt == 0L && progress <= 0f
    val isFinished: Boolean get() = progress >= 0.995f

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("format", format.name)
        .put("addedAt", addedAt)
        .put("lastOpenedAt", lastOpenedAt)
        .put("progress", progress.toDouble())
        .put("position", position)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.optString("title"),
            author = o.optString("author"),
            format = runCatching { BookFormat.valueOf(o.getString("format")) }.getOrDefault(BookFormat.EPUB),
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            position = o.optString("position"),
        )
    }
}
