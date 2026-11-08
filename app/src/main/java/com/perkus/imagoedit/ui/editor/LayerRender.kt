package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MaskOverlay
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.drawMasked
import com.perkus.imagoedit.data.halved
import com.perkus.imagoedit.data.levelFor
import com.perkus.imagoedit.data.orientH
import com.perkus.imagoedit.data.orientW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val LEVEL_MIN = 64 // длинная сторона самой мелкой копии, px

/** Display copies by [Bitmap] (an added photo's wrapper changes on confirm, its pixels don't): [0] — whole, then 2×, 4×… smaller. HARDWARE; out of memory → the original */
typealias Levels = Map<Bitmap, List<ImageBitmap>>

/** Read in the draw phase. Display only */
@Composable
fun rememberLevels(layers: List<ImageBitmap?>): State<Levels> {
    val levels = remember { mutableStateOf<Levels>(emptyMap()) }
    val alive = remember(layers) { layers.mapNotNull { it?.asAndroidBitmap() }.toSet() }
    val aliveNow = rememberUpdatedState(alive)
    LaunchedEffect(alive) { levels.value = levels.value.filterKeys { it in alive } }
    // One worker: a list change only changes what's built next
    LaunchedEffect(Unit) {
        while (true) {
            val bmp = snapshotFlow { aliveNow.value.firstOrNull { it !in levels.value } }.filterNotNull().first()
            val built = withContext(Dispatchers.Default) { bmp.buildLevels { isActive && bmp in aliveNow.value } }
            if (built != null && bmp in aliveNow.value) levels.value += bmp to built
        }
    }
    return levels
}

/** [needed] false between levels → null */
private fun Bitmap.buildLevels(needed: () -> Boolean): List<ImageBitmap>? {
    val out = ArrayList<ImageBitmap>()
    out += (copy(Bitmap.Config.HARDWARE, false) ?: this).asImageBitmap()
    var cur = this
    while (maxOf(cur.width, cur.height) > LEVEL_MIN && minOf(cur.width, cur.height) > 1) {
        if (!needed()) {
            if (cur !== this) cur.recycle()
            return null
        }
        val half = cur.halved()
        if (cur !== this) cur.recycle()
        cur = half
        out += (cur.copy(Bitmap.Config.HARDWARE, false) ?: break).asImageBitmap()
    }
    if (cur !== this) cur.recycle()
    return out
}

/** Smallest copy downscaled ≤ 2× at [k] px per original px */
private fun pickLevel(img: ImageBitmap, levels: List<ImageBitmap>?, k: Float): ImageBitmap {
    if (levels.isNullOrEmpty()) return img
    return levels[minOf(levelFor(k), levels.lastIndex)]
}

/** Bilinear below 2×, squares above. No mipmaps: a large bitmap's are built on the CPU and stall */
private fun filtered(scale: Float) = scale < 2f

private val paints = MaskPaints() // main thread only

/** As the canvas draws a layer: an empty one as Layer.paintable (it may take a stroke) */
class Drawn(val layer: Layer, val img: ImageBitmap?, val mask: LayerMask?, val patch: Tiles?)

/** [t] is for the layer AFTER [orient]; clipped to the canvas as baking crops. Through drawMasked: an offscreen only with a mask, a patch over pixels or an [overlay]
(live strokes, Transition's preview) for Layer.id [key]. No AA (the edge follows the pixel grid, as baked) except turned plain pixels: a stair-stepped slanted edge would
flicker while dragged. [detail] < 1 picks coarser copies: fewer texels to read */
fun DrawScope.drawPlaced(img: ImageBitmap?, fw: Int, fh: Int, t: ContentTransform, s: Float, canvasW: Int, canvasH: Int, levels: List<ImageBitmap>? = null, alpha: Float = 1f, orient: Int = 0,
    mask: LayerMask? = null, patch: Tiles? = null, overlay: MaskOverlay? = null, key: Long = 0L, detail: Float = 1f) {
    if (img == null && patch == null && overlay == null) return
    val b = t.box
    // Screen px per file px, from the visible part; sizes after orientation
    val ow = orientW(orient, fw, fh)
    val oh = orientH(orient, fw, fh)
    val kx = t.w * s / (b.width * ow)
    val ky = t.h * s / (b.height * oh)
    val k = maxOf(kx, ky) * detail
    clipRect(0f, 0f, canvasW * s, canvasH * s) {
        // ContentTransform.matrix's order
        withTransform({
            translate(t.cx * s, t.cy * s)
            rotate(t.angle, Offset.Zero)
            scale(if (t.flipH) -kx else kx, if (t.flipV) -ky else ky, Offset.Zero)
            translate((0.5f - b.centerX) * ow, (0.5f - b.centerY) * oh)
            rotate(90f * (orient and 3), Offset.Zero)
            if (orient >= 4) scale(-1f, 1f, Offset.Zero)
            translate(-fw / 2f, -fh / 2f)
        }) {
            paints.image.isAntiAlias = mask == null && patch == null && !t.axisAligned
            // A smaller copy is stretched over the file's px
            drawContext.canvas.nativeCanvas.drawMasked(img?.let { pickLevel(it, levels, k).asAndroidBitmap() }, fw, fh, mask, patch, k, (alpha * 255).roundToInt(), filtered(minOf(kx, ky)),
                paints, overlay, key)
        }
    }
}