package com.perkus.imagoedit.ui.common

import android.os.Build
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.ceil

private const val CORNER_K = 0.2929f // Прямой угол интерфейса ложится на дугу скругления при отступе r·(1 − 1/√2) ≈ 0.293·r. Если угол всё равно «цепляет» — увеличьте коэффициент
val LocalFrame = compositionLocalOf { WindowInsets(0, 0, 0, 0) } // Screen frame (px). Dialogs are separate windows, so they read it from here instead of computing it

/** Frame on the sides only: max(camera cutout, screen corner rounding), symmetric — the cutout doesn't shift the UI center.
Both values are hardware properties: read synchronously from Display, once per window. WindowInsets don't fit here:
the system delivers them asynchronously and resends them on dialog close and focus change — the frame would "breathe" */
@Composable
private fun rememberFrame(): WindowInsets {
    val view = LocalView.current
    return remember(view) {
        val display = view.display
        // Cutout depth = max over four sides: rotation-independent (top in portrait, side in landscape)
        val cutout = display?.cutout?.run { maxOf(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom) } ?: 0
        // RoundedCorner.POSITION_TOP_LEFT..POSITION_BOTTOM_LEFT = 0..3
        val corner = if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (0..3).maxOf { display.getRoundedCorner(it)?.radius ?: 0 } else 0
        val h = maxOf(cutout, ceil(CORNER_K * corner).toInt())
        WindowInsets(h, 0, h, 0)
    }
}

@Composable
fun ScreenFrame(content: @Composable () -> Unit) {
    val frame = rememberFrame()
    val base = LocalViewConfiguration.current
    // Min touch target 16×16dp instead of the system 48×48dp: small elements (layer visibility button) respond only within their bounds. 250 ms long press — layer drag in the layers panel
    val viewConfig = remember(base) { object : ViewConfiguration by base {
        override val minimumTouchTargetSize = DpSize(16.dp, 16.dp)
        override val longPressTimeoutMillis = 250L
    } }
    CompositionLocalProvider(LocalFrame provides frame, LocalViewConfiguration provides viewConfig) {
        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(frame)) { NumPadHost(content) }
    }
}

fun Window.hideSystemBars() = WindowInsetsControllerCompat(this, decorView).run {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.systemBars())
}
