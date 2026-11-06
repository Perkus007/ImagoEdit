package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Visible part of the source as fractions of its sides (0..1). Transparent margins around it are outside the content frame.
[empty] — no visible pixels at all: the box is then the whole source */
data class ContentBox(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f, val empty: Boolean = false) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Same visible part as fractions of the layer sides AFTER orientation: [this] comes from file pixels, while the tool and frame work with the layer as it lies on the canvas */
fun ContentBox.oriented(orient: Int): ContentBox {
    val r = RectF(left, top, right, bottom)
    orientMatrix(orient, 1, 1).mapRect(r)
    return ContentBox(r.left, r.top, r.right, r.bottom)
}

/** Layer content placement on the canvas, all in canvas px: frame center ([cx], [cy]), frame size before rotation ([w]×[h]), clockwise angle (°) and pixel flips inside the frame.
The frame covers only the source's visible part [box]: transparent margins scale and rotate with it but stay outside the frame */
data class ContentTransform(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float = 0f, val flipH: Boolean = false, val flipV: Boolean = false, val box: ContentBox = ContentBox()) {
    companion object {
        fun initial(box: ContentBox, x: Int, y: Int, srcW: Int, srcH: Int) = ContentTransform(x + box.centerX * srcW, y + box.centerY * srcH, box.width * srcW, box.height * srcH, box = box)
    }

    val axisAligned: Boolean get() = (angle / 90f).let { q -> abs(q - q.roundToInt()) < 1e-4f }

    /** "File pixels → canvas" matrix. Frame [box] and size [w]×[h] are for the layer AFTER orientation. The Compose preview (drawPlaced) repeats the same chain of steps */
    fun matrix(srcW: Int, srcH: Int, orient: Int): Matrix {
        val ow = orientW(orient, srcW, srcH)
        val oh = orientH(orient, srcW, srcH)
        return orientMatrix(orient, srcW, srcH).apply {
            // Center of the VISIBLE part (not the whole layer) to the origin: it coincides with the frame center
            postTranslate(-box.centerX * ow, -box.centerY * oh)
            postScale(w / (box.width * ow) * (if (flipH) -1f else 1f), h / (box.height * oh) * (if (flipV) -1f else 1f))
            postRotate(angle)
            postTranslate(cx, cy)
        }
    }

    /** The frame [r] gets when [src] gets this one (its box the whole source): both rects of the same source px */
    fun framing(src: Rect, r: Rect): ContentTransform {
        val kx = w / src.width()
        val ky = h / src.height()
        val dx = (r.exactCenterX() - src.exactCenterX()) * kx * (if (flipH) -1 else 1)
        val dy = (r.exactCenterY() - src.exactCenterY()) * ky * (if (flipV) -1 else 1)
        val a = Math.toRadians(angle.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return copy(cx = cx + dx * c - dy * s, cy = cy + dx * s + dy * c, w = r.width() * kx, h = r.height() * ky)
    }

    fun bounds(): RectF {
        val a = Math.toRadians(angle.toDouble())
        val c = abs(cos(a)).toFloat()
        val s = abs(sin(a)).toFloat()
        val hx = (w * c + h * s) / 2
        val hy = (w * s + h * c) / 2
        return RectF(cx - hx, cy - hy, cx + hx, cy + hy)
    }

    /** Frame bounds plus a margin for anti-aliasing and bilinear halo (a pixel copy has none), clipped to the canvas; null — content is entirely off-canvas.
    [srcW]×[srcH] — the source layer's size */
    fun layerRect(canvasW: Int, canvasH: Int, srcW: Int, srcH: Int): Rect? {
        val zoom = maxOf(w / (box.width * srcW), h / (box.height * srcH))
        val pad = if (isPixelCopy(srcW, srcH)) 0 else ceil(maxOf(zoom / 2, 1f)).toInt()
        val b = bounds()
        val r = Rect(floor(b.left).toInt() - pad, floor(b.top).toInt() - pad, ceil(b.right).toInt() + pad, ceil(b.bottom).toInt() + pad)
        return if (r.intersect(0, 0, canvasW, canvasH)) r else null
    }

    /** A pixel copy suffices instead of resampling. Flips don't matter: pixels just swap places */
    private fun isPixelCopy(srcW: Int, srcH: Int) = abs(w - box.width * srcW) < 0.01f && abs(h - box.height * srcH) < 0.01f && axisAligned

    /** Layer [src] after this transform with the same file, if it only rearranges pixels and stays fully on the canvas; otherwise null — baking needed.
    The matrix is rounded to the grid as in baking → result matches it pixel for pixel */
    fun placed(src: Layer, canvasW: Int, canvasH: Int): Layer? {
        if (!isPixelCopy(src.w, src.h)) return null
        return src.laid(matrix(src.fileW, src.fileH, src.orient).apply { roundToGrid() }).takeIf { it.x >= 0 && it.y >= 0 && it.x + it.w <= canvasW && it.y + it.h <= canvasH }
    }

    /** Canvas px over [src] as it lies → where its pixels carry them: the matrix rounded as in [placed] and [render] */
    fun carrier(src: Layer) = src.canvasToFile().apply { postConcat(matrix(src.fileW, src.fileH, src.orient).apply { if (isPixelCopy(src.w, src.h)) roundToGrid() }) }

    /** [area] (canvas px) over [src] as it lies, carried along with its pixels (carrier): whole px.
    Rect by rect, merged by unionAll: setPath's working buffer stays one rect high */
    fun carry(area: Region, src: Layer): Region {
        val m = carrier(src)
        val iter = RegionIterator(area)
        val r = Rect()
        val path = Path()
        val b = RectF()
        return generateSequence { if (iter.next(r)) r else null }.map {
            b.set(it)
            path.rewind()
            path.addRect(b, Path.Direction.CW)
            path.transform(m)
            m.mapRect(b)
            Region().apply { setPath(path, Region(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())) }
        }.unionAll()
    }

    /** Into a new bitmap sized as [dst] — the layer rect on the canvas (see [layerRect]). [src] is neither modified nor recycled.
    [clamp] — the source's edge goes on past it (a mask: an erased edge erases the pixels' anti-aliased rim too, a kept one keeps it) */
    fun render(src: Bitmap, dst: Rect, orient: Int, clamp: Boolean = false): Bitmap {
        val copy = isPixelCopy(orientW(orient, src.width, src.height), orientH(orient, src.width, src.height))
        // Downscaling the visible part by more than 2× along an axis goes in ×½ steps along it: a single bilinear pass over a large image causes moiré
        val turned = (orient and 1) == 1
        val fx = if (turned) h / box.height else w / box.width // the source's width on the canvas, px
        val fy = if (turned) w / box.width else h / box.height
        val s = src.halveWhile({ fx < it.width / 2f }, { fy < it.height / 2f })
        val out = src.blankLike(dst.width(), dst.height())
        // Origin → layer's top-left: an integer offset doesn't spoil the grid rounding below
        val m = matrix(s.width, s.height, orient).apply { postTranslate(-dst.left.toFloat(), -dst.top.toFloat()) }
        if (copy) m.roundToGrid()
        val paint = if (copy) Paint() else Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (clamp) Canvas(out).drawPaint(paint.apply { shader = BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) } })
        else Canvas(out).drawBitmap(s, m, paint)
        if (s !== src) s.recycle()
        return out
    }
}

/** After a 90° rotation the matrix holds "almost" 0/±1 (float error), but exact values are needed:
then a source pixel maps exactly onto a canvas pixel, and a fractional offset (manual X/Y input) is rounded to an integer */
private fun Matrix.roundToGrid() {
    val v = FloatArray(9)
    getValues(v)
    for (k in 0..5) v[k] = v[k].roundToInt().toFloat()
    setValues(v)
}

/** [this] with the same file, mask and patch, its file px laid on the canvas by [m] (file px → canvas px, coefficients 0/±1, integer offsets): only its place and orientation new */
fun Layer.laid(m: Matrix): Layer {
    val r = RectF(0f, 0f, fileW.toFloat(), fileH.toFloat()).apply { m.mapRect(this) }
    return copy(x = r.left.roundToInt(), y = r.top.roundToInt(), w = r.width().roundToInt(), h = r.height().roundToInt(), orient = m.toOrient())
}

/** Orientation (0..7) from the linear part of the "file → canvas" matrix after roundToGrid (only 0/±1 there). Determinant < 0 — flipped.
The image of the file's X axis (v[0], v[3]) is the rotation's first column; with the flip applied first, its sign is inverted */
internal fun Matrix.toOrient(): Int {
    val v = FloatArray(9)
    getValues(v)
    val flip = v[0] * v[4] - v[1] * v[3] < 0f
    val k = if (flip) -1f else 1f
    val ax = k * v[0]
    val ay = k * v[3]
    val rot = if (ax > 0f) 0 else if (ay > 0f) 1 else if (ax < 0f) 2 else 3
    return if (flip) rot + 4 else rot
}