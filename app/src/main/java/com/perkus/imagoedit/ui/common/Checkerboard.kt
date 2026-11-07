package com.perkus.imagoedit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.ui.theme.CheckerGray

/** A 2×2 tile shader: one drawRect per frame (weak GPUs) */
@Composable
fun rememberCheckerBrush(cell: Dp = 8.dp): Brush {
    val cellPx = with(LocalDensity.current) { cell.roundToPx() }
    return remember(cellPx) {
        val c = cellPx.toFloat()
        val tile = ImageBitmap(cellPx * 2, cellPx * 2)
        val canvas = Canvas(tile)
        val paint = Paint().apply { color = Color.White }
        canvas.drawRect(0f, 0f, 2 * c, 2 * c, paint)
        paint.color = CheckerGray
        canvas.drawRect(c, 0f, 2 * c, c, paint)
        canvas.drawRect(0f, c, c, 2 * c, paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

private val checkerPaint = Paint().apply { isAntiAlias = false } // main thread only

/** Under a [w]×[h] canvas. Not anti-aliased, as the layers: an AA edge would show past a layer's edge as a thin checker line */
fun DrawScope.drawChecker(brush: Brush, w: Float, h: Float) {
    brush.applyTo(size, checkerPaint, 1f)
    drawContext.canvas.drawRect(0f, 0f, w, h, checkerPaint)
}