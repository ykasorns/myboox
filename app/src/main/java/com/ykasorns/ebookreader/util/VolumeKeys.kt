package com.ykasorns.ebookreader.util

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Routes the hardware volume buttons to the open reader while page turning is enabled. */
object VolumeKeys {
    @Volatile
    var listener: ((forward: Boolean) -> Unit)? = null

    fun handle(event: KeyEvent): Boolean {
        val l = listener ?: return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) l(forward)
        return true
    }
}

@Composable
fun VolumeKeysEffect(enabled: Boolean, onTurn: (forward: Boolean) -> Unit) {
    val current by rememberUpdatedState(onTurn)
    DisposableEffect(enabled) {
        val listener: (Boolean) -> Unit = { current(it) }
        if (enabled) VolumeKeys.listener = listener
        onDispose { if (VolumeKeys.listener === listener) VolumeKeys.listener = null }
    }
}
