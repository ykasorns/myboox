package com.ykasorns.ebookreader.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Hides the status and navigation bars while reading (swipe from an edge to peek at them),
 * and shows them together with the reader controls, like iPad Books.
 */
@Composable
fun ImmersiveBars(showBars: Boolean, lightBackground: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(window, showBars, lightBackground) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = lightBackground
        controller.isAppearanceLightNavigationBars = lightBackground
        if (showBars) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(window) {
        onDispose {
            val controller = WindowCompat.getInsetsController(window, view)
            val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !night
            controller.isAppearanceLightNavigationBars = !night
        }
    }
}

@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Applies an in-app brightness override (negative = follow the system). */
@Composable
fun ScreenBrightness(value: Float) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(window, value) {
        val attrs = window.attributes
        attrs.screenBrightness = if (value < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            value.coerceIn(0.01f, 1f)
        }
        window.attributes = attrs
        onDispose {
            val reset = window.attributes
            reset.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = reset
        }
    }
}
