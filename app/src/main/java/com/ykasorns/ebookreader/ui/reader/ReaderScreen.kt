package com.ykasorns.ebookreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ykasorns.ebookreader.AppGraph
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.BookFormat
import com.ykasorns.ebookreader.data.ReaderTheme
import com.ykasorns.ebookreader.util.ImmersiveBars
import com.ykasorns.ebookreader.util.KeepScreenOn
import com.ykasorns.ebookreader.util.ScreenBrightness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What every reader needs from the screen around it. */
class ReaderChromeState(
    val controlsVisible: Boolean,
    val setControlsVisible: (Boolean) -> Unit,
    val onBack: () -> Unit,
    val onAppearance: () -> Unit,
) {
    /** Shared tap behaviour: tap anywhere hides the controls, the middle shows them, the sides turn pages. */
    fun handleTap(zone: TapZone, previous: () -> Unit, next: () -> Unit) {
        when {
            controlsVisible -> setControlsVisible(false)
            zone == TapZone.CENTER -> setControlsVisible(true)
            zone == TapZone.LEFT -> previous()
            else -> next()
        }
    }
}

enum class TapZone { LEFT, CENTER, RIGHT }

@Composable
fun ReaderScreen(bookId: String, onClose: () -> Unit) {
    val library = AppGraph.library
    val book = remember(bookId) { library.get(bookId) }
    if (book == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(bookId) { withContext(Dispatchers.IO) { library.markOpened(bookId) } }

    ImmersiveBars(showBars = controlsVisible || showAppearance, lightBackground = !settings.theme.isDark)
    KeepScreenOn(settings.keepScreenOn)
    ScreenBrightness(settings.brightness)
    BackHandler(onBack = onClose)

    val chrome = ReaderChromeState(
        controlsVisible = controlsVisible,
        setControlsVisible = { controlsVisible = it },
        onBack = onClose,
        onAppearance = { showAppearance = true },
    )

    // The page colour fills the whole window, including behind the status bar and camera cutout.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(settings.theme.background)),
    ) {
        when (book.format) {
            BookFormat.PDF -> PdfReader(book, settings, chrome)
            BookFormat.EPUB, BookFormat.TXT -> WebBookReader(book, settings, chrome)
        }
    }

    if (showAppearance) {
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        AppearanceSheet(
            settings = settings,
            showTextOptions = book.format != BookFormat.PDF,
            wide = wide,
            onChange = AppGraph.settings::update,
            onDismiss = { showAppearance = false },
        )
    }
}

@Composable
fun ReaderMessage(message: String, theme: ReaderTheme, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(theme.background))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            color = Color(theme.text),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = onBack,
            colors = ButtonDefaults.textButtonColors(contentColor = Color(theme.link)),
        ) { Text(stringResource(R.string.back)) }
    }
}
