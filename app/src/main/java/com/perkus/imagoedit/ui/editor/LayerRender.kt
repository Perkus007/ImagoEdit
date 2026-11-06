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

private const val LEVEL_MIN = 64 // длинная сторона самой мелкой копии не больше, px

/** Display copies of layers. Keyed by the [Bitmap], not the ImageBitmap wrapper: an added photo's wrapper changes on confirm while pixels stay the same → copies aren't rebuilt.
Value: [0] — whole layer, then 2×, 4×, … smaller. Copies are HARDWARE; out of memory → [0] is the software original */
typealias Levels = Map<Bitmap, List<ImageBitmap>>

/** Read in the draw phase → redraw without recomposition. Display only */
@Composable
fun rememberLevels(layers: List<ImageBitmap?>): State<Levels> {
    val levels = remember { mutableStateOf<Levels>(emptyMap()) }
    val alive = remember(layers) { layers.mapNotNull { it?.asAndroidBitmap() }.toSet() }
    val aliveNow = rememberUpdatedState(alive)
    LaunchedEffect(alive) { levels.value = levels.value.filterKeys { it in alive } }
    // One worker per composition: a list change doesn't interrupt building, only changes what's built next. No parallel builds
    LaunchedEffect(Unit) {
        while (true) {
            val bmp = snapshotFlow { aliveNow.value.firstOrNull { it !in levels.value } }.filterNotNull().first()
            val built = withContext(Dispatchers.Default) { bmp.buildLevels { isActive && bmp in aliveNow.value } }
            if (built != null && bmp in aliveNow.value) levels.value += bmp to built
        }
    }
    return levels
}

/** [needed] is checked between levels: false → null, the rest isn't built */
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

/** Smallest copy downscaled by at most 2× for [k] px per original px (drawPlaced's: its detail applied) */
private fun pickLevel(img: ImageBitmap, levels: List<ImageBitmap>?, k: Float): ImageBitmap {
    if (levels.isNullOrEmpty()) return img
    return levels[minOf(levelFor(k), levels.lastIndex)]
}

/** Bilinear below 2×, pixels as squares above. No mipmaps on purpose: pickLevel already chose a copy downscaled by at most 2×, and mipmaps of a large bitmap are built on the CPU and cause stalls */
private fun filtered(scale: Float) = scale < 2f

private val paints = MaskPaints() // main thread only: the canvas and the layer thumbnails

/** A layer as the canvas draws it: [layer] (an empty one as Layer.paintable: it may be taking a stroke), its pixels [img] (null — none), [mask], [patch] */
class Drawn(val layer: Layer, val img: ImageBitmap?, val mask: LayerMask?, val patch: Tiles?)

/** [img] — the layer's pixels (null — none), its file [fw]×[fh] px; [t] is for the layer AFTER orientation [orient]. Clipping to the canvas mirrors baking's crop.
Through the compositing of export (drawMasked): an offscreen only with a [mask], a [patch] over pixels or live [strokes] of the layer [key] (Layer.id); nothing to draw → nothing.
No anti-aliasing: the layer's edge follows the pixel grid, as baked. Only turned pixels alone (the Move tool's preview) keep it: a stair-stepped slanted edge would flicker while dragged.
[detail] scales the resolution its copies (pixels, mask, patch) are picked for: below 1 — coarser ones, stretched on screen but with fewer texels to read */
fun DrawScope.drawPlaced(img: ImageBitmap?, fw: Int, fh: Int, t: ContentTransform, s: Float, canvasW: Int, canvasH: Int, levels: List<ImageBitmap>? = null, alpha: Float = 1f, orient: Int = 0,
    mask: LayerMask? = null, patch: Tiles? = null, strokes: BrushTool? = null, key: Long = 0L, detail: Float = 1f) {
    val live = strokes?.has(key) == true
    if (img == null && patch == null && !live) return
    val b = t.box
    // Screen px per file px, from the visible part (box); sizes after orientation
    val ow = orientW(orient, fw, fh)
    val oh = orientH(orient, fw, fh)
    val kx = t.w * s / (b.width * ow)
    val ky = t.h * s / (b.height * oh)
    val k = maxOf(kx, ky) * detail // what the copies are picked for
    clipRect(0f, 0f, canvasW * s, canvasH * s) {
        // Same order as ContentTransform.matrix: file → orientation → visible part center → scale with flip → rotation → translation
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
            // A smaller copy is stretched over the file's px: the mask, patch and strokes are in them
            drawContext.canvas.nativeCanvas.drawMasked(img?.let { pickLevel(it, levels, k).asAndroidBitmap() }, fw, fh, mask, patch, k, (alpha * 255).roundToInt(), filtered(minOf(kx, ky)),
                paints, strokes.takeIf { live }, key)
        }
    }
}