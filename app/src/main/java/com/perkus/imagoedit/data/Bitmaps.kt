package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MATCH: Byte = 1 // сетка волшебной палочки: пиксель похож цветом на исходный
private const val FILLED: Byte = 2 // сетка волшебной палочки: похож и связан с исходным (смежный режим)

private val contentBoxes = Collections.synchronizedMap(WeakHashMap<Bitmap, ContentBox>()) // Keyed by Bitmap: layer pixels never change in place (an edit writes a new bitmap)

/** A line of [len] file px of a layer from [x], [y] along X or Y into [px] (non-premultiplied): its pixels [bmp] (null — none) erased by [mask], [patch] over them where it has tiles.
[tmp] — TILE ints */
fun readLine(bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, px: IntArray, x: Int, y: Int, alongX: Boolean, len: Int, tmp: IntArray) {
    when {
        bmp == null -> px.fill(0, 0, len)
        alongX -> bmp.getPixels(px, 0, len, x, y, len, 1)
        else -> bmp.getPixels(px, 0, 1, x, y, 1, len)
    }
    mask?.erase(px, x, y, alongX, len, tmp)
    patch?.over(px, x, y, alongX, len)
}

/** Off the main thread. The visible part of a [w]×[h] layer: its pixels [bmp] (null — none) without those [mask] erases fully, [patch] over them.
Pixels alone without an alpha channel → whole; no visible pixels → whole marked [ContentBox.empty] */
fun contentBox(bmp: Bitmap?, w: Int, h: Int, mask: LayerMask? = null, patch: Tiles? = null): ContentBox {
    val plain = mask == null && patch == null
    if (bmp != null && plain) {
        if (!bmp.hasAlpha()) return ContentBox()
        contentBoxes[bmp]?.let { return it }
    }
    // Without pixels nothing lies outside the patch's tiles
    val b = if (bmp != null) Rect(0, 0, w, h) else patch?.bounds()?.takeUnless { it.isEmpty } ?: return ContentBox(empty = true)
    val row = IntArray(w)
    val tmp = IntArray(TILE)
    fun read(y: Int, x0: Int, x1: Int) = readLine(bmp, mask, patch, row, x0, y, true, x1 - x0, tmp)
    fun first(n: Int): Int { for (i in 0 until n) if ((row[i] ushr 24) != 0) return i; return -1 }
    fun last(n: Int): Int { for (i in n - 1 downTo 0) if ((row[i] ushr 24) != 0) return i; return -1 }

    var top = -1
    var left = b.right
    var right = -1
    for (y in b.top until b.bottom) {
        read(y, b.left, b.right)
        val f = first(b.width())
        if (f >= 0) { top = y; left = b.left + f; right = b.left + last(b.width()); break }
    }
    if (top < 0) return ContentBox(empty = true).also { if (bmp != null && plain) contentBoxes[bmp] = it }

    var bottom = top
    for (y in b.bottom - 1 downTo top + 1) {
        read(y, b.left, b.right)
        val f = first(b.width())
        if (f >= 0) { bottom = y; left = minOf(left, b.left + f); right = maxOf(right, b.left + last(b.width())); break }
    }
    for (y in top + 1 until bottom) {
        if (left == b.left && right == b.right - 1) break
        if (left > b.left) { read(y, b.left, left); val f = first(left - b.left); if (f >= 0) left = b.left + f }
        if (right < b.right - 1) { read(y, right + 1, b.right); val l = last(b.right - right - 1); if (l >= 0) right += 1 + l }
    }
    return ContentBox(left / w.toFloat(), top / h.toFloat(), (right + 1f) / w, (bottom + 1f) / h).also { if (bmp != null && plain) contentBoxes[bmp] = it }
}

/** Union of [this] regions (they're consumed), merged pairwise like a binary counter: adding them one by one to one region would be quadratic */
fun Sequence<Region>.unionAll(): Region {
    val levels = ArrayList<Region?>() // [k] — parts merged by 2^k, or none
    for (part in this) {
        var k = 0
        while (k < levels.size) {
            val prev = levels[k] ?: break
            part.op(prev, Region.Op.UNION)
            levels[k++] = null
        }
        if (k == levels.size) levels += part else levels[k] = part
    }
    return Region().apply { for (r in levels) if (r != null) op(r, Region.Op.UNION) }
}

/** A [w]×[h] layer read by lines lying along canvas rows under [m] (90° turns, flips and whole px shifts only): stored rows if [alongX], otherwise stored columns,
each [len] px from [start]. Only the [lines] that may show: without pixels [bmp] nothing lies outside the [patch]'s tiles */
private class LayerLines(private val bmp: Bitmap?, w: Int, h: Int, m: Matrix, private val mask: LayerMask?, private val patch: Tiles?) {
    private val v = FloatArray(9).also { m.getValues(it) }
    val alongX = v[Matrix.MSCALE_X] != 0f
    val step = (if (alongX) v[Matrix.MSCALE_X] else v[Matrix.MSKEW_X]).toInt() // ±1: the way a line's px go along its canvas row
    private val b = if (bmp != null) Rect(0, 0, w, h) else patch?.bounds() ?: Rect()
    val start = if (alongX) b.left else b.top
    val len = if (alongX) b.width() else b.height()
    val lines = if (alongX) b.top until b.bottom else b.left until b.right
    val px = IntArray(len)
    private val tmp = IntArray(TILE)

    /** Line [i] into [px] */
    fun read(i: Int) = readLine(bmp, mask, patch, px, if (alongX) start else i, if (alongX) i else start, alongX, len, tmp)
}

/** Runs of the px that pass [on] in [lines] of [len] px, each line read by [read] first — line i along X: y = i from x = [start], else x = i from y = [start] — placed by [m] (null — as they are).
A region per line clipped to [clip] — setPath's working buffer is height × all edges, so it stays one line high — merged by unionAll */
private inline fun runsRegion(lines: IntRange, start: Int, len: Int, alongX: Boolean, m: Matrix?, clip: Region, crossinline read: (Int) -> Unit, crossinline on: (Int) -> Boolean): Region {
    val span = RectF()
    val path = Path()
    return lines.asSequence().mapNotNull { i ->
        read(i)
        path.rewind()
        var a = 0
        while (a < len) {
            if (!on(a)) { a++; continue }
            var e = a + 1
            while (e < len && on(e)) e++
            if (alongX) span.set((start + a).toFloat(), i.toFloat(), (start + e).toFloat(), i + 1f) else span.set(i.toFloat(), (start + a).toFloat(), i + 1f, (start + e).toFloat())
            m?.mapRect(span)
            path.addRect(span, Path.Direction.CW)
            a = e
        }
        if (path.isEmpty) null else Region().apply { setPath(path, clip) }
    }.unionAll()
}

/** A [w]×[h] layer's visible pixels — [bmp] (null — none) with alpha > 0 that [mask] doesn't erase fully, [patch] over them — as a region placed by [m]
(90° turns, flips and whole px shifts only): each separate area gets its own contour. Off the main thread */
fun visibleRegion(bmp: Bitmap?, w: Int, h: Int, m: Matrix, mask: LayerMask? = null, patch: Tiles? = null): Region {
    val l = LayerLines(bmp, w, h, m, mask, patch)
    val span = RectF(0f, 0f, w.toFloat(), h.toFloat()).also { m.mapRect(it) }
    val clip = Region(span.left.roundToInt(), span.top.roundToInt(), span.right.roundToInt(), span.bottom.roundToInt())
    return runsRegion(l.lines, l.start, l.len, l.alongX, m, clip, l::read) { (l.px[it] ushr 24) != 0 }
}

/** The px of ALPHA_8 [this] with alpha at least [min] as a region, its top-left at canvas px ([x], [y]). Off the main thread */
fun Bitmap.alphaRegion(x: Int, y: Int, min: Int): Region {
    val row = IntArray(width)
    return runsRegion(0 until height, 0, width, true, Matrix().apply { setTranslate(x.toFloat(), y.toFloat()) }, Region(x, y, x + width, y + height),
        { getPixels(row, 0, width, 0, it, width, 1) }) { (row[it] ushr 24) >= min }
}

/** The px of ALPHA_8 [this] within [r] px of one it fills (r > 0), or all of whose px within -r it fills (r < 0), past it nothing filled: its fill's outline moved out or in by r,
corners rounded. Exact on px centers: each px's distance along its column to the nearest spreading px, then per row the spans those columns reach. As a region, its top-left at canvas
px ([x], [y]). Off the main thread: a Short per px */
fun Bitmap.grownRegion(x: Int, y: Int, r: Float): Region {
    val w = width
    val h = height
    val out = r > 0f // the filled px spread; shrinking — the unfilled ones, those past the bitmap too (its edge shrinks at any scale of a copy), and the rest stays
    val rr = abs(r)
    val far = ceil(rr).toInt() + 1 // past any reach
    val edge = if (out) far else 1 // to the row past the bitmap
    val d = ShortArray(w * h) // to the nearest spreading px of its column, capped at far
    val row = IntArray(w)
    for (j in 0 until h) {
        getPixels(row, 0, w, 0, j, w, 1)
        val o = j * w
        for (i in 0 until w) d[o + i] = if ((row[i] ushr 24 != 0) == out) 0 else minOf(if (j == 0) edge else d[o - w + i] + 1, far).toShort()
    }
    for (i in w * (h - 1) until w * h) if (edge < d[i]) d[i] = edge.toShort()
    for (o in w * (h - 2) downTo 0 step w) for (i in o until o + w) if (d[i + w] + 1 < d[i]) d[i] = (d[i + w] + 1).toShort()
    val half = IntArray(far) { k -> if (k <= rr) floor(sqrt(rr.toDouble() * rr - k * k)).toInt() else -1 } // along the row a spreading px k rows off reaches either way
    val near = BooleanArray(w)
    return runsRegion(0 until h, 0, w, true, Matrix().apply { setTranslate(x.toFloat(), y.toFloat()) }, Region(x, y, x + w, y + h), { j ->
        val o = j * w
        var reach = if (out) Int.MIN_VALUE else half[0] - 1 // shrinking: from the column past the left edge
        for (i in 0 until w) { val k = d[o + i].toInt(); if (k < far && half[k] >= 0) reach = maxOf(reach, i + half[k]); near[i] = reach >= i }
        reach = if (out) Int.MAX_VALUE else w - half[0] // and past the right one
        for (i in w - 1 downTo 0) { val k = d[o + i].toInt(); if (k < far && half[k] >= 0) reach = minOf(reach, i - half[k]); if (reach <= i) near[i] = true }
    }) { near[it] == out }
}

/** Channel [shift] of non-premultiplied [c], premultiplied */
private fun premul(c: Int, shift: Int) = ((c shr shift and 0xFF) * (c ushr 24) + 127) / 255

/** Off the main thread. The magic wand on a [w]×[h] layer — its pixels [bmp] (null — none), [mask] and [patch] applied, placed by [m] (90° turns, flips and whole px shifts only) —
over the [cw]×[ch] canvas, transparent past the layer: the canvas px whose premultiplied channels each differ from those of the px ([x], [y]) by at most [tolerance];
[contiguous] — only those joined to it side by side. A byte per canvas px */
fun wandRegion(bmp: Bitmap?, w: Int, h: Int, m: Matrix, mask: LayerMask?, patch: Tiles?, cw: Int, ch: Int, x: Int, y: Int, tolerance: Int, contiguous: Boolean): Region {
    val l = LayerLines(bmp, w, h, m, mask, patch)
    val pt = floatArrayOf(x + 0.5f, y + 0.5f).also { Matrix().apply { m.invert(this) }.mapPoints(it) }
    val seed = IntArray(1)
    if (pt[0] >= 0f && pt[1] >= 0f && pt[0] < w && pt[1] < h) readLine(bmp, mask, patch, seed, floor(pt[0]).toInt(), floor(pt[1]).toInt(), true, 1, IntArray(TILE))
    val sa = seed[0] ushr 24
    val sr = premul(seed[0], 16)
    val sg = premul(seed[0], 8)
    val sb = premul(seed[0], 0)
    fun near(c: Int) = abs((c ushr 24) - sa) <= tolerance && abs(premul(c, 16) - sr) <= tolerance && abs(premul(c, 8) - sg) <= tolerance && abs(premul(c, 0) - sb) <= tolerance

    val grid = ByteArray(cw * ch)
    if (near(0)) grid.fill(MATCH)
    for (i in l.lines) {
        pt[0] = (if (l.alongX) l.start else i) + 0.5f
        pt[1] = (if (l.alongX) i else l.start) + 0.5f
        m.mapPoints(pt)
        val cy = floor(pt[1]).toInt()
        if (cy !in 0 until ch) continue
        val cx0 = floor(pt[0]).toInt()
        l.read(i)
        for (a in 0 until l.len) {
            val cx = cx0 + l.step * a
            if (cx in 0 until cw) grid[cy * cw + cx] = if (near(l.px[a])) MATCH else 0
        }
    }
    if (contiguous) grid.flood(cw, y * cw + x)
    val pick = if (contiguous) FILLED else MATCH
    var row = 0
    return runsRegion(0 until ch, 0, cw, true, null, Region(0, 0, cw, ch), { row = it * cw }) { grid[row + it] == pick }
}

/** Scanline flood fill: the MATCH px joined side by side to [start] become FILLED; [cols] px a row */
private fun ByteArray.flood(cols: Int, start: Int) {
    var stack = IntArray(1024) // a px of each run still to fill
    var n = 0
    stack[n++] = start
    while (n > 0) {
        val s = stack[--n]
        if (this[s] != MATCH) continue
        val row = s - s % cols
        var l = s
        while (l > row && this[l - 1] == MATCH) l--
        var r = s + 1
        while (r < row + cols && this[r] == MATCH) r++
        fill(FILLED, l, r)
        for (side in 0..1) {
            val off = if (side == 0) -cols else cols
            if (row + off !in indices) continue
            var k = l + off
            while (k < r + off) {
                if (this[k] != MATCH) { k++; continue }
                if (n == stack.size) stack = stack.copyOf(n * 2)
                stack[n++] = k
                while (k < r + off && this[k] == MATCH) k++
            }
        }
    }
}

/** Bilinear: at exactly half it's a moiré-free 2×2 average. Only along [x] / [y] if one is off */
fun Bitmap.halved(x: Boolean = true, y: Boolean = true): Bitmap = scale(if (x) (width / 2).coerceAtLeast(1) else width, if (y) (height / 2).coerceAtLeast(1) else height)

/** Halved along each axis while its [x] / [y] holds: the other one's detail is kept. Intermediate copies are recycled, [this] is not; nothing to halve → returns [this] */
inline fun Bitmap.halveWhile(x: (Bitmap) -> Boolean, y: (Bitmap) -> Boolean): Bitmap {
    var s = this
    while (true) {
        val hx = s.width > 1 && x(s)
        val hy = s.height > 1 && y(s)
        if (!hx && !hy) return s
        val half = s.halved(hx, hy)
        if (s !== this) s.recycle()
        s = half
    }
}

/** Of its kind: ALPHA_8 (a mask) has no color space */
fun Bitmap.blankLike(w: Int, h: Int): Bitmap =
    if (config == Bitmap.Config.ALPHA_8) createBitmap(w, h, Bitmap.Config.ALPHA_8) else createBitmap(w, h, Bitmap.Config.ARGB_8888, true, colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))

/** Level of the smallest 2ⁿ× smaller copy shown downscaled by at most 2× at [k] px per original px */
fun levelFor(k: Float) = if (k >= 1f) 0 else floor(log2(1f / k)).toInt()

/** Lossless: the file decodes exactly to these pixels */
fun Bitmap.writePng(f: File) = f.outputStream().buffered(IO_BUFFER).use { if (!compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encode failed") }
