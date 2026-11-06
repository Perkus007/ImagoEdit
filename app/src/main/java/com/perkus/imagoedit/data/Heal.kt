package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val SWEEPS = 2 // проходы сглаживания (красно-чёрный Гаусс — Зейдель) до и после спуска на грубый уровень в V-цикле
private const val COARSEST_SWEEPS = 50 // проходы на самом грубом уровне: там лишь несколько клеток, решается почти точно
private const val MAX_CYCLES = 30 // наибольшее число V-циклов на канал
private const val SETTLED = 0.02f // V-цикл не сдвинул ни одну клетку больше чем на столько (из 255) — сошлось: ошибка того же порядка, глазу не видна
private const val FULL = 255 * 255 // покрытие × непрозрачность штриха целиком

/** A healing stroke's result over [box] (file px): its coverage [cover] ([stride] B a row) and the healed pixels [px] (premultiplied RGBA, [box]'s width a row) */
internal class Healed(private val box: Rect, private val cover: ByteArray, private val stride: Int, private val px: ByteArray) {
    private val raw = ByteArray(TILE * TILE * 4)

    /** Patch tile [t] at file px ([x0], [y0]) — the layer before the stroke — toward the healed pixels by the coverage × [opacity] (0..255) */
    fun into(t: Bitmap, x0: Int, y0: Int, opacity: Int) {
        val left = maxOf(x0, box.left)
        val top = maxOf(y0, box.top)
        val right = minOf(x0 + TILE, box.right)
        val bottom = minOf(y0 + TILE, box.bottom)
        if (left >= right || top >= bottom) return
        t.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        val bw = box.width()
        for (y in top until bottom) for (x in left until right) {
            val k = (cover[(y - box.top) * stride + x - box.left].toInt() and 0xFF) * opacity
            if (k == 0) continue
            val ti = ((y - y0) * TILE + x - x0) * 4
            val hi = ((y - box.top) * bw + x - box.left) * 4
            for (c in 0..3) raw[ti + c] = (((raw[ti + c].toInt() and 0xFF) * (FULL - k) + (px[hi + c].toInt() and 0xFF) * k + FULL / 2) / FULL).toByte()
        }
        t.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
    }
}

/** Off the main thread. This healing stroke's result (Poisson cloning): where it covers anything (Ω) — the source ([clone]'s offset) plus a membrane, harmonic in Ω and on its rim
the layer minus the source: the source's texture takes the surroundings' tone and color with no seam. The layer's edge is no rim: nothing to meet there.
[w]×[h] — the layer, file px; [rects] — the selection's, file px (null — none); [cover], [render] — the stroke's coverage, the layer as it is, from file px into a bitmap;
[base] — the layer's pixels (their kind). Covers nothing → null. Each channel is solved in turn: RAM for one */
internal fun Stroke.healed(w: Int, h: Int, rects: IntArray?, cover: (Bitmap, Int, Int) -> Unit, render: (Bitmap, Int, Int) -> Unit, base: Bitmap?): Healed? {
    val c = clone ?: return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (i in 0 until xy.size / 2) {
        left = minOf(left, xy[2 * i])
        right = maxOf(right, xy[2 * i])
        top = minOf(top, xy[2 * i + 1])
        bottom = maxOf(bottom, xy[2 * i + 1])
    }
    // The dabs' reach and the rim around it
    val box = Rect(floor(left - r).toInt() - 1, floor(top - r).toInt() - 1, ceil(right + r).toInt() + 1, ceil(bottom + r).toInt() + 1)
    if (!box.intersect(0, 0, w, h)) return null
    if (rects != null) {
        val s = Rect()
        for (k in rects.indices step 4) s.union(rects[k], rects[k + 1], rects[k + 2], rects[k + 3])
        s.inset(-1, -1)
        if (!box.intersect(s)) return null
    }
    val bw = box.width()
    val bh = box.height()
    val n = bw * bh
    val a = createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
    val stride = a.rowBytes
    val cov = ByteArray(stride * bh)
    try {
        cover(a, box.left, box.top)
        a.copyPixelsToBuffer(ByteBuffer.wrap(cov))
    } finally { a.recycle() }
    val inside = BooleanArray(n)
    var any = false
    for (y in 0 until bh) for (x in 0 until bw) if (cov[y * stride + x].toInt() != 0) { inside[y * bw + x] = true; any = true }
    if (!any) return null

    // The source over the box; the layer itself on the rim: the cells just outside Ω, beside it
    val px = ByteArray(4 * n)
    var rim = IntArray(1024)
    var near = IntArray(1024) // the layer's pixel there, RGBA bytes from the low one
    var m = 0
    val tile = patchTile(base)
    val raw = ByteArray(TILE * TILE * 4)
    try {
        for (by in 0 until bh step TILE) for (bx in 0 until bw step TILE) {
            val tw = minOf(TILE, bw - bx)
            val th = minOf(TILE, bh - by)
            render(tile, box.left + bx + c.dx, box.top + by + c.dy)
            tile.copyPixelsToBuffer(ByteBuffer.wrap(raw))
            for (y in 0 until th) System.arraycopy(raw, y * TILE * 4, px, ((by + y) * bw + bx) * 4, tw * 4)
            var drawn = false
            for (y in by until by + th) for (x in bx until bx + tw) {
                val i = y * bw + x
                if (inside[i] || !(x > 0 && inside[i - 1] || x < bw - 1 && inside[i + 1] || y > 0 && inside[i - bw] || y < bh - 1 && inside[i + bw])) continue
                if (!drawn) {
                    render(tile, box.left + bx, box.top + by)
                    tile.copyPixelsToBuffer(ByteBuffer.wrap(raw))
                    drawn = true
                }
                if (m == rim.size) { rim = rim.copyOf(2 * m); near = near.copyOf(2 * m) }
                val j = ((y - by) * TILE + x - bx) * 4
                rim[m] = i
                near[m] = (raw[j].toInt() and 0xFF) or ((raw[j + 1].toInt() and 0xFF) shl 8) or ((raw[j + 2].toInt() and 0xFF) shl 16) or ((raw[j + 3].toInt() and 0xFF) shl 24)
                m++
            }
        }
    } finally { tile.recycle() }

    val g = Grid(bw, bh, inside, null)
    for (ch in 0..3) {
        var sum = 0f
        var off = false
        for (j in 0 until m) {
            val i = rim[j]
            val d = ((near[j] ushr (8 * ch)) and 0xFF) - (px[4 * i + ch].toInt() and 0xFF)
            g.v[i] = d.toFloat()
            sum += d
            if (d != 0) off = true
        }
        if (!off) continue // the source meets the surroundings as is
        val mean = sum / m
        for (i in 0 until n) if (inside[i]) g.v[i] = mean
        g.solve()
        for (i in 0 until n) if (inside[i]) {
            val k = 4 * i + ch
            px[k] = ((px[k].toInt() and 0xFF) + g.v[i]).roundToInt().coerceIn(0, 255).toByte()
        }
    }
    // Premultiplied: no color past alpha
    for (i in 0 until n) if (inside[i]) {
        val al = px[4 * i + 3].toInt() and 0xFF
        for (k in 4 * i until 4 * i + 3) if ((px[k].toInt() and 0xFF) > al) px[k] = al.toByte()
    }
    return Healed(box, cov, stride, px)
}

/** A level of the membrane solve (multigrid): [w]×[h] cells, unknown where [inside], held elsewhere. Level 0 holds the boundary there (only the cells beside Ω are read);
a coarser one solves for the finer one's error: holds 0, [b] — the finer residual summed (null — 0). No neighbor past the edge: the layer's edge, nothing to meet.
A coarser cell is inside only where all its 2×2 are: each level keeps a held rim, never left with no boundary */
private class Grid(val w: Int, val h: Int, val inside: BooleanArray, val b: FloatArray?) {
    val v = FloatArray(w * h)
    private val coarse = coarser()

    private fun coarser(): Grid? {
        if (w <= 1 && h <= 1) return null
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val sub = BooleanArray(cw * ch)
        var any = false
        for (y in 0 until ch) for (x in 0 until cw) {
            var all = true
            for (dy in 0..1) for (dx in 0..1) {
                val fx = 2 * x + dx
                val fy = 2 * y + dy
                if (fx < w && fy < h && !inside[fy * w + fx]) all = false
            }
            if (all) { sub[y * cw + x] = true; any = true }
        }
        return if (any) Grid(cw, ch, sub, FloatArray(cw * ch)) else null
    }

    /** V-cycles until one moves no cell by SETTLED */
    fun solve() {
        repeat(MAX_CYCLES) { if (cycle() < SETTLED) return }
    }

    /** The most a cell moved in the cycle's last steps here */
    private fun cycle(): Float {
        val c = coarse
        var moved = 0f
        if (c == null) {
            repeat(COARSEST_SWEEPS) { moved = smooth() }
            return moved
        }
        repeat(SWEEPS) { smooth() }
        restrict(c)
        c.cycle()
        moved = prolong(c)
        repeat(SWEEPS) { moved = maxOf(moved, smooth()) }
        return moved
    }

    private fun around(i: Int, x: Int, y: Int): Float {
        var s = 0f
        if (x > 0) s += v[i - 1]
        if (x < w - 1) s += v[i + 1]
        if (y > 0) s += v[i - w]
        if (y < h - 1) s += v[i + w]
        return s
    }

    private fun degree(x: Int, y: Int) = (if (x > 0) 1 else 0) + (if (x < w - 1) 1 else 0) + (if (y > 0) 1 else 0) + (if (y < h - 1) 1 else 0)

    /** Red-black Gauss–Seidel over Σneighbors − degree·v = b; the most a cell moved */
    private fun smooth(): Float {
        val rhs = b
        var most = 0f
        for (color in 0..1) for (y in 0 until h) {
            var x = (y + color) and 1
            while (x < w) {
                val i = y * w + x
                val k = degree(x, y)
                if (inside[i] && k > 0) {
                    val e = (around(i, x, y) - (if (rhs == null) 0f else rhs[i])) / k
                    val d = abs(e - v[i])
                    if (d > most) most = d
                    v[i] = e
                }
                x += 2
            }
        }
        return most
    }

    /** The residual summed into [c]'s inside cells — its right side at twice the spacing; its values zeroed */
    private fun restrict(c: Grid) {
        val cb = c.b ?: return
        val rhs = b
        cb.fill(0f)
        c.v.fill(0f)
        for (y in 0 until h) {
            val row = (y shr 1) * c.w
            for (x in 0 until w) {
                val i = y * w + x
                if (!inside[i]) continue
                val ci = row + (x shr 1)
                if (c.inside[ci]) cb[ci] += (if (rhs == null) 0f else rhs[i]) - around(i, x, y) + degree(x, y) * v[i]
            }
        }
    }

    /** [c]'s correction added bilinearly (cell-centered: 9/16, 3/16, 3/16, 1/16) where inside; the most added */
    private fun prolong(c: Grid): Float {
        val cv = c.v
        var most = 0f
        for (y in 0 until h) {
            val cy = y shr 1
            val ny = (if ((y and 1) == 0) cy - 1 else cy + 1).coerceIn(0, c.h - 1)
            for (x in 0 until w) {
                val i = y * w + x
                if (!inside[i]) continue
                val cx = x shr 1
                val nx = (if ((x and 1) == 0) cx - 1 else cx + 1).coerceIn(0, c.w - 1)
                val e = 0.5625f * cv[cy * c.w + cx] + 0.1875f * (cv[cy * c.w + nx] + cv[ny * c.w + cx]) + 0.0625f * cv[ny * c.w + nx]
                v[i] += e
                if (abs(e) > most) most = abs(e)
            }
        }
        return most
    }
}
