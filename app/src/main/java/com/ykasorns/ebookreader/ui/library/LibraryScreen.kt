@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ykasorns.ebookreader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.ykasorns.ebookreader.data.BookFormat
import com.ykasorns.ebookreader.data.LibrarySort
import kotlin.math.max
import kotlin.math.roundToInt

private val pickerMimeTypes = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/html",
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
    var editing by remember { mutableStateOf<Book?>(null) }
    var addingTo by remember { mutableStateOf<Book?>(null) }
    val nameRequest by viewModel.nameRequest.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is LibraryMessage.Added -> context.getString(R.string.import_done, message.count)
                is LibraryMessage.Failed -> context.getString(R.string.import_failed, message.name)
                is LibraryMessage.EpisodesAdded -> context.getString(R.string.episodes_added, message.count)
                LibraryMessage.EpisodesFailed -> context.getString(R.string.episodes_failed)
            }
            snackbar.showSnackbar(text)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    val pickBooks = { picker.launch(pickerMimeTypes) }
    val episodePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addingTo?.let { book -> viewModel.addEpisodes(book, uris) }
        addingTo = null
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Light mode uses a warm paper colour behind the crayon bookshelf.
    val paper = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.background else PaperLight
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = paper,
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = paper, scrolledContainerColor = paper),
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
                    actions = BookActions(
                        onRemove = { pendingRemoval = it },
                        onEdit = { editing = it },
                        onAddEpisodes = { book ->
                            addingTo = book
                            episodePicker.launch(markdownMimeTypes)
                        },
                    ),
                )
            }
            if (importing > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    (editing ?: nameRequest)?.let { book ->
        EditBookDialog(
            book = book,
            isNew = editing == null,
            onSave = { title, author ->
                viewModel.rename(book, title, author)
                editing = null
            },
            onDismiss = {
                editing = null
                viewModel.dismissNameRequest()
            },
        )
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

private val PaperLight = Color(0xFFFBF7EF)

/**
 * The library as a bookcase drawn in crayon: covers stand on hand-drawn shelves, the uprights
 * run down both sides, and empty space on the last shelf gets an abstract doodle.
 */
@Composable
private fun BookGrid(
    books: List<Book>,
    continueBook: Book?,
    bottomPadding: Dp,
    onOpen: (Book) -> Unit,
    actions: BookActions,
) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) CrayonPalette.dark else CrayonPalette.light
    val wood = if (dark) CrayonPalette.woodDark else CrayonPalette.woodLight
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Phones get 3 books per shelf; tablets and unfolded foldables get more and bigger covers.
        val wide = maxWidth >= 600.dp
        val side = if (wide) 40.dp else 26.dp
        val gap = if (wide) 28.dp else 18.dp
        val minCell = if (wide) 140.dp else 92.dp
        val columns = max(3, ((maxWidth - side * 2 + gap) / (minCell + gap)).toInt())
        val cell = (maxWidth - side * 2 - gap * (columns - 1)) / columns
        val rows = books.chunked(columns)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding + 32.dp),
        ) {
            if (continueBook != null) {
                item(key = "continue") {
                    Box(Modifier.padding(horizontal = side - 6.dp, vertical = 8.dp)) {
                        ContinueReadingCard(continueBook, wide = wide, onClick = { onOpen(continueBook) })
                    }
                }
            }
            item(key = "header") {
                Column(Modifier.padding(start = side, top = 12.dp, bottom = 4.dp)) {
                    Text(
                        stringResource(R.string.all_books),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Canvas(Modifier.width(150.dp).height(10.dp)) {
                        crayon(
                            listOf(Offset(0f, 5.dp.toPx()), Offset(size.width, 3.dp.toPx())),
                            palette[0],
                            4f,
                            seed = 7,
                        )
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> "shelf-" + row.first().id }) { rowIndex, row ->
                ShelfRow(
                    books = row,
                    columns = columns,
                    cell = cell,
                    gap = gap,
                    side = side,
                    rowIndex = rowIndex,
                    isFirst = rowIndex == 0,
                    isLast = rowIndex == rows.lastIndex,
                    color = palette[rowIndex % palette.size],
                    doodleColors = palette[(rowIndex + 1) % palette.size] to palette[(rowIndex + 2) % palette.size],
                    wood = wood,
                    onOpen = onOpen,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun ShelfRow(
    books: List<Book>,
    columns: Int,
    cell: Dp,
    gap: Dp,
    side: Dp,
    rowIndex: Int,
    isFirst: Boolean,
    isLast: Boolean,
    color: Color,
    doodleColors: Pair<Color, Color>,
    wood: Color,
    onOpen: (Book) -> Unit,
    actions: BookActions,
) {
    val coverHeight = cell * 1.5f
    val topSpace = if (isFirst) 10.dp else 22.dp
    val plankBottom = topSpace + coverHeight + 14.dp
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // The bookcase uprights; each shelf draws its own stretch so they join up.
                val left = side.toPx() - 14.dp.toPx()
                val right = size.width - side.toPx() + 14.dp.toPx()
                val top = if (isFirst) 0f else -2f
                val bottom = if (isLast) plankBottom.toPx() + 4.dp.toPx() else size.height + 2f
                crayon(listOf(Offset(left, top), Offset(left + 1.dp.toPx(), bottom)), wood, 3.2f, seed = 991 + rowIndex * 7)
                crayon(listOf(Offset(right, top), Offset(right - 1.dp.toPx(), bottom)), wood, 3.2f, seed = 992 + rowIndex * 7)
            },
    ) {
        Column {
            Spacer(Modifier.height(topSpace))
            Box(Modifier.fillMaxWidth().height(coverHeight + 26.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    val plank = coverHeight.toPx()
                    val l = side.toPx() - 12.dp.toPx()
                    val r = size.width - side.toPx() + 12.dp.toPx()
                    val tilt = if (rowIndex % 2 == 0) -1.5f else 1.5f
                    val seed = rowIndex * 100
                    crayon(listOf(Offset(l, plank + tilt), Offset(r, plank - tilt)), color, 4.2f, seed + 1)
                    crayon(
                        listOf(Offset(l + 4.dp.toPx(), plank + 12.dp.toPx() + tilt), Offset(r - 3.dp.toPx(), plank + 12.dp.toPx() - tilt)),
                        color, 3f, seed + 2,
                    )
                    crayonHatch(Offset(l + 6.dp.toPx(), plank + 2.dp.toPx()), Offset(r - 6.dp.toPx(), plank + 11.dp.toPx()), color, seed + 3)
                    // Little brackets under both ends of the shelf.
                    val s = side.toPx()
                    crayon(
                        listOf(Offset(s + 6.dp.toPx(), plank + 12.dp.toPx()), Offset(s + 14.dp.toPx(), plank + 24.dp.toPx()), Offset(s + 22.dp.toPx(), plank + 12.dp.toPx())),
                        color, 2.4f, seed + 4, passes = 2,
                    )
                    crayon(
                        listOf(
                            Offset(size.width - s - 22.dp.toPx(), plank + 12.dp.toPx()),
                            Offset(size.width - s - 14.dp.toPx(), plank + 24.dp.toPx()),
                            Offset(size.width - s - 6.dp.toPx(), plank + 12.dp.toPx()),
                        ),
                        color, 2.4f, seed + 5, passes = 2,
                    )
                    // An abstract doodle in the first empty spot of a shelf that is not full.
                    if (books.size < columns) {
                        val c = cell.toPx()
                        val x = s + books.size * (c + gap.toPx()) + c / 2f
                        crayonSpiral(Offset(x, plank - plank * 0.38f), c * 0.3f, doodleColors.first, seed + 6)
                        crayon(
                            listOf(
                                Offset(x - c * 0.16f, plank - 2.dp.toPx()),
                                Offset(x - c * 0.05f, plank - plank * 0.2f),
                                Offset(x + c * 0.12f, plank - plank * 0.06f),
                                Offset(x + c * 0.2f, plank - 2.dp.toPx()),
                            ),
                            doodleColors.second, 2.5f, seed + 7, passes = 2,
                        )
                    }
                }
                Row(
                    Modifier.padding(horizontal = side),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    books.forEach { book ->
                        ShelfCover(book, cell, coverHeight, onOpen = { onOpen(book) }, actions = actions)
                    }
                }
            }
            Row(
                Modifier.padding(start = side, end = side, top = 2.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                books.forEach { book ->
                    Column(Modifier.width(cell)) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodySmall,
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
                }
            }
        }
    }
}

/** What the long-press menu on a book can do. */
private class BookActions(
    val onRemove: (Book) -> Unit,
    val onEdit: (Book) -> Unit,
    val onAddEpisodes: (Book) -> Unit,
)

private val markdownMimeTypes = arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")

@Composable
private fun ShelfCover(book: Book, width: Dp, height: Dp, onOpen: () -> Unit, actions: BookActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        BookCover(
            book,
            Modifier
                .width(width)
                .height(height)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (book.format == BookFormat.MARKDOWN) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_episodes)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.onAddEpisodes(book)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_book)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    actions.onEdit(book)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_book)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    actions.onRemove(book)
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

@Composable
private fun EditBookDialog(book: Book, isNew: Boolean, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.name_your_book else R.string.edit_book)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.book_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.book_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, author) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
