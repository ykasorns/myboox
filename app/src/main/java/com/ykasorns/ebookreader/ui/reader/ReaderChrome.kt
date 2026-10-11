@file:OptIn(ExperimentalLayoutApi::class)

package com.ykasorns.ebookreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ykasorns.ebookreader.R
import com.ykasorns.ebookreader.data.ReaderTheme

/**
 * The layer drawn over the page. While reading it only shows a quiet chapter title at the top
 * and the page number at the bottom; a tap in the middle of the page brings in the top bar
 * (back, contents, appearance) and the bottom progress slider.
 */
@Composable
fun ReaderChrome(
    visible: Boolean,
    theme: ReaderTheme,
    title: String,
    header: String,
    headerPlacement: HeaderPlacement?,
    footer: String,
    footerBottom: Dp?,
    progress: Float,
    sliderLabel: (Float) -> String,
    onSeek: (Float) -> Unit,
    onBack: () -> Unit,
    onContents: (() -> Unit)?,
    onAppearance: () -> Unit,
) {
    val background = Color(theme.background)
    val text = Color(theme.text)
    val muted = Color(theme.secondary)
    val barInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

    Box(Modifier.fillMaxSize()) {
        if (headerPlacement != null) {
            AnimatedVisibility(
                visible = !visible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .absoluteOffset(x = headerPlacement.left, y = headerPlacement.top),
            ) {
                Box(
                    Modifier.size(headerPlacement.width, headerPlacement.height),
                    contentAlignment = headerPlacement.alignment,
                ) {
                    Text(
                        header,
                        color = muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (footerBottom != null) {
            AnimatedVisibility(
                visible = !visible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text(
                    footer,
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .padding(bottom = footerBottom),
                )
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = background.copy(alpha = 0.97f), contentColor = text, shadowElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(barInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (onContents != null) {
                        IconButton(onClick = onContents) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.contents))
                        }
                    }
                    TextButton(
                        onClick = onAppearance,
                        colors = ButtonDefaults.textButtonColors(contentColor = text),
                    ) {
                        Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = background.copy(alpha = 0.97f), contentColor = text, shadowElevation = 3.dp) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(barInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Slider(
                        value = dragging ?: progress,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let(onSeek)
                            dragging = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = text,
                            activeTrackColor = text,
                            inactiveTrackColor = muted.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    )
                    Text(
                        sliderLabel(dragging ?: progress),
                        color = muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}
