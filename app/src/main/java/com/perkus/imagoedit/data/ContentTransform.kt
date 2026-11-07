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

/** Visible part of the source as fractions of its sides; [empty] — no visible px, the box is the whole source */
data class ContentBox(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f, val empty: Boolean = false) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

    /** As fractions of the layer AFTER orientation */
fun ContentBox.oriented(orient: Int): ContentBox {
    val r = RectF(left, top, right, bottom)
    orientMatrix(orient, 1, 1).mapRect(r)
    return ContentBox(r.left, r.top, r.right, r.bottom)
}

/** Layer placement, canvas px: frame center ([cx], [cy]), size before rotation, clockwise angle (°), flips. The frame covers only the visible part [box] */
data class ContentTransform(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float = 0f, val flipH: Boolean = false, val flipV: Boolean = false, val box: ContentBox = ContentBox()) {
    companion object {
        fun initial(box: ContentBox, x: Int, y: Int, srcW: Int, srcH: Int) = ContentTransform(x + box.centerX * srcW, y + box.centerY * srcH, box.width * srcW, box.height * srcH, box = box)
    }

    val axisAligned: Boolean get() = (angle / 90f).let { q -> abs(q - q.roundToInt()) < 1e-4f }

    /** File px → canvas; [box], [w]×[h] are for the layer AFTER orientation. drawPlaced repeats this chain */
    fun matrix(srcW: Int, srcH: Int, orient: Int): Matrix {
        val ow = orientW(orient, srcW, srcH)
        val oh = orientH(orient, srcW, srcH)
        return orientMatrix(orient, srcW, srcH).apply {
            // The visible part's center to the origin: it's the frame center
            postTranslate(-box.centerX * ow, -box.centerY * oh)
            postScale(w / (box.width * ow) * (if (flipH) -1f else 1f), h / (box.height * oh) * (if (flipV) -1f else 1f))
            postRotate(angle)
            postTranslate(cx, cy)
        }
    }

    /** The frame [r] gets when [src] gets this one; both rects of the same source px */
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

    /** Frame bounds + a margin for AA and bilinear halo, clipped to the canvas; null — entirely off it */
    fun layerRect(canvasW: Int, canvasH: Int, srcW: Int, srcH: Int): Rect? {
        val zoom = maxOf(w / (box.width * srcW), h / (box.height * srcH))
        val pad = if (isPixelCopy(srcW, srcH)) 0 else ceil(maxOf(zoom / 2, 1f)).toInt()
        val b = bounds()
        val r = Rect(floor(b.left).toInt() - pad, floor(b.top).toInt() - pad, ceil(b.right).toInt() + pad, ceil(b.bottom).toInt() + pad)
        return if (r.intersect(0, 0, canvasW, canvasH)) r else null
    }

    /** A pixel copy suffices, no resampling */
    private fun isPixelCopy(srcW: Int, srcH: Int) = abs(w - box.width * srcW) < 0.01f && abs(h - box.height * srcH) < 0.01f && axisAligned

    /** [src] with the same file if this only rearranges px and stays on the canvas; else null — baking needed. Rounded to the grid as baking is */
    fun placed(src: Layer, canvasW: Int, canvasH: Int): Layer? {
        if (!isPixelCopy(src.w, src.h)) return null
        return src.laid(matrix(src.fileW, src.fileH, src.orient).apply { roundToGrid() }).takeIf { it.x >= 0 && it.y >= 0 && it.x + it.w <= canvasW && it.y + it.h <= canvasH }
    }

    /** Canvas px → where its pixels carry them, rounded as in [placed] and [render] */
    fun carrier(src: Layer) = src.canvasToFile().apply { postConcat(matrix(src.fileW, src.fileH, src.orient).apply { if (isPixelCopy(src.w, src.h)) roundToGrid() }) }

    /** [area] carried with its pixels, whole px. Rect by rect, merged by unionAll: setPath's buffer stays one rect high */
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

    /** Into a new bitmap sized as [dst]; [src] untouched. [clamp] — the source's edge goes on past it (a mask) */
    fun render(src: Bitmap, dst: Rect, orient: Int, clamp: Boolean = false): Bitmap {
        val copy = isPixelCopy(orientW(orient, src.width, src.height), orientH(orient, src.width, src.height))
        // Downscaling > 2× goes in ×½ steps: one bilinear pass causes moiré
        val turned = (orient and 1) == 1
        val fx = if (turned) h / box.height else w / box.width
        val fy = if (turned) w / box.width else h / box.height
        val s = src.halveWhile({ fx < it.width / 2f }, { fy < it.height / 2f })
        val out = src.blankLike(dst.width(), dst.height())
        // An integer offset keeps the grid rounding below
        val m = matrix(s.width, s.height, orient).apply { postTranslate(-dst.left.toFloat(), -dst.top.toFloat()) }
        if (copy) m.roundToGrid()
        val paint = if (copy) Paint() else Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (clamp) Canvas(out).drawPaint(paint.apply { shader = BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) } })
        else Canvas(out).drawBitmap(s, m, paint)
        if (s !== src) s.recycle()
        return out
    }
}

/** After 90° turns the matrix holds almost 0/±1: snapped to exact values, the offset rounded */
private fun Matrix.roundToGrid() {
    val v = FloatArray(9)
    getValues(v)
    for (k in 0..5) v[k] = v[k].roundToInt().toFloat()
    setValues(v)
}

/** Same file, mask and patch laid on the canvas by [m] (0/±1, integer offsets): only place and orientation new */
fun Layer.laid(m: Matrix): Layer {
    val r = RectF(0f, 0f, fileW.toFloat(), fileH.toFloat()).apply { m.mapRect(this) }
    return copy(x = r.left.roundToInt(), y = r.top.roundToInt(), w = r.width().roundToInt(), h = r.height().roundToInt(), orient = m.toOrient())
}

/** Orientation (0..7) from a roundToGrid matrix: determinant < 0 — flipped; the file X axis' image gives the rotation (sign inverted after a flip) */
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