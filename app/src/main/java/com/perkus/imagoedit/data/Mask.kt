package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.createBitmap
import kotlin.math.floor

const val MASK = "mask"
private const val MASK_BAND = 256 // px: программная сборка идёт полосами — offscreen размером с полосу

/** ALPHA_8 tiles of the ERASED amount (255 — erased), drawn DST_OUT: the same on GPU (alpha) and on Skia's software canvas (coverage). Missing tile — nothing erased */
typealias LayerMask = Tiles

fun blankMaskTile() = createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)

/** The kept part, 0..1 */
fun LayerMask.alphaAt(x: Int, y: Int) = levels[0][y / TILE * cols(0) + x / TILE]?.let { 1f - (it.getPixel(x % TILE, y % TILE) ushr 24) / 255f } ?: 1f

/** Alphas of [px] (non-premultiplied) × the kept part, rounded up: zero only where fully erased. [tmp] — TILE ints */
fun LayerMask.erase(px: IntArray, x: Int, y: Int, alongX: Boolean, len: Int, tmp: IntArray) {
    val cols = cols(0)
    var i = 0
    while (i < len) {
        val fx = if (alongX) x + i else x
        val fy = if (alongX) y else y + i
        val run = minOf(len - i, TILE - (if (alongX) fx else fy) % TILE)
        levels[0][fy / TILE * cols + fx / TILE]?.let { t ->
            if (alongX) t.getPixels(tmp, 0, run, fx % TILE, fy % TILE, run, 1) else t.getPixels(tmp, 0, 1, fx % TILE, fy % TILE, 1, run)
            for (j in 0 until run) {
                val e = tmp[j] ushr 24
                val c = px[i + j]
                if (e != 0) px[i + j] = (((c ushr 24) * (255 - e) + 254) / 255 shl 24) or (c and 0xFFFFFF)
            }
        }
        i += run
    }
}

/** Reused; one per drawing thread */
class MaskPaints {
    val layer = Paint()
    val image = Paint().apply { isAntiAlias = false } // an AA rim px past the mask's tiles would stay unerased
    val mask = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val patch = tilePaint()
    val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    val rect = RectF()
    val clip = Rect()
}

/** The brush's live strokes of layer [key], drawn inside its offscreen; [bmp]…[k] — the layer as drawMasked draws it (a clone redraws it shifted) */
interface MaskOverlay {
    fun draw(c: Canvas, key: Long, filter: Boolean, bmp: Bitmap?, w: Int, h: Int, mask: LayerMask?, patch: Tiles?, k: Float)
}

/** [bmp] (or a smaller copy; null — none) over [w]×[h] file px × [mask] + [patch] by ADD: mask and patch are disjoint, so filtered coarse levels sum to a whole
(SRC_OVER leaves a see-through seam at patch tile edges). Levels for [k] px per file px. The single compositing path: preview, merge, crop, export, thumbnails, clone.
A software canvas goes in bands over its clip only */
fun Canvas.drawMasked(bmp: Bitmap?, w: Int, h: Int, mask: LayerMask?, patch: Tiles?, k: Float, alpha: Int, filter: Boolean, p: MaskPaints, overlay: MaskOverlay? = null, key: Long = 0L) {
    p.image.isFilterBitmap = filter
    p.mask.isFilterBitmap = filter
    p.patch.isFilterBitmap = filter
    val pn = patch?.let { minOf(levelFor(k), it.levels.lastIndex) } ?: 0
    if (overlay == null && mask == null && (bmp == null || patch == null)) {
        if (bmp != null) {
            p.image.alpha = alpha
            drawBitmap(bmp, null, p.rect.apply { set(0f, 0f, w.toFloat(), h.toFloat()) }, p.image)
        } else if (patch != null) {
            p.patch.alpha = alpha
            p.patch.xfermode = null
            drawPatch(patch, pn, 0f, w.toFloat(), h.toFloat(), p)
        }
        return
    }
    p.image.alpha = 255
    p.patch.alpha = 255
    p.patch.xfermode = p.add
    p.layer.alpha = alpha
    val n = mask?.let { minOf(levelFor(k), it.levels.lastIndex) } ?: 0
    val band = if (isHardwareAccelerated) h.toFloat() else maxOf(1f, MASK_BAND / k)
    var top = 0f
    var end = h.toFloat()
    if (!isHardwareAccelerated) {
        if (!getClipBounds(p.clip)) return
        top = maxOf(0, p.clip.top).toFloat()
        end = minOf(h, p.clip.bottom).toFloat()
    }
    while (top < end) {
        val bottom = minOf(end, top + band)
        saveLayer(0f, top, w.toFloat(), bottom, p.layer)
        if (bmp != null) drawBitmap(bmp, null, p.rect.apply { set(0f, 0f, w.toFloat(), h.toFloat()) }, p.image)
        if (mask != null) drawTiles(mask.levels[n], mask.cols(n), (1 shl n).toFloat(), p.mask, p.rect)
        if (patch != null) drawPatch(patch, pn, top, w.toFloat(), bottom, p)
        overlay?.draw(this, key, filter, bmp, w, h, mask, patch, k)
        restore()
        top = bottom
    }
}

/** [into] (software) gets the layer from px ([x], [y]) at [k] px per file px, transparent past it; k = 1 — an exact copy.
The px its edge cuts (the last of ceil(w·k), ceil(h·k)) get the edge, as from a clamped texture: by the pixel-center rule they'd stay empty — drawn with the layer shifted
to end on their far side */
fun Canvas.composite(into: Bitmap, x: Int, y: Int, bmp: Bitmap?, w: Int, h: Int, mask: LayerMask?, patch: Tiles?, p: MaskPaints, k: Float = 1f) {
    into.eraseColor(Color.TRANSPARENT)
    setBitmap(into)
    translate(-x.toFloat(), -y.toFloat())
    val ex = w * k
    val ey = h * k
    val fx = floor(ex)
    val fy = floor(ey)
    for (cutX in 0..(if (fx < ex) 1 else 0)) for (cutY in 0..(if (fy < ey) 1 else 0)) {
        save()
        clipRect(cutX * fx, cutY * fy, fx + cutX, fy + cutY)
        translate(cutX * (fx + 1 - ex), cutY * (fy + 1 - ey))
        scale(k, k)
        drawMasked(bmp, w, h, mask, patch, k, 255, k != 1f, p)
        restore()
    }
}

/** Clipped to the layer: edge tiles' padding stays unseen */
private fun Canvas.drawPatch(patch: Tiles, n: Int, top: Float, w: Float, bottom: Float, p: MaskPaints) {
    save()
    clipRect(0f, top, w, bottom)
    drawTiles(patch.levels[n], patch.cols(n), (1 shl n).toFloat(), p.patch, p.rect)
    restore()
}

/** [l] without what [sel] selects: in the patch where it has a tile, else in the mask. All of it → an empty layer. Returns the layer and its new rasters */
fun cleared(l: Layer, mask: LayerMask?, patch: Tiles?, sel: Selection, maskId: String, patchId: String): Pair<Layer, List<Tiles>> {
    if (l.region().apply { op(sel.core, Region.Op.DIFFERENCE) }.isEmpty) return Layer() to emptyList()
    val w = l.fileW
    val h = l.fileH
    val toFile = l.canvasToFile()
    val rects = l.region().apply { op(sel.reach, Region.Op.INTERSECT) }.rects(toFile)
    val cols = tilesAlong(w, 0)
    val rows = tilesAlong(h, 0)
    val touched = BooleanArray(cols * rows)
    touched.mark(cols, rows, rects)
    val patched = patch?.levels?.get(0)
    val toPatch = BooleanArray(touched.size) { touched[it] && patched?.get(it) != null }
    val toMask = BooleanArray(touched.size) { touched[it] && patched?.get(it) == null && l.file != null }
    val erase = Paint().apply { isAntiAlias = false }
    val cut = Paint().apply { isAntiAlias = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val f = sel.feather
    fun Canvas.fill(paint: Paint) {
        if (f != null) { concat(toFile); f.draw(this, paint) }
        else for (k in rects.indices step 4) drawRect(rects[k].toFloat(), rects[k + 1].toFloat(), rects[k + 2].toFloat(), rects[k + 3].toFloat(), paint)
    }
    val m = if (toMask.none { it }) mask else mask.edited(maskId, w, h, toMask) { _, old, x, y -> tileDrawn(old, ::blankMaskTile, x, y, w, h) { it.fill(erase) } }
    val p = if (toPatch.none { it }) patch else patch.edited(patchId, w, h, toPatch) { _, old, x, y -> tileDrawn(old, { patchTile(null) }, x, y, w, h) { it.fill(cut) } }
    return l.copy(mask = m?.id, patch = p?.id) to listOfNotNull(m?.takeIf { it !== mask }, p?.takeIf { it !== patch })
}

/** Carried like the pixels (ContentTransform.render); its edge goes past the content, so the AA rim is erased too */
fun LayerMask.transformed(id: String, t: ContentTransform, rect: Rect, orient: Int): LayerMask? {
    val e = createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val out = try {
        Canvas(e).drawTiles(levels[0], cols(0), 1f, tilePaint(), RectF())
        t.render(e, rect, orient, clamp = true)
    } finally { e.recycle() }
    return try { out.tiled(id) } finally { out.recycle() }
}
