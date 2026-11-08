package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val BLUR_EXACT = 8f // σ при запекании (и наибольший живой), px: шире — в уменьшенной копии
private const val CELLS = TILE / 4 // поле 3σ, px растра, с которого окрестность плитки берётся из общих ячеек: её прямой рендер уже ≥ 2,25 плитки
const val SMEAR_MAX = 128f // радиус «Пальца» при запекании, px: шире — в уменьшенной копии
const val SMEAR_STEP = 2f // шаг дабов «Пальца», px его растра: крупнее — видны круги
private const val STEP_SLACK = 0.99f // доля SMEAR_STEP, ближе которой даб пропускается: даб ровно через шаг не теряется на округлении float
private const val BOXES = 3
private const val KEEP_ONE = 1 shl 15
private const val CHANGED = 16 // изменение пикселя (из 255), с которого уменьшенная копия «Пальца» ложится целиком

/** Radii of BOXES box blurs approximating a Gaussian of σ [sigma] */
private fun boxRadii(sigma: Float): IntArray {
    val ideal = sqrt(12 * sigma * sigma / BOXES + 1)
    val wl = floor(ideal).toInt().let { if (it % 2 == 0) it - 1 else it }
    val m = ((12 * sigma * sigma - BOXES * wl * wl - 4 * BOXES * wl - 3 * BOXES) / (-4f * wl - 4)).roundToInt()
    return IntArray(BOXES) { (if (it < m) wl else wl + 2) / 2 }
}

/** RGBA bytes, px [step] B apart along a line, lines [next] B apart; edge px repeated */
private fun box(src: ByteArray, dst: ByteArray, lines: Int, len: Int, step: Int, next: Int, rb: Int) {
    val div = 2 * rb + 1
    val last = (len - 1) * step
    for (l in 0 until lines) for (c in l * next until l * next + 4) {
        var s = 0
        for (i in -rb..rb) s += src[c + i.coerceIn(0, len - 1) * step].toInt() and 0xFF
        var at = c
        for (i in 0 until len) {
            dst[at] = ((s + rb) / div).toByte()
            s += (src[c + minOf((i + rb + 1) * step, last)].toInt() and 0xFF) - (src[c + maxOf((i - rb) * step, 0)].toInt() and 0xFF)
            at += step
        }
    }
}

/** Returns whichever of [px], [tmp] holds the result */
private fun gauss(px: ByteArray, tmp: ByteArray, w: Int, h: Int, sx: Float, sy: Float): ByteArray {
    var a = px
    var b = tmp
    val rx = boxRadii(sx)
    val ry = boxRadii(sy)
    for (i in 0 until BOXES) {
        if (rx[i] > 0) { box(a, b, h, w, 4, 4 * w, rx[i]); a = b.also { b = a } }
        if (ry[i] > 0) { box(a, b, w, h, 4 * w, 4, ry[i]); a = b.also { b = a } }
    }
    return a
}

/** A [w]×[h] raster, Gaussian-blurred by σ [sigma] (raster px) tile by tile, raster edge repeated; σ over [limit] — in a copy 2ⁿ× smaller, stretched back.
3σ ≥ CELLS: the copy comes from cells — TILE blocks drawn once each and halved, shared by the tiles around; else each tile draws its surroundings itself.
Tiles may be made on several threads at once */
class Blurred(private val w: Int, private val h: Int, private val sigma: Float, private val limit: Float) {
    private val n = run { var n = 0; while (TILE shr n > 1 && sigma / (1 shl n) > limit) n++; n }
    private val cols = (w + TILE - 1) / TILE
    private val cells = if (3 * sigma >= CELLS) arrayOfNulls<ByteArray>(cols * ((h + TILE - 1) / TILE)) else null
    private val locks = Array(cells?.size ?: 0) { Any() } // a cell is made once, the others wait for it
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** [out] (TILE²) gets tile ([x0], [y0]) of the raster [render] draws (the calling thread's) */
    fun into(out: Bitmap, x0: Int, y0: Int, render: (Bitmap, Int, Int) -> Unit) {
        val a = Rect() // raster px the copy covers
        val c = cells
        val b = if (c == null) around(out, x0, y0, a, render) else gathered(c, out, x0, y0, a, render)
        out.eraseColor(Color.TRANSPARENT)
        if (b == null) return
        val px = ByteArray(b.byteCount).also { b.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        b.copyPixelsFromBuffer(ByteBuffer.wrap(gauss(px, ByteArray(px.size), b.width, b.height, sigma * b.width / a.width(), sigma * b.height / a.height())))
        Canvas(out).drawBitmap(b, null, RectF(a).apply { offset(-x0.toFloat(), -y0.toFloat()) }, paint)
        b.recycle()
    }

    /** The tile's surroundings drawn at full size, halved while σ is over the limit */
    private fun around(out: Bitmap, x0: Int, y0: Int, a: Rect, render: (Bitmap, Int, Int) -> Unit): Bitmap? {
        val m = ceil(3 * sigma).toInt()
        a.set(x0 - m, y0 - m, x0 + TILE + m, y0 + TILE + m)
        if (!a.intersect(0, 0, w, h)) return null
        val src = out.blankLike(a.width(), a.height())
        render(src, a.left, a.top)
        val s = src.halveWhile({ sigma * it.width / a.width() > limit }, { sigma * it.height / a.height() > limit })
        if (s !== src) src.recycle()
        return if (s.isMutable) s else (s.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError()).also { s.recycle() }
    }

    /** The tile's surroundings 2ⁿ× smaller, from [cells]; [out] is drawn in meanwhile */
    private fun gathered(cells: Array<ByteArray?>, out: Bitmap, x0: Int, y0: Int, a: Rect, render: (Bitmap, Int, Int) -> Unit): Bitmap? {
        val c = TILE shr n
        val m = ceil(3 * sigma / (1 shl n)).toInt()
        val g = Rect((x0 shr n) - m, (y0 shr n) - m, (x0 shr n) + c + m, (y0 shr n) + c + m)
        if (!g.intersect(0, 0, (w + (1 shl n) - 1) shr n, (h + (1 shl n) - 1) shr n)) return null
        val gw = g.width()
        val px = ByteArray(4 * gw * g.height())
        for (y in g.top until g.bottom) {
            var x = g.left
            while (x < g.right) {
                val run = minOf(g.right - x, c - x % c)
                System.arraycopy(cell(cells, y / c * cols + x / c, out, render), 4 * (y % c * c + x % c), px, 4 * ((y - g.top) * gw + x - g.left), 4 * run)
                x += run
            }
        }
        a.set(g.left shl n, g.top shl n, g.right shl n, g.bottom shl n)
        return out.blankLike(gw, g.height()).apply { copyPixelsFromBuffer(ByteBuffer.wrap(px)) }
    }

    /** Block [k] drawn into [scratch] (TILE²), edge px repeated past the raster (the halved edge isn't faded), halved n times */
    private fun cell(cells: Array<ByteArray?>, k: Int, scratch: Bitmap, render: (Bitmap, Int, Int) -> Unit): ByteArray = synchronized(locks[k]) {
        cells[k] ?: run {
            val x = k % cols * TILE
            val y = k / cols * TILE
            render(scratch, x, y)
            val vw = minOf(TILE, w - x)
            val vh = minOf(TILE, h - y)
            if (vw < TILE || vh < TILE) scratch.clampPad(vw, vh, ByteArray(4 * TILE * TILE))
            val s = scratch.halveWhile({ it.width > TILE shr n }, { it.height > TILE shr n })
            ByteArray(s.byteCount).also { s.copyPixelsToBuffer(ByteBuffer.wrap(it)); if (s !== scratch) s.recycle(); cells[k] = it }
        }
    }
}

/** Finger style smudge over a [w]×[h] raster in TILE tiles of premultiplied RGBA, made on first touch by [render]. Each dab keeps [keep] of the carried color:
the px under the brush move by 2r·keep/(1 − keep) whatever the step. Dabs closer than SMEAR_STEP are skipped. [origins] — needed for into with k < 1.
After the dabs [render] may run on another thread (prepare): it must have its own canvas */
class Smear(private val w: Int, private val h: Int, r: Float, val hardness: Float, val keep: Float, private val like: Bitmap?, origins: Boolean = false,
    private val render: (Bitmap, Int, Int) -> Unit) {
    private val cols = (w + TILE - 1) / TILE
    private val tiles = arrayOfNulls<ByteArray>(cols * ((h + TILE - 1) / TILE))
    private val origin = if (origins) arrayOfNulls<ByteArray>(tiles.size) else null
    val dirty = BooleanArray(tiles.size)
    private val ri = ceil(r).toInt()
    private val n = 2 * ri + 1
    private val profile = IntArray(n * n) { // 256 = 1
        val d = hypot((it % n - ri).toFloat(), (it / n - ri).toFloat()) / r
        if (d >= 1f) 0 else if (d <= hardness) 256 else ((1 - d) / (1 - hardness) * 256).roundToInt()
    }
    private val acc = IntArray(4 * n * n) // carried color, channel × 256
    private val fp = ByteArray(4 * n * n)
    private val raw = ByteArray(4 * TILE * TILE)
    private val q = (keep * KEEP_ONE).roundToInt()
    private var first = true
    private var lastX = 0f
    private var lastY = 0f
    private var scratch: Bitmap? = null

    fun dab(x: Float, y: Float) {
        if (!first && hypot(x - lastX, y - lastY) < SMEAR_STEP * STEP_SLACK) return
        lastX = x
        lastY = y
        val ox = floor(x).toInt() - ri
        val oy = floor(y).toInt() - ri
        copy(ox, oy, n, n, fp, false)
        Kernels.smear(profile, acc, fp, q, first)
        first = false
        copy(ox, oy, n, n, fp, true)
    }

    /** Reads (edge repeated past the raster) or writes back ([back], inside only) a [bw]×[bh] block */
    private fun copy(ox: Int, oy: Int, bw: Int, bh: Int, buf: ByteArray, back: Boolean, of: Array<ByteArray?> = tiles) {
        for (j in 0 until bh) {
            val y = oy + j
            if (back && y !in 0 until h) continue
            val ty = y.coerceIn(0, h - 1)
            var i = 0
            while (i < bw) {
                val x = ox + i
                val inside = x in 0 until w
                val cx = x.coerceIn(0, w - 1)
                val run = if (inside) minOf(bw - i, w - x, TILE - x % TILE) else 1
                if (inside || !back) {
                    val k = ty / TILE * cols + cx / TILE
                    val at = 4 * (ty % TILE * TILE + cx % TILE)
                    tile(k)
                    if (back) { System.arraycopy(buf, 4 * (j * bw + i), tiles[k]!!, at, 4 * run); dirty[k] = true }
                    else System.arraycopy(of[k]!!, at, buf, 4 * (j * bw + i), 4 * run)
                }
                i += run
            }
        }
    }

    private fun tile(k: Int) = tiles[k] ?: ByteArray(4 * TILE * TILE).also { t ->
        val b = scratch ?: patchTile(like).also { scratch = it }
        render(b, k % cols * TILE, k / cols * TILE)
        b.copyPixelsToBuffer(ByteBuffer.wrap(t))
        tiles[k] = t
        origin?.set(k, t.copyOf())
    }

    /** [out] (holding the raster as it was) gets tile ([x0], [y0]) of a raster 1/[k] times bigger. k < 1: stretched and mixed in by how much the px changed (CHANGED),
    so lightly touched areas keep their full detail. [buf] — TILE² px; with the calling thread's and the tiles made by [prepare], tiles may be made on several threads */
    fun into(out: Bitmap, x0: Int, y0: Int, k: Float = 1f, buf: ByteArray = raw) {
        if (k == 1f) {
            copy(x0, y0, TILE, TILE, buf, false)
            out.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
            dirty[y0 / TILE * cols + x0 / TILE] = false
            return
        }
        val s = stretched(out, x0, y0, k, tiles)
        val o = stretched(out, x0, y0, k, origin ?: tiles)
        out.copyPixelsToBuffer(ByteBuffer.wrap(buf))
        for (p in 0 until 4 * TILE * TILE step 4) {
            var d = 0
            for (i in p until p + 4) d = maxOf(d, abs((s[i].toInt() and 0xFF) - (o[i].toInt() and 0xFF)))
            if (d == 0) continue
            val t = minOf(d, CHANGED) / CHANGED.toFloat()
            for (i in p until p + 4) {
                val v = buf[i].toInt() and 0xFF
                buf[i] = (v + ((s[i].toInt() and 0xFF) - v) * t).roundToInt().toByte()
            }
        }
        out.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
    }

    /** Makes every tile [into] reads for tile ([x0], [y0]) */
    fun prepare(x0: Int, y0: Int, k: Float = 1f) {
        val b = block(x0, y0, k)
        for (ty in b.top.coerceIn(0, h - 1) / TILE..(b.bottom - 1).coerceIn(0, h - 1) / TILE)
            for (tx in b.left.coerceIn(0, w - 1) / TILE..(b.right - 1).coerceIn(0, w - 1) / TILE) tile(ty * cols + tx)
    }

    /** Raster px [into] reads for tile ([x0], [y0]); k < 1 — one px of margin: no seams between stretched tiles */
    private fun block(x0: Int, y0: Int, k: Float) = if (k == 1f) Rect(x0, y0, x0 + TILE, y0 + TILE)
        else Rect(floor(x0 * k).toInt() - 1, floor(y0 * k).toInt() - 1, ceil((x0 + TILE) * k).toInt() + 1, ceil((y0 + TILE) * k).toInt() + 1)

    private fun stretched(kind: Bitmap, x0: Int, y0: Int, k: Float, of: Array<ByteArray?>): ByteArray {
        val b = block(x0, y0, k)
        val buf = ByteArray(4 * b.width() * b.height())
        copy(b.left, b.top, b.width(), b.height(), buf, false, of)
        val small = kind.blankLike(b.width(), b.height()).apply { copyPixelsFromBuffer(ByteBuffer.wrap(buf)) }
        val big = kind.blankLike(TILE, TILE)
        Canvas(big).drawBitmap(small, null, RectF(b.left / k - x0, b.top / k - y0, b.right / k - x0, b.bottom / k - y0), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        return ByteArray(4 * TILE * TILE).also { big.copyPixelsToBuffer(ByteBuffer.wrap(it)); big.recycle() }
    }

    fun recycle() { scratch?.recycle() }
}

/** A live Finger smudge at the bake's raster, made over the layer's [mask] and [patch]: the bake takes it as is while the layer is still so */
class Smeared(val smear: Smear, val mask: LayerMask?, val patch: Tiles?)
