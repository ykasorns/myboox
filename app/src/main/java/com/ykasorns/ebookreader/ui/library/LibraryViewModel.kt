package com.ykasorns.ebookreader.ui.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ykasorns.ebookreader.AppGraph
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.LibrarySort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LibraryMessage {
    data class Added(val count: Int) : LibraryMessage
    data class Failed(val name: String) : LibraryMessage
    data class EpisodesAdded(val count: Int) : LibraryMessage
    data object EpisodesFailed : LibraryMessage
}

class LibraryViewModel : ViewModel() {
    private val library = AppGraph.library
    private val importer = AppGraph.importer
    private val settings = AppGraph.settings

    private val _sort = MutableStateFlow(settings.librarySort)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    val books: StateFlow<List<Book>> = combine(library.books, _sort) { list, sort ->
        when (sort) {
            LibrarySort.RECENT -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
            LibrarySort.TITLE -> list.sortedBy { it.title.lowercase() }
            LibrarySort.AUTHOR -> list.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The book shown in the "Continue reading" card. */
    val continueReading: StateFlow<Book?> = library.books
        .map { list -> list.filter { it.lastOpenedAt > 0 && !it.isFinished }.maxByOrNull { it.lastOpenedAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing.asStateFlow()

    /** Markdown files waiting for the reader to choose: new book, or episodes of an existing one. */
    class MarkdownChoice(val uris: List<Uri>, val books: List<Book>)

    private val _markdownChoice = MutableStateFlow<MarkdownChoice?>(null)
    val markdownChoice: StateFlow<MarkdownChoice?> = _markdownChoice.asStateFlow()

    /** A book that was just created from several files and should be given a proper name. */
    private val _nameRequest = MutableStateFlow<Book?>(null)
    val nameRequest: StateFlow<Book?> = _nameRequest.asStateFlow()

    private val _messages = MutableSharedFlow<LibraryMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<LibraryMessage> = _messages.asSharedFlow()

    fun setSort(sort: LibrarySort) {
        _sort.value = sort
        settings.librarySort = sort
    }

    fun import(uris: List<Uri>, onImported: (Book) -> Unit = {}) {
        if (uris.isEmpty()) return
        if (importer.allMarkdown(uris)) {
            // New episodes usually belong to a book that is already on the shelf: ask first.
            val books = library.books.value.filter { importer.canAddEpisodes(it) }
                .sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
            if (books.isNotEmpty()) {
                _markdownChoice.value = MarkdownChoice(uris, books)
                return
            }
        }
        importAsNew(uris, onImported)
    }

    fun chooseNewBook(choice: MarkdownChoice) {
        _markdownChoice.value = null
        importAsNew(choice.uris)
    }

    fun chooseExistingBook(choice: MarkdownChoice, book: Book) {
        _markdownChoice.value = null
        addEpisodes(book, choice.uris)
    }

    fun dismissMarkdownChoice() {
        _markdownChoice.value = null
    }

    private fun importAsNew(uris: List<Uri>, onImported: (Book) -> Unit = {}) {
        // Several Markdown files together are the episodes of one book.
        if (uris.size > 1 && importer.allMarkdown(uris)) {
            importSeries(uris, onImported)
            return
        }
        viewModelScope.launch {
            _importing.update { it + uris.size }
            var added = 0
            var last: Book? = null
            for (uri in uris) {
                try {
                    last = importer.import(uri)
                    added++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("LibraryViewModel", "Import failed for $uri", e)
                    _messages.tryEmit(LibraryMessage.Failed(importer.displayName(uri)))
                } finally {
                    _importing.update { it - 1 }
                }
            }
            if (added > 0) _messages.tryEmit(LibraryMessage.Added(added))
            last?.let(onImported)
        }
    }

    private fun importSeries(uris: List<Uri>, onImported: (Book) -> Unit) {
        viewModelScope.launch {
            _importing.update { it + 1 }
            try {
                val book = importer.importMarkdownSeries(uris)
                _messages.tryEmit(LibraryMessage.Added(1))
                _nameRequest.value = book
                onImported(book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LibraryViewModel", "Series import failed", e)
                _messages.tryEmit(LibraryMessage.Failed(importer.displayName(uris.first())))
            } finally {
                _importing.update { it - 1 }
            }
        }
    }

    fun addEpisodes(book: Book, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.update { it + 1 }
            try {
                val count = importer.addMarkdownEpisodes(book, uris)
                _messages.tryEmit(LibraryMessage.EpisodesAdded(count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LibraryViewModel", "Adding episodes failed", e)
                _messages.tryEmit(LibraryMessage.EpisodesFailed)
            } finally {
                _importing.update { it - 1 }
            }
        }
    }

    fun rename(book: Book, title: String, author: String) {
        _nameRequest.value = null
        val cleanTitle = title.trim().ifBlank { book.title }
        viewModelScope.launch(Dispatchers.IO) { library.rename(book.id, cleanTitle, author.trim()) }
    }

    fun dismissNameRequest() {
        _nameRequest.value = null
    }

    fun remove(book: Book) {
        CoverCache.evict(book.id)
        viewModelScope.launch(Dispatchers.IO) { library.remove(book.id) }
    }
}
