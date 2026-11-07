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

const val BLUR_EXACT = 8f // σ при запекании, px: шире — в уменьшенной копии
const val SMEAR_MAX = 128f // радиус «Пальца» при запекании, px: шире — в уменьшенной копии
const val SMEAR_STEP = 2f // шаг дабов «Пальца», px его растра: крупнее — видны круги
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

/** Tile ([x0], [y0]) of a [w]×[h] raster drawn by [render], Gaussian-blurred, raster edge repeated. σ over [limit] — in a halved copy, stretched back */
fun blurredTile(out: Bitmap, x0: Int, y0: Int, w: Int, h: Int, sigma: Float, limit: Float, render: (Bitmap, Int, Int) -> Unit) {
    out.eraseColor(Color.TRANSPARENT)
    val m = ceil(3 * sigma).toInt()
    val a = Rect(x0 - m, y0 - m, x0 + out.width + m, y0 + out.height + m)
    if (!a.intersect(0, 0, w, h)) return
    val aw = a.width()
    val ah = a.height()
    val src = out.blankLike(aw, ah)
    render(src, a.left, a.top)
    val s = src.halveWhile({ sigma * it.width / aw > limit }, { sigma * it.height / ah > limit })
    if (s !== src) src.recycle()
    val px = ByteArray(s.byteCount).also { s.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
    val b = if (s.isMutable) s else (s.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError()).also { s.recycle() }
    b.copyPixelsFromBuffer(ByteBuffer.wrap(gauss(px, ByteArray(px.size), b.width, b.height, sigma * b.width / aw, sigma * b.height / ah)))
    Canvas(out).drawBitmap(b, null, RectF(a).apply { offset(-x0.toFloat(), -y0.toFloat()) }, Paint(Paint.FILTER_BITMAP_FLAG))
    b.recycle()
}

/** Finger style smudge over a [w]×[h] raster in TILE tiles of premultiplied RGBA, made on first touch by [render]. Each dab keeps [keep] of the carried color:
the px under the brush move by 2r·keep/(1 − keep) whatever the step. Dabs closer than SMEAR_STEP are skipped. [origins] — needed for into with k < 1 */
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
        if (!first && hypot(x - lastX, y - lastY) < SMEAR_STEP) return
        lastX = x
        lastY = y
        val ox = floor(x).toInt() - ri
        val oy = floor(y).toInt() - ri
        copy(ox, oy, n, n, fp, false)
        for (k in profile.indices) {
            val m = profile[k]
            if (m == 0) continue
            for (i in 4 * k until 4 * k + 4) {
                val v = fp[i].toInt() and 0xFF
                if (first) { acc[i] = v shl 8; continue }
                val a = (q * acc[i] + (KEEP_ONE - q) * (v shl 8) + KEEP_ONE / 2) shr 15
                acc[i] = a
                fp[i] = (v + (((a - (v shl 8)) * m + (1 shl 15)) shr 16)).toByte()
            }
        }
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
    so lightly touched areas keep their full detail */
    fun into(out: Bitmap, x0: Int, y0: Int, k: Float = 1f) {
        if (k == 1f) {
            copy(x0, y0, TILE, TILE, raw, false)
            out.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
            dirty[y0 / TILE * cols + x0 / TILE] = false
            return
        }
        val s = stretched(out, x0, y0, k, tiles)
        val o = stretched(out, x0, y0, k, origin ?: tiles)
        out.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        for (p in 0 until 4 * TILE * TILE step 4) {
            var d = 0
            for (i in p until p + 4) d = maxOf(d, abs((s[i].toInt() and 0xFF) - (o[i].toInt() and 0xFF)))
            if (d == 0) continue
            val t = minOf(d, CHANGED) / CHANGED.toFloat()
            for (i in p until p + 4) {
                val v = raw[i].toInt() and 0xFF
                raw[i] = (v + ((s[i].toInt() and 0xFF) - v) * t).roundToInt().toByte()
            }
        }
        out.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
    }

    /** One px of margin around the tile: no seams between stretched tiles */
    private fun stretched(kind: Bitmap, x0: Int, y0: Int, k: Float, of: Array<ByteArray?>): ByteArray {
        val ox = floor(x0 * k).toInt() - 1
        val oy = floor(y0 * k).toInt() - 1
        val bw = ceil((x0 + TILE) * k).toInt() + 1 - ox
        val bh = ceil((y0 + TILE) * k).toInt() + 1 - oy
        val buf = ByteArray(4 * bw * bh)
        copy(ox, oy, bw, bh, buf, false, of)
        val small = kind.blankLike(bw, bh).apply { copyPixelsFromBuffer(ByteBuffer.wrap(buf)) }
        val big = kind.blankLike(TILE, TILE)
        Canvas(big).drawBitmap(small, null, RectF(ox / k - x0, oy / k - y0, (ox + bw) / k - x0, (oy + bh) / k - y0), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        return ByteArray(4 * TILE * TILE).also { big.copyPixelsToBuffer(ByteBuffer.wrap(it)); big.recycle() }
    }

    fun recycle() { scratch?.recycle() }
}
