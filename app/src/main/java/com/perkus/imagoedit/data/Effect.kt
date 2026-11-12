package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.perkus.imagoedit.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val BLUR_MAX = 0.05f // σ размытия на 100%: доля средней стороны холста √(w·h)
private const val STREAK_MAX = 0.15f // длина направленного размытия на 100%: доля средней стороны холста
private const val GLOW_GAIN = 4f // усиление непрозрачности свечения на 100% интенсивности
private const val REACH_SIGMAS = 3.5f // охват размытия в σ: три бокса и растянутая уменьшенная копия
const val MAX_OFFSET = 100 // смещение тени, px холста
val ANGLES = -180..180

/** The carousel's; ReplaceColor — CorrectionTool's (Correction.kt Recolor), the rest — filters */
enum class Effect(override val title: Int, override val image: Int) : CarouselItem {
    GaussianBlur(R.string.effect_gaussian_blur, R.drawable.effect_gaussian_blur),
    MotionBlur(R.string.effect_motion_blur, R.drawable.effect_motion_blur),
    ReplaceColor(R.string.correction_replace_color, R.drawable.correction_replace_color),
    Shadow(R.string.effect_shadow, R.drawable.effect_shadow),
    Glow(R.string.effect_glow, R.drawable.effect_glow)
}

/** [pct] of [max]·[side], squared: fine steps at the low end */
private fun share(pct: Int, side: Float, max: Float) = (pct / MAX_PCT.toFloat()).let { it * it } * max * side

/** A blur slider's σ, canvas px; [side] — the canvas' mean side √(w·h) */
fun blurSigma(pct: Int, side: Float) = share(pct, side, BLUR_MAX)

/** Directional blur's length, canvas px */
fun streakLength(pct: Int, side: Float) = share(pct, side, STREAK_MAX)

/** How far a blur of σ [sigma] spreads, px */
fun blurReach(sigma: Float) = ceil(REACH_SIGMAS * sigma).toInt() + 1

fun glowGain(pct: Int) = GLOW_GAIN * pct / MAX_PCT

/** [deg]° clockwise from +x (canvas px, y down) as a unit vector */
fun angleVector(deg: Int) = Math.toRadians(deg.toDouble()).let { floatArrayOf(cos(it).toFloat(), sin(it).toFloat()) }

/** Makes tile ([x0], [y0]) of a raster into [out] from the raster [render] draws from (x, y) into a bitmap (the calling thread's). Tiles may be made on several
threads at once */
fun interface Filter {
    fun into(out: Bitmap, x0: Int, y0: Int, render: (Bitmap, Int, Int) -> Unit)
}

/** An effect's Filter for a [w]×[h] raster, [m] — canvas px → its px: the baked layer's file, the preview's smaller copy */
typealias FilterOn = (w: Int, h: Int, m: Matrix) -> Filter

/** Canvas vector ([x], [y]) in raster px */
private fun Matrix.vector(x: Float, y: Float) = floatArrayOf(x, y).also { mapVectors(it) }

/** Raster px per canvas px */
private fun Matrix.scale() = vector(1f, 0f).let { hypot(it[0], it[1]) }

fun gaussian(sigma: Float): FilterOn = { w, h, m -> Blurred(w, h, sigma * m.scale(), BLUR_EXACT) }

/** [length] canvas px along [deg]° */
fun streak(deg: Int, length: Float): FilterOn = { w, h, m ->
    val d = angleVector(deg).let { m.vector(it[0], it[1]) }
    val k = hypot(d[0], d[1])
    Streaked(w, h, d[0] / k, d[1] / k, length * k)
}

/** Shadow, glow: the layer's alpha where [sel] selects (none — all of it), blurred by σ [sigma] canvas px, in place; only the alpha counts */
fun castShape(sigma: Float, sel: Selection?): FilterOn = { w, h, m ->
    val b = Blurred(w, h, sigma * m.scale(), BLUR_EXACT)
    Filter { out, x0, y0, render ->
        b.into(out, x0, y0) { px, x, y ->
            render(px, x, y)
            if (sel != null) Canvas(px).run { translate(-x.toFloat(), -y.toFloat()); keepSelected(sel, m, true) }
        }
    }
}

/** RGB → [color], alpha × [gain], clamped: a shape's alpha drawn in the color */
fun castFilter(color: Int, gain: Float) = ColorMatrixColorFilter(floatArrayOf(
    0f, 0f, 0f, 0f, Color.red(color).toFloat(),
    0f, 0f, 0f, 0f, Color.green(color).toFloat(),
    0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
    0f, 0f, 0f, gain, 0f))

private val behind = Paint().apply { blendMode = BlendMode.DST_OVER }

/** [e] drawn onto the px on this canvas (raster px; [m] — canvas px → them): behind them where [sel] selects (none — everywhere), over them elsewhere */
fun Canvas.cast(sel: Selection?, m: Matrix, e: (Canvas) -> Unit) {
    saveClipLayer(behind)
    e(this)
    if (sel != null) keepSelected(sel, m, true)
    restore()
    if (sel == null) return
    saveClipLayer(null)
    e(this)
    keepSelected(sel, m, false)
    restore()
}

/** Shadow, glow baked: castShape moved by ([dx], [dy]) canvas px (whole raster px) in [color] at [gain]× its alpha, cast onto the layer's px */
class Cast(private val sigma: Float, private val dx: Float, private val dy: Float, color: Int, gain: Float, private val sel: Selection?) {
    private val paint = Paint().apply { colorFilter = castFilter(color, gain) } // only read: threads share it

    fun filter(w: Int, h: Int, m: Matrix): Filter {
        val shape = castShape(sigma, sel)(w, h, m)
        val d = m.vector(dx, dy)
        val ix = d[0].roundToInt()
        val iy = d[1].roundToInt()
        return Filter { out, x0, y0, render ->
            val e = out.blankLike(out.width, out.height)
            try {
                shape.into(e, x0 - ix, y0 - iy, render)
                render(out, x0, y0)
                Canvas(out).run {
                    translate(-x0.toFloat(), -y0.toFloat())
                    cast(sel, m) { it.drawBitmap(e, x0.toFloat(), y0.toFloat(), paint) }
                }
            } finally { e.recycle() }
        }
    }
}

/** Directional blur of a [w]×[h] raster: each px the average of a [length] px stretch along unit ([ux], [uy]) through it, read by its digital line — whole px, nothing
resampled across it; the raster's edge repeated. Native (kernels.c) */
class Streaked(private val w: Int, private val h: Int, ux: Float, uy: Float, length: Float) : Filter {
    private val alongX = abs(ux) >= abs(uy)
    private val slope = if (alongX) uy / ux else ux / uy // px across per px along
    private val half = length / 2 * maxOf(abs(ux), abs(uy)) // px along to each side
    private val n = half.toInt()
    private val edge = ((half - n) * 256).roundToInt() // the px past n: their share of 256
    private val reach = ceil((n + 1) * abs(slope)).toInt() + 1 // px across it reaches

    override fun into(out: Bitmap, x0: Int, y0: Int, render: (Bitmap, Int, Int) -> Unit) {
        val p0 = if (alongX) x0 else y0
        val s0 = if (alongX) y0 else x0
        val len = if (alongX) out.width else out.height
        val pa = maxOf(0, p0 - n - 1)
        val pb = minOf(if (alongX) w else h, p0 + len + n + 1)
        val sa = maxOf(0, s0 - reach)
        val sb = minOf(if (alongX) h else w, s0 + (if (alongX) out.height else out.width) + reach)
        if (pa >= pb || sa >= sb) return out.eraseColor(Color.TRANSPARENT)
        val src = out.blankLike(if (alongX) pb - pa else sb - sa, if (alongX) sb - sa else pb - pa)
        try {
            render(src, if (alongX) pa else sa, if (alongX) sa else pa)
            val r = IntArray(len + 2 * n + 2) { floor((p0 - n - 1 + it) * slope + 0.5f).toInt() }
            Kernels.streak(src, out, alongX, pa, sa, p0, s0, n, edge, r)
        } finally { src.recycle() }
    }
}
