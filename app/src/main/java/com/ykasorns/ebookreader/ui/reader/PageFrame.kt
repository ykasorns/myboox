package com.ykasorns.ebookreader.ui.reader

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where the small chapter title sits while reading (absolute, left-to-right coordinates). */
data class HeaderPlacement(val left: Dp, val top: Dp, val width: Dp, val height: Dp, val alignment: Alignment)

/**
 * The reading area of a page. Values are in dp and measured from the physical screen edges.
 * [top]/[bottom]/[left]/[right] are where text (or the PDF page) may start; side margins chosen
 * by the reader are added on top of [left]/[right] by the caller.
 */
data class PageFrame(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val header: HeaderPlacement?,
    /** Distance of the page number from the bottom edge, or null to hide it. */
    val footerBottom: Dp?,
)

/**
 * Works out the reading area so text comes as close to the screen edges as possible, like on
 * iPad. On phones with a camera cutout the chapter title moves into the strip beside the camera
 * (where the clock normally is), and the text starts right below that strip.
 */
@Composable
fun rememberPageFrame(fullScreen: Boolean, screenWidth: Dp, wide: Boolean): PageFrame {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val view = LocalView.current
    val safe = WindowInsets.displayCutout.union(WindowInsets.waterfall)
    val cutTop: Float
    val cutBottom: Float
    val cutLeft: Float
    val cutRight: Float
    with(density) {
        cutTop = safe.getTop(this).toDp().value
        cutBottom = safe.getBottom(this).toDp().value
        cutLeft = safe.getLeft(this, direction).toDp().value
        cutRight = safe.getRight(this, direction).toDp().value
    }

    if (fullScreen) {
        return PageFrame(
            top = maxOf(cutTop + 6f, 14f),
            bottom = cutBottom + 14f,
            left = cutLeft,
            right = cutRight,
            header = null,
            footerBottom = null,
        )
    }

    val width = screenWidth.value
    val sideGap = if (wide) 40f else 24f
    val header: HeaderPlacement
    val top: Float
    if (cutTop >= 18f) {
        // Find the camera hole(s) along the top edge and put the title beside them.
        var holeLeft = width / 2f - 30f
        var holeRight = width / 2f + 30f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val rects = view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
                .filter { it.top <= 0 && it.width() < view.width }
            if (rects.isNotEmpty()) {
                holeLeft = rects.minOf { it.left } / density.density
                holeRight = rects.maxOf { it.right } / density.density
            }
        }
        val leftSpace = holeLeft - 12f - (cutLeft + sideGap)
        val rightSpace = (width - cutRight - sideGap) - (holeRight + 12f)
        header = if (leftSpace >= rightSpace) {
            HeaderPlacement(
                left = (cutLeft + sideGap).dp,
                top = 0.dp,
                width = leftSpace.coerceAtLeast(40f).dp,
                height = cutTop.dp,
                alignment = Alignment.CenterStart,
            )
        } else {
            HeaderPlacement(
                left = (holeRight + 12f).dp,
                top = 0.dp,
                width = rightSpace.coerceAtLeast(40f).dp,
                height = cutTop.dp,
                alignment = Alignment.CenterEnd,
            )
        }
        top = cutTop + if (wide) 14f else 8f
    } else {
        // No cutout (tablets, older phones): a thin title line above the text.
        val headerTop = if (wide) 14f else 10f
        header = HeaderPlacement(
            left = (cutLeft + 56f).dp,
            top = headerTop.dp,
            width = (width - cutLeft - cutRight - 112f).coerceAtLeast(40f).dp,
            height = 18.dp,
            alignment = Alignment.Center,
        )
        top = headerTop + 18f + if (wide) 14f else 8f
    }
    return PageFrame(
        top = top,
        bottom = cutBottom + if (wide) 36f else 30f,
        left = cutLeft,
        right = cutRight,
        header = header,
        footerBottom = (cutBottom + 8f).dp,
    )
}
