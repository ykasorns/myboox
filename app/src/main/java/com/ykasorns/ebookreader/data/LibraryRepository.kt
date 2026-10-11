package com.ykasorns.ebookreader.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Stores the list of books in a small JSON file. Every imported book gets its own folder
 * under files/books/<id>/ holding the extracted content and cover.
 *
 * Mutating calls write to disk, so call them off the main thread.
 */
class LibraryRepository(context: Context) {
    private val booksRoot = File(context.filesDir, BOOKS_DIR)
    private val store = AtomicFile(File(context.filesDir, "library.json"))
    private val lock = Any()
    private val _books = MutableStateFlow(load())

    val books: StateFlow<List<Book>> = _books.asStateFlow()

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun bookDir(id: String) = File(booksRoot, id)

    fun coverFile(id: String) = File(bookDir(id), "cover")

    fun add(book: Book) = mutate { list -> listOf(book) + list.filterNot { it.id == book.id } }

    fun markOpened(id: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it }
    }

    fun updatePosition(id: String, progress: Float, position: String) = mutate { list ->
        list.map {
            if (it.id == id && (it.position != position || it.progress != progress)) {
                it.copy(progress = progress, position = position)
            } else {
                it
            }
        }
    }

    fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        bookDir(id).deleteRecursively()
    }

    private fun mutate(block: (List<Book>) -> List<Book>) {
        synchronized(lock) {
            val current = _books.value
            val next = block(current)
            if (next == current) return
            _books.value = next
            save(next)
        }
    }

    private fun load(): List<Book> = try {
        val array = JSONArray(store.readFully().toString(Charsets.UTF_8))
        (0 until array.length()).map { Book.fromJson(array.getJSONObject(it)) }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Could not read library", e)
        emptyList()
    }

    private fun save(list: List<Book>) {
        val json = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        val out = try {
            store.startWrite()
        } catch (e: IOException) {
            Log.e(TAG, "Could not save library", e)
            return
        }
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            store.finishWrite(out)
        } catch (e: IOException) {
            store.failWrite(out)
            Log.e(TAG, "Could not save library", e)
        }
    }

    companion object {
        const val BOOKS_DIR = "books"
        private const val TAG = "LibraryRepository"
    }
}
