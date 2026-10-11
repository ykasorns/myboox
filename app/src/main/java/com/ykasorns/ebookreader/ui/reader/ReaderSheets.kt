@file:OptIn(ExperimentalMaterial3Api::class)

package com.ykasorns.ebookreader.ui.reader

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.LineSpacing
import com.ykasorns.ebookreader.data.PageMargin
import com.ykasorns.ebookreader.data.PageTurn
import com.ykasorns.ebookreader.data.PdfLayout
import com.ykasorns.ebookreader.data.ReaderFont
import com.ykasorns.ebookreader.data.ReaderSettings
import com.ykasorns.ebookreader.data.ReaderTheme
import com.ykasorns.ebookreader.data.TocEntry

/** The "Aa" panel: brightness, text size, page theme, font, spacing and reading options. */
@Composable
fun AppearanceSheet(
    settings: ReaderSettings,
    showTextOptions: Boolean,
    wide: Boolean,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = settings.theme
    val text = Color(theme.text)
    val muted = Color(theme.secondary)
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val systemBrightness = remember {
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.02f, 1f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(theme.background),
        contentColor = text,
        scrimColor = Color.Black.copy(alpha = 0.25f),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_brightness),
                    contentDescription = stringResource(R.string.brightness),
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
                Slider(
                    value = if (settings.brightness < 0f) systemBrightness else settings.brightness,
                    onValueChange = { value -> onChange { it.copy(brightness = value) } },
                    valueRange = 0.02f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = text,
                        activeTrackColor = text,
                        inactiveTrackColor = muted.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TextButton(onClick = { onChange { it.copy(brightness = -1f) } }) {
                    Text(
                        stringResource(R.string.brightness_auto),
                        color = if (settings.brightness < 0f) accent else muted,
                    )
                }
            }

            if (showTextOptions) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val smaller = stringResource(R.string.smaller_text)
                    val larger = stringResource(R.string.larger_text)
                    OutlinedButton(
                        onClick = {
                            onChange {
                                it.copy(fontScale = (it.fontScale - ReaderSettings.FONT_SCALE_STEP).coerceAtLeast(ReaderSettings.MIN_FONT_SCALE))
                            }
                        },
                        border = BorderStroke(1.dp, muted.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = smaller },
                    ) { Text("A", color = text, fontSize = 15.sp) }
                    Text(
                        "${settings.fontScale}%",
                        color = muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = {
                            onChange {
                                it.copy(fontScale = (it.fontScale + ReaderSettings.FONT_SCALE_STEP).coerceAtMost(ReaderSettings.MAX_FONT_SCALE))
                            }
                        },
                        border = BorderStroke(1.dp, muted.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = larger },
                    ) { Text("A", color = text, fontSize = 24.sp) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ReaderTheme.entries.forEach { option ->
                    ThemeSwatch(option, selected = option == theme, accent = accent, labelColor = text) {
                        onChange { it.copy(theme = option) }
                    }
                }
            }

            if (showTextOptions) {
                Segments(
                    label = stringResource(R.string.font),
                    options = ReaderFont.entries,
                    selected = settings.font,
                    optionLabel = { stringResource(it.label) },
                    optionFont = {
                        when (it) {
                            ReaderFont.ORIGINAL -> FontFamily.Default
                            ReaderFont.SANS -> FontFamily.SansSerif
                            ReaderFont.SERIF -> FontFamily.Serif
                        }
                    },
                    theme = theme,
                ) { option -> onChange { it.copy(font = option) } }
                Segments(
                    label = stringResource(R.string.line_spacing),
                    options = LineSpacing.entries,
                    selected = settings.lineSpacing,
                    optionLabel = { stringResource(it.label) },
                    theme = theme,
                ) { option -> onChange { it.copy(lineSpacing = option) } }
                Segments(
                    label = stringResource(R.string.margins),
                    options = PageMargin.entries,
                    selected = settings.margin,
                    optionLabel = { stringResource(it.label) },
                    theme = theme,
                ) { option -> onChange { it.copy(margin = option) } }
                SwitchRow(stringResource(R.string.justify_text), settings.justify, theme, accent) { value ->
                    onChange { it.copy(justify = value) }
                }
            }
            if (showTextOptions || !settings.pdfLayoutIsScroll(wide)) {
                SwitchRow(stringResource(R.string.full_screen_text), settings.fullScreenText, theme, accent) { value ->
                    onChange { it.copy(fullScreenText = value) }
                }
            }
            if (!showTextOptions) {
                Segments(
                    label = stringResource(R.string.pdf_layout),
                    options = PdfLayout.entries,
                    selected = settings.pdfLayout,
                    optionLabel = { stringResource(it.label) },
                    theme = theme,
                ) { option -> onChange { it.copy(pdfLayout = option) } }
                SwitchRow(stringResource(R.string.pdf_crop), settings.pdfCrop, theme, accent) { value ->
                    onChange { it.copy(pdfCrop = value) }
                }
            }
            Segments(
                label = stringResource(R.string.page_turn),
                options = PageTurn.entries,
                selected = settings.pageTurn,
                optionLabel = { stringResource(it.label) },
                theme = theme,
            ) { option -> onChange { it.copy(pageTurn = option) } }
            SwitchRow(stringResource(R.string.two_pages), settings.twoPageLandscape, theme, accent) { value ->
                onChange { it.copy(twoPageLandscape = value) }
            }
            SwitchRow(stringResource(R.string.volume_keys), settings.volumeKeys, theme, accent) { value ->
                onChange { it.copy(volumeKeys = value) }
            }
            SwitchRow(stringResource(R.string.keep_screen_on), settings.keepScreenOn, theme, accent) { value ->
                onChange { it.copy(keepScreenOn = value) }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, accent: Color, labelColor: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(theme.background))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) accent else Color(theme.secondary).copy(alpha = 0.6f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = Color(theme.text), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(theme.label), color = labelColor, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun <T> Segments(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    theme: ReaderTheme,
    optionFont: (T) -> FontFamily = { FontFamily.Default },
    onSelect: (T) -> Unit,
) {
    val text = Color(theme.text)
    val muted = Color(theme.secondary)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = muted, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = text.copy(alpha = 0.12f),
                        activeContentColor = text,
                        activeBorderColor = muted.copy(alpha = 0.6f),
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = text,
                        inactiveBorderColor = muted.copy(alpha = 0.6f),
                    ),
                    icon = {},
                    label = { Text(optionLabel(option), fontFamily = optionFont(option), maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, theme: ReaderTheme, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(theme.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                uncheckedTrackColor = Color(theme.secondary).copy(alpha = 0.25f),
                uncheckedBorderColor = Color(theme.secondary).copy(alpha = 0.6f),
                uncheckedThumbColor = Color(theme.secondary),
            ),
        )
    }
}

/** Table of contents. The current chapter is highlighted and scrolled into view. */
@Composable
fun TocSheet(
    entries: List<TocEntry>,
    currentIndex: Int,
    theme: ReaderTheme,
    onSelect: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val text = Color(theme.text)
    val accent = MaterialTheme.colorScheme.primary
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(theme.background),
        contentColor = text,
        scrimColor = Color.Black.copy(alpha = 0.25f),
    ) {
        Text(
            stringResource(R.string.contents),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(entries) { index, entry ->
                val current = index == currentIndex
                Text(
                    entry.title,
                    color = if (current) accent else text,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(start = 24.dp + 18.dp * entry.depth.coerceAtMost(4), end = 24.dp, top = 13.dp, bottom = 13.dp),
                )
            }
        }
    }
}

/** Whether PDFs are shown as one continuous scroll on this screen. */
fun ReaderSettings.pdfLayoutIsScroll(wide: Boolean): Boolean = when (pdfLayout) {
    PdfLayout.AUTO -> !wide
    PdfLayout.SCROLL -> true
    PdfLayout.PAGES -> false
}
