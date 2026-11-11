package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import com.perkus.imagoedit.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val MAX_PCT = 100
val PERCENT = 0..MAX_PCT
val SIGNED = -MAX_PCT..MAX_PCT
private const val MID = 127.5f // ось контраста
private const val CONTRAST_MIN = 0.5f // на −100%: серый, перекрытый оригиналом с такой непрозрачностью
private const val TEMP = 0.3f // сдвиг красного и синего на краю «Температуры»
private const val BALANCE_GAIN = 2f // усиление канала Цветового баланса на +100% (на −100% — обратное)
private const val GRID_PX = 1 shl 18 // клеток сетки окружения, не больше (от 1/4): радиус окна — от ~6 клеток
private const val REACH = 0.025f // радиус окна окружения: доля средней стороны области √(w·h); фильтр — два окна, охват вдвое
private const val EDGE = 0.1f // перепад светлоты (L* / 100), с которого окно — край, а не фактура: окружение через него не смешивается
private const val MIN_MASS = 1e-3f // непрозрачность окна в px, ниже — пустое

/** RGB × [k] + [add] (of 255); alpha kept or [opaque] */
private fun scaled(k: Float, add: Float, opaque: Boolean = false) =
    ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, add, 0f, k, 0f, 0f, add, 0f, 0f, k, 0f, add, 0f, 0f, 0f, if (opaque) 0f else 1f, if (opaque) 255f else 0f))

private fun matrix(m: ColorMatrix.() -> Unit) = listOf(ColorMatrix().apply(m))

/** ×[k] around MID: below 0 down to CONTRAST_MIN, up to ×2 */
private fun contrast(v: Float): List<ColorMatrix> {
    val k = 1 + v * (if (v < 0) 1 - CONTRAST_MIN else 1f)
    return listOf(scaled(k, MID * (1 - k)))
}

/** Color balance [b] (red, green, blue, of MAX_PCT): channel gains up to ×BALANCE_GAIN, scaled to keep gray's luminance — equal ones change nothing — and gray
added or taken back to keep each px' own. Nothing to change → null */
private fun balanced(b: List<Int>): ColorMatrix? {
    if (b.all { it == b[0] }) return null
    val g = FloatArray(3) { BALANCE_GAIN.pow(b[it] / MAX_PCT.toFloat()) }
    val gray = lum(g)
    val m = FloatArray(20)
    for (c in 0..2) for (k in 0..2) m[5 * c + k] = LUMA[k] * (1 - g[k] / gray) + (if (c == k) g[c] / gray else 0f)
    m[18] = 1f
    return ColorMatrix(m)
}

/** [tone] — one slider over [range], the correction by its value / 100 (0 — none) as a Tone stage; null — its own panel. Invert — |x − 255·v|:
max(0, x − g) + max(0, g − x), no gray halfway */
enum class Correction(val title: Int, val image: Int, val range: IntRange = SIGNED, val tone: ((Float) -> List<ColorMatrix>)? = null) {
    Invert(R.string.correction_invert, R.drawable.correction_invert, PERCENT, { v -> listOf(scaled(1f, -255 * v, true), scaled(-1f, 255 * v, true)) }),
    BlackWhite(R.string.correction_black_white, R.drawable.correction_black_white, PERCENT, { v -> matrix { setSaturation(1 - v) } }),
    Saturation(R.string.color_saturation, R.drawable.correction_saturation, tone = { v -> matrix { setSaturation(1 + v) } }),
    Brightness(R.string.color_brightness, R.drawable.correction_brightness, tone = { v -> listOf(scaled(1 + v, 0f)) }),
    Contrast(R.string.correction_contrast, R.drawable.correction_contrast, tone = ::contrast),
    Temperature(R.string.correction_temperature, R.drawable.correction_temperature, tone = { v -> matrix { setScale(1 + TEMP * v, 1f, 1 - TEMP * v, 1f) } }),
    ReplaceColor(R.string.correction_replace_color, R.drawable.correction_replace_color),
    ShadowsHighlights(R.string.correction_shadows_highlights, R.drawable.correction_shadows_highlights),
    ColorBalance(R.string.correction_color_balance, R.drawable.correction_color_balance)
}

/** The one-slider corrections' [values] (by Correction.ordinal) in Correction's order, lone matrices joined, Color balance [balance] (red, green, blue) last;
none → null */
fun toneOf(values: List<Int>, balance: List<Int>): Tone? {
    val stages = ArrayList<List<ColorMatrix>>()
    fun add(s: List<ColorMatrix>) {
        val last = stages.lastOrNull()?.singleOrNull()
        if (last != null && s.size == 1) last.postConcat(s[0]) else stages.add(s)
    }
    for (c in Correction.entries) {
        val v = values[c.ordinal]
        if (v != 0) c.tone?.let { add(it(v / MAX_PCT.toFloat())) }
    }
    balanced(balance)?.let { add(listOf(it)) }
    return if (stages.isEmpty()) null else Tone(stages)
}

/** A correction of px, added onto a canvas (PLUS): [stages] one after another, each one matrix keeping alpha or opaque passes summed, then the px' alpha back; none —
the px as they are. Paints are only read: threads share it */
class Tone(stages: List<List<ColorMatrix>>) {
    // A stage onto a new layer: a lone matrix drawn, opaque passes added
    private val passes = stages.map { s -> s.map { m -> Paint().apply { colorFilter = ColorMatrixColorFilter(m); if (s.size > 1) blendMode = BlendMode.PLUS } } }
    private val last = passes.lastOrNull()?.singleOrNull() // applied by the result's own layer
    private val sum = Paint(last ?: Paint()).apply { blendMode = BlendMode.PLUS }
    private val alpha = Paint().apply { blendMode = BlendMode.DST_IN }

    /** [px] draws the px; [inside] — onto the result before it's added (a soft edge) */
    fun add(c: Canvas, px: (Canvas) -> Unit, inside: (Canvas) -> Unit = {}) {
        c.saveClipLayer(sum)
        through(c, passes.lastIndex - (if (last != null) 1 else 0), px)
        inside(c)
        c.restore()
    }

    /** [px] through stages 0..[i] */
    private fun through(c: Canvas, i: Int, px: (Canvas) -> Unit) {
        if (i < 0) return px(c)
        for (p in passes[i]) { c.saveClipLayer(p); through(c, i - 1, px); c.restore() }
        if (passes[i].size > 1) { c.saveClipLayer(alpha); through(c, i - 1, px); c.restore() }
    }
}

/** Most samples (= kernels.c SAMPLES) */
const val SAMPLES = 5

/** Replace color: px near any of the samples [from] (ARGB, ≤ SAMPLES; each on its own) toward [to] at [opacity] (0 — erased), by [threshold] and [amount];
0..1. [seeds] — Contiguous: only px joined to these canvas px (null — any). Native (kernels.c): Oklab, shades and edges carried */
data class Recolor(val from: List<Int>, val to: Int, val opacity: Float, val threshold: Float, val amount: Float, val seeds: List<Point>?) {
    /** Changes nothing: a sample's px are carried as its shades */
    val idle get() = amount <= 0f || from.isEmpty() || opacity >= 1f && from.all { it == to }

    /** [src]'s px into [dst] (as large; null — only tells; may be [src]), only where [only] (null — all; a byte per px) isn't 0; true — some change. Any thread */
    fun swap(src: Bitmap, dst: Bitmap? = src, only: ByteArray? = null) = Kernels.replace(src, dst, from.toIntArray(), to, opacity, threshold, amount, only)

    /** Contiguous (null — not): the px of [r] joined to [seeds]' px (their centers placed in r's px by [m]) through px it replaces at all, 4-connected. [render]
    gives the px from (x, y) in a TILE² bitmap of [worker]'s (null — none there) */
    internal fun joined(r: Rect, m: Matrix, render: (worker: Int, x: Int, y: Int) -> Bitmap?): Joined? {
        val s = seeds ?: return null
        val at = FloatArray(2 * s.size) { i -> (if (i % 2 == 0) s[i / 2].x else s[i / 2].y) + 0.5f }.also { m.mapPoints(it) }
        val w = r.width()
        val h = r.height()
        val grid = ByteArray(w * h)
        val cols = (w + TILE - 1) / TILE
        val near = arrayOfNulls<ByteArray>(WORKERS)
        val samples = from.toIntArray()
        parallel(cols * ((h + TILE - 1) / TILE)) { wk, k ->
            val x = k % cols * TILE
            val y = k / cols * TILE
            val b = render(wk, r.left + x, r.top + y) ?: return@parallel
            val f = near[wk] ?: ByteArray(TILE * TILE).also { near[wk] = it }
            if (!Kernels.near(b, samples, threshold, f)) return@parallel
            for (j in 0 until minOf(TILE, h - y)) System.arraycopy(f, j * TILE, grid, (y + j) * w + x, minOf(TILE, w - x))
        }
        for (i in at.indices step 2) {
            val x = floor(at[i]).toInt() - r.left
            val y = floor(at[i + 1]).toInt() - r.top
            if (x in 0 until w && y in 0 until h) grid.flood(w, y * w + x)
        }
        return Joined(r, grid)
    }
}

/** Contiguous's px of [r]: FILLED in [grid], a byte per px */
internal class Joined(private val r: Rect, private val grid: ByteArray) {
    /** [out] (TILE², a row TILE) gets 1 where the px from ([x], [y]) are joined, else 0; none → false */
    fun at(x: Int, y: Int, out: ByteArray): Boolean {
        out.fill(0)
        val w = r.width()
        val bx = x - r.left
        val by = y - r.top
        var any = false
        for (j in maxOf(0, -by) until minOf(TILE, r.height() - by)) {
            val row = (by + j) * w + bx
            for (i in maxOf(0, -bx) until minOf(TILE, w - bx)) if (grid[row + i] == FILLED) { out[j * TILE + i] = 1; any = true }
        }
        return any
    }
}

/** File px of layer [l] this selection reaches (none — all of them); may be empty */
fun Selection?.fileRect(l: Layer): Rect = Rect(0, 0, l.fileW, l.fileH).also { a ->
    val b = this?.let { s -> Rect().also { RectF(s.reach.bounds).apply { l.canvasToFile().mapRect(this) }.roundOut(it) } }
    if (b != null && !a.intersect(b)) a.setEmpty()
}

/** Shadows/Highlights, −1..1: [highlights], [shadows] — by the lightness of each px' surroundings (Base), its local contrast kept; [whites] — the white point,
[blacks] — the black point (−) or the darkest tones, black kept (+), by its own lightness. Native (kernels.c) */
data class Relight(val highlights: Float, val shadows: Float, val whites: Float, val blacks: Float) {
    val idle get() = highlights == 0f && shadows == 0f && whites == 0f && blacks == 0f

    /** Needs the surroundings */
    val local get() = highlights != 0f || shadows != 0f

    private val gains by lazy { if (local) Kernels.gains(highlights, shadows) else null }
    private val points by lazy { if (whites != 0f || blacks != 0f) Kernels.points(whites, blacks) else null }

    /** [src]'s px into [dst] (as large; null — only tells; may be [src]); src's px (0, 0) at file px ([x], [y]), each [s] file px a side. [base] — the
    surroundings (null — Highlights and Shadows skipped). true — some change. Any thread */
    fun adjust(src: Bitmap, dst: Bitmap?, x: Int, y: Int, s: Int, base: Base?) = Kernels.relight(src, dst, x, y, s, base?.let { gains }, base?.ab, base?.cells, points)
}

/** The lightness of each px' surroundings: A·L + B of its own lightness L, a self-guided filter's weighed by alpha — edge-aware: a step past EDGE stays a step,
no halo. [ab] — A, B of each cell; [cells] — gw, gh, gx, gy, n: gw × gh cells 2ⁿ file px a side from cell (gx, gy), a row gw */
class Base internal constructor(val ab: FloatArray, val cells: IntArray)

/** Lightness sums of file px [area]'s px in cells 2ⁿ px a side from px 0, so each within a tile. Tiles apart may be added at once */
internal class Guide(private val area: Rect) {
    private val n = maxOf(0, ceil(log2(area.width().toFloat() * area.height() / GRID_PX) / 2).toInt())
    private val gx = area.left shr n
    private val gy = area.top shr n
    private val gw = ((area.right - 1) shr n) - gx + 1
    private val gh = ((area.bottom - 1) shr n) - gy + 1
    private val sums = FloatArray(3 * gw * gh) // Σα, ΣαL, ΣαL² a cell
    private val tile = arrayOfNulls<FloatArray>(WORKERS)

    /** [px] — the layer's TILE² from file px ([x], [y]), a tile's corner; [worker]'s (see parallel) */
    fun add(worker: Int, px: Bitmap, x: Int, y: Int) {
        val x0 = maxOf(area.left - x, 0)
        val y0 = maxOf(area.top - y, 0)
        val x1 = minOf(area.right - x, TILE)
        val y1 = minOf(area.bottom - y, TILE)
        if (x0 >= x1 || y0 >= y1) return
        val q = TILE shr n
        val t = tile[worker] ?: FloatArray(3 * q * q).also { tile[worker] = it }
        Kernels.lightness(px, x0, y0, x1, y1, n, t)
        val c0 = x0 shr n
        val len = 3 * (((x1 - 1) shr n) - c0 + 1)
        for (j in (y0 shr n)..((y1 - 1) shr n)) System.arraycopy(t, 3 * (j * q + c0), sums, 3 * (((y shr n) + j - gy) * gw + (x shr n) + c0 - gx), len)
    }

    /** From the sums so far */
    fun base(): Base {
        val r = maxOf(1, (REACH * sqrt(area.width().toFloat() * area.height()) / (1 shl n)).roundToInt())
        val cells = gw * gh
        val mass = FloatArray(cells) { sums[3 * it] }.boxed(r)
        val m1 = FloatArray(cells) { sums[3 * it + 1] }.boxed(r)
        val m2 = FloatArray(cells) { sums[3 * it + 2] }.boxed(r)
        val a = FloatArray(cells)
        val b = FloatArray(cells)
        for (i in 0 until cells) {
            val w = mass[i]
            if (w < MIN_MASS) continue
            val mean = m1[i] / w
            val v = maxOf(m2[i] / w - mean * mean, 0f)
            val k = v / (v + EDGE * EDGE)
            a[i] = k * w
            b[i] = (1 - k) * mean * w
        }
        // A px' A, B — its windows', each weighed by its mass: emptier ones count less
        val sa = a.boxed(r)
        val sb = b.boxed(r)
        val sw = mass.boxed(r)
        val ab = FloatArray(2 * cells)
        for (i in 0 until cells) if (sw[i] >= MIN_MASS) { ab[2 * i] = sa[i] / sw[i]; ab[2 * i + 1] = sb[i] / sw[i] }
        return Base(ab, intArrayOf(gw, gh, gx, gy, n))
    }

    /** Sums over (2[r] + 1)² cells about each, the window cut at the grid's edges */
    private fun FloatArray.boxed(r: Int): FloatArray {
        val rows = FloatArray(size)
        for (y in 0 until gh) {
            val o = y * gw
            var s = 0.0
            for (x in 0 until minOf(r, gw)) s += this[o + x]
            for (x in 0 until gw) {
                if (x + r < gw) s += this[o + x + r]
                if (x > r) s -= this[o + x - r - 1]
                rows[o + x] = s.toFloat()
            }
        }
        val out = FloatArray(size)
        val col = DoubleArray(gw)
        for (y in 0 until minOf(r, gh)) for (x in 0 until gw) col[x] += rows[y * gw + x]
        for (y in 0 until gh) {
            if (y + r < gh) for (x in 0 until gw) col[x] += rows[(y + r) * gw + x]
            if (y > r) for (x in 0 until gw) col[x] -= rows[(y - r - 1) * gw + x]
            for (x in 0 until gw) out[y * gw + x] = col[x].toFloat()
        }
        return out
    }
}

/** The surroundings of file px [r]'s px (empty — none): [render] gives the layer's px from (x, y), a tile's corner, in a TILE² bitmap of [worker]'s.
Off the main thread */
internal fun surroundings(r: Rect, render: (worker: Int, x: Int, y: Int) -> Bitmap): Base? {
    if (r.isEmpty) return null
    val g = Guide(r)
    val tx = r.left / TILE
    val ty = r.top / TILE
    val cols = (r.right - 1) / TILE - tx + 1
    parallel(cols * ((r.bottom - 1) / TILE - ty + 1)) { wk, k ->
        val x = (tx + k % cols) * TILE
        val y = (ty + k / cols) * TILE
        g.add(wk, render(wk, x, y), x, y)
    }
    return g.base()
}
