package com.ykasorns.ebookreader.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ykasorns.ebookreader.ui.library.LibraryScreen
import com.ykasorns.ebookreader.ui.library.LibraryViewModel
import com.ykasorns.ebookreader.ui.reader.ReaderScreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AppRoot(incoming: StateFlow<List<Uri>>, onIncomingHandled: () -> Unit) {
    val libraryViewModel: LibraryViewModel = viewModel()
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }

    val pending by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        val uris = pending
        onIncomingHandled()
        // A single file opened from another app goes straight to the reader.
        libraryViewModel.import(uris) { book -> if (uris.size == 1) openBookId = book.id }
    }

    val bookId = openBookId
    if (bookId == null) {
        LibraryScreen(viewModel = libraryViewModel, onOpen = { openBookId = it.id })
    } else {
        key(bookId) {
            ReaderScreen(bookId = bookId, onClose = { openBookId = null })
        }
    }
}
