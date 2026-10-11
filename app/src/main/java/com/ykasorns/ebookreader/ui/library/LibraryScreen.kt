@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ykasorns.ebookreader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.Book
import com.ykasorns.ebookreader.data.LibrarySort
import kotlin.math.roundToInt

private val pickerMimeTypes = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "text/plain",
    // Many file managers do not know the EPUB type; the importer checks the content anyway.
    "application/octet-stream",
)

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpen: (Book) -> Unit) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val continueBook by viewModel.continueReading.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is LibraryMessage.Added -> context.getString(R.string.import_done, message.count)
                is LibraryMessage.Failed -> context.getString(R.string.import_failed, message.name)
            }
            snackbar.showSnackbar(text)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    val pickBooks = { picker.launch(pickerMimeTypes) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = pickBooks) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_books))
                    }
                    SortMenu(sort = sort, onSort = viewModel::setSort)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                ),
        ) {
            if (books.isEmpty() && importing == 0) {
                EmptyLibrary(onAdd = pickBooks)
            } else {
                BookGrid(
                    books = books,
                    continueBook = continueBook,
                    bottomPadding = padding.calculateBottomPadding(),
                    onOpen = onOpen,
                    onRemove = { pendingRemoval = it },
                )
            }
            if (importing > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    pendingRemoval?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_title, book.title)) },
            text = { Text(stringResource(R.string.remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(book)
                    pendingRemoval = null
                }) { Text(stringResource(R.string.remove_book)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            listOf(
                LibrarySort.RECENT to R.string.sort_recent,
                LibrarySort.TITLE to R.string.sort_title,
                LibrarySort.AUTHOR to R.string.sort_author,
            ).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = {
                        if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSort(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BookGrid(
    books: List<Book>,
    continueBook: Book?,
    bottomPadding: Dp,
    onOpen: (Book) -> Unit,
    onRemove: (Book) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Phones get 3 columns, tablets and unfolded foldables get more and bigger covers.
        val wide = maxWidth >= 600.dp
        val side = if (wide) 32.dp else 20.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (wide) 150.dp else 100.dp),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = bottomPadding + 32.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (continueBook != null) {
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(continueBook, wide = wide, onClick = { onOpen(continueBook) })
                }
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.all_books),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                BookCell(book, onOpen = { onOpen(book) }, onRemove = { onRemove(book) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun BookCell(book: Book, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(8.dp))
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLabel(book),
                style = MaterialTheme.typography.labelSmall,
                color = if (book.isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_book)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun statusLabel(book: Book): String = when {
    book.isNew -> stringResource(R.string.book_new)
    book.isFinished -> stringResource(R.string.book_finished)
    else -> "${(book.progress * 100).roundToInt()}%"
}

@Composable
private fun ContinueReadingCard(book: Book, wide: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(if (wide) 96.dp else 72.dp).aspectRatio(2f / 3f))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.continue_reading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier.weight(1f),
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(book.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_book),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_books))
        }
    }
}
