package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val PATCH = "patch"

/** Of [base]'s kind and color space; no base — sRGB */
fun patchTile(base: Bitmap?) = base?.blankLike(TILE, TILE) ?: createBitmap(TILE, TILE)

/** Mask tile under every patch tile: the patch replaces those px. One shared immutable instance */
private val cutOut: Bitmap by lazy { blankMaskTile().apply { eraseColor(Color.BLACK) }.copy(Bitmap.Config.ALPHA_8, false) ?: throw OutOfMemoryError() }

/** Clone source: the layer's own px [dx], [dy] file px off, as before the stroke; [heal] — healing brush */
class Clone(val dx: Int, val dy: Int, val heal: Boolean)

/** [layer] — Layer.id; [file] — its pixels at the start; [color] null — erases, unless [clone], [blur], [smudge]. [xy] — dab centers and [r] — file px;
[area] — the selection at the start. [select] — Selection brush: canvas px, [layer] 0, joined to the selection. [figure] — Fill/Stroke, Transition (erases): covers in place of dabs.
[blur] > 0 — Blur Area, σ file px; [smudge] — Blur Finger, Smear.keep; [smeared] — its live smudge, if at the bake's raster; [filter] — an effect (Effect.kt) or
Sharpness: the layer's px through it; [recolor], [relight], then [tone] — Correction: the layer's px (filtered) through them */
class Stroke(val layer: Long, val file: String?, val color: Int?, val xy: FloatArray = FloatArray(0), val r: Float = 0f, val hardness: Float = 1f, val flow: Float = 1f,
    val opacity: Float = 1f, val area: Selection?, val clone: Clone? = null, val select: Region.Op? = null, val figure: Figure? = null,
    val blur: Float = 0f, val smudge: Float? = null, val smeared: Smeared? = null, val tone: Tone? = null, val recolor: Recolor? = null, val relight: Relight? = null,
    val filter: FilterOn? = null) {
    /** Puts px, not only erases */
    val paints get() = color != null || clone != null || blur > 0f || smudge != null || tone != null || recolor != null || relight != null || filter != null
}

/** Fill/Stroke side; name is persisted */
enum class StrokeSide { Outside, Center, Inside }

/** Fill/Stroke coverage, canvas px: [path] filled, or with a [side] its outline stroked [width] px on that side, corners [sharp] (mitered) or round.
[shader] — covers by its alpha (Transition); a new one per draw: threads don't share it */
class Figure(val path: Path, val side: StrokeSide? = null, val width: Float = 0f, val sharp: Boolean = true, val shader: (() -> Shader)? = null) {
    /** A side stroke is twice as wide, half clipped off by the outline */
    private fun styled(p: Paint) = p.apply {
        style = if (side == null) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = if (side == StrokeSide.Center) width else 2 * width
        strokeJoin = if (sharp) Paint.Join.MITER else Paint.Join.ROUND
        strokeMiter = MITER
        shader = this@Figure.shader?.invoke()
    }

    // Exact (the layer grows by it); lazy: a complex outline's stroke takes a while
    @Suppress("DEPRECATION") // computeBounds(RectF) is API 34+
    private val bounds by lazy {
        RectF().also { (if (side == null || side == StrokeSide.Inside) path else Path().apply { styled(Paint()).getFillPath(path, this) }).computeBounds(it, true) }
    }

    fun reach() = RectF(bounds)

    /** Sets [p]'s style and stroke */
    fun draw(c: Canvas, p: Paint) {
        styled(p)
        if (side == null) return c.drawPath(path, p)
        c.save()
        if (side == StrokeSide.Inside) c.clipPath(path) else if (side == StrokeSide.Outside) c.clipOutPath(path)
        c.drawPath(path, p)
        c.restore()
    }
}

/** The canvas rect layer [l] must cover for the stroke to land whole, its reach taken within canvas and selection: a figure's ∪ its own; painting dabs' (Area blur:
with the px it reads) — the whole canvas ∪ its own, at once: later strokes don't grow it again. null — it already does */
fun Stroke.room(l: Layer, cw: Int, ch: Int): Rect? {
    val reach = figure?.reach() ?: if (!paints) return null else RectF().apply {
        val m = r + 3 * blur
        for (i in 0 until xy.size / 2) union(xy[2 * i] - m, xy[2 * i + 1] - m, xy[2 * i] + m, xy[2 * i + 1] + m)
        l.fileToCanvas().mapRect(this)
    }
    val need = Rect().also { reach.roundOut(it) }
    val own = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    if (!need.intersect(0, 0, cw, ch) || area?.let { need.intersect(it.reach.bounds) } == false || own.contains(need)) return null
    return (if (figure == null) Rect(0, 0, cw, ch) else need).apply { union(own) }
}

/** The same stroke on [to], whose px lie where [from]'s did (room grew it): file px ([xy], [clone]) carried over. The live smudge stays behind: it's on [from]'s raster */
fun Stroke.rebased(from: Layer, to: Layer): Stroke {
    val m = from.fileToCanvas().apply { postConcat(to.canvasToFile()) }
    val p = xy.copyOf().also { m.mapPoints(it) }
    val c = clone?.let { cl -> floatArrayOf(cl.dx.toFloat(), cl.dy.toFloat()).let { v -> m.mapVectors(v); Clone(v[0].roundToInt(), v[1].roundToInt(), cl.heal) } }
    return Stroke(layer, to.file, color, p, r, hardness, flow, opacity, area, c, select, figure, blur, smudge, tone = tone, recolor = recolor, relight = relight, filter = filter)
}

private const val TIP_LUT = 1024 // ступени профиля по квадрату расстояния до центра даба
private const val MASK_EDGE = 64f // px уменьшенной маски мягкого мазка на спад края даба: грубее — край при растяжении неточен

/** A brush's profile [y] radii off its center: 1 up to [hardness], then an S-curve to 0 at the edge — no kink for the eye to take for a line */
fun tip(y: Float, hardness: Float): Float {
    if (y <= hardness) return 1f
    if (y >= 1f) return 0f
    val v = (1 - y) / (1 - hardness)
    return v * v * (3 - 2 * v)
}

/** A stroke's coverage by its dabs, in whole px. Each px takes the max of [flow]·[tip] over a visit — the dabs in a row that hold it — and visits build up by
SRC_OVER: a pass and its ends alike have flow·tip, full flow's core is exactly 1, a pass over px the stroke left before builds up. [solid] (hard, full flow) — Skia's
circles give the same, faster. A hard edge stays a pixel edge, a soft one is at least 1 px wide (anti-aliasing) */
class Dab(val hardness: Float, val flow: Float) {
    val solid = hardness >= 1f && flow >= 1f
    private val paint = tilePaint()
    private val lut = IntArray(TIP_LUT + 1) // flow·tip by squared distance
    private var lutR = 0f

    /** A [solid] dab onto [c] */
    fun draw(c: Canvas, x: Float, y: Float, r: Float) = c.drawCircle(x, y, r, paint)

    /** Adds the dab at ([x], [y]), radius [r] raster px, to the [bw]×[bh] block at ([bx], [by]) ([stride] B a row): [cur] — each px's visit going on, [acc] — its done ones.
    The dab before at ([px], [py]) (NaN — none) tells a visit going on from a new one. [out] (null — none) gets acc over cur where this dab reaches */
    fun visit(x: Float, y: Float, px: Float, py: Float, r: Float, bx: Int, by: Int, bw: Int, bh: Int, stride: Int, acc: ByteArray, cur: ByteArray, out: ByteArray?) =
        Kernels.visit(x, y, px, py, r, bx, by, bw, bh, stride, lut(r), acc, cur, out)

    /** Every dab of [xy] (× [s]) into a block as [visit]: [acc] gets the coverage, [cur] is scratch */
    fun visits(xy: FloatArray, s: Float, r: Float, bx: Int, by: Int, bw: Int, bh: Int, stride: Int, acc: ByteArray, cur: ByteArray) =
        Kernels.visits(xy, s, r, bx, by, bw, bh, stride, lut(r), acc, cur)

    private fun lut(r: Float): IntArray {
        if (r != lutR) {
            val edge = if (hardness >= 1f) 1f else minOf(hardness, (1 - 1 / r).coerceAtLeast(0f))
            for (i in lut.indices) lut[i] = (flow * tip(sqrt(i.toFloat() / TIP_LUT), edge) * 255).roundToInt()
            lutR = r
        }
        return lut
    }
}

/** Tile by tile: dabs (Dab: visits) or the figure into a draft (× soft selection edge), the draft onto the tile with [opacity]: the draft builds up to it, never past.
Clone — the layer's own px through the draft; healing — toward the healed px (Heal.kt); blur, filter — lerp toward the blurred, smudged (its coverage is hard) or filtered layer,
recolor, relight, tone — toward the layer (filtered) through them (recolor, relight alone: only tiles with px they change).
Painting, cloning, blurring → patch (a new tile starts as base × mask, the mask tile becomes cutOut); erasing → patch where it has a tile, else mask.
null — nothing reaches the layer; else the new mask and patch */
fun Stroke.bake(l: Layer, base: Bitmap?, mask: LayerMask?, patch: Tiles?, maskId: String, patchId: String): Pair<LayerMask?, Tiles?>? {
    if (opacity <= 0f || flow <= 0f) return null
    val w = l.fileW
    val h = l.fileH
    val cols = tilesAlong(w, 0)
    val rows = tilesAlong(h, 0)
    val touched = BooleanArray(cols * rows)
    for (i in 0 until xy.size / 2) {
        val x = xy[2 * i]
        val y = xy[2 * i + 1]
        if (x + r > 0f && y + r > 0f && x - r < w && y - r < h) touched.mark(cols, rows, x - r, y - r, x + r, y + r)
    }
    val toFile = l.canvasToFile()
    figure?.reach()?.let { toFile.mapRect(it); touched.mark(cols, rows, it.left, it.top, it.right, it.bottom) }
    val rects = area?.reach?.rects(toFile)
    if (rects != null) {
        val inside = BooleanArray(touched.size).apply { mark(cols, rows, rects) }
        for (k in touched.indices) touched[k] = touched[k] && inside[k]
    }
    val ink = color
    val from = clone
    val patched = patch?.levels?.get(0)
    val erased = mask?.levels?.get(0)
    val clip = rects?.let { Path().apply { for (k in it.indices step 4) addRect(it[k].toFloat(), it[k + 1].toFloat(), it[k + 2].toFloat(), it[k + 3].toFloat(), Path.Direction.CW) } }
    val hard = if (smudge != null) 1f else hardness
    val feather = area?.feather
    val keep = keepPaint()
    // Soft dabs go into a mask ms (2⁻ⁿ) px per file px, stretched: their edge falls off over MASK_EDGE..2·MASK_EDGE of its px
    val ms = run { var s = 1f; while ((1 - hard) * r * s / 2 >= MASK_EDGE) s /= 2; s }
    val stretch = tilePaint().apply { isFilterBitmap = true }

    /** One thread's drawing state (see parallel) */
    class Worker {
        private val dc = Canvas()
        private val cc = Canvas()
        private var coarse: Bitmap? = null
        private val mp = MaskPaints()
        private val dab = Dab(hard, flow)
        private val figurePaint = Paint().apply { isAntiAlias = true }
        private var scratch: Bitmap? = null
        private val acc by lazy(LazyThreadSafetyMode.NONE) { ByteArray(TILE * TILE) }
        private val cur by lazy(LazyThreadSafetyMode.NONE) { ByteArray(TILE * TILE) }
        private var block: Bitmap? = null
        val src = Canvas()
        val keepDraft = keepPaint()
        val draft = blankMaskTile()
        val raw by lazy(LazyThreadSafetyMode.NONE) { ByteArray(4 * TILE * TILE) }
        val joinedHere by lazy(LazyThreadSafetyMode.NONE) { ByteArray(TILE * TILE) } // Recolor's Joined.at

        /** The source the draft lets through */
        fun under() = scratch ?: patchTile(base).also { scratch = it }

        /** The layer before the stroke from file px ([x], [y]) */
        fun render(into: Bitmap, x: Int, y: Int) = src.composite(into, x, y, base, w, h, mask, patch, mp)

        /** Dabs reaching [left]..[right] × [top]..[bottom] of [c], [s] px per file px, moved by whole px */
        private fun dabsOn(c: Canvas, left: Int, top: Int, right: Int, bottom: Int, s: Float) {
            val rs = r * s
            for (i in 0 until xy.size / 2) {
                val x = xy[2 * i] * s
                val y = xy[2 * i + 1] * s
                if (x + rs > left && y + rs > top && x - rs < right && y - rs < bottom) dab.draw(c, x, y, rs)
            }
        }

        /** Dabs reaching [left]..[right] × [top]..[bottom] of [c] (ALPHA_8), as dabsOn; not solid ones — by visits, a TILE block at a time */
        private fun laid(c: Canvas, left: Int, top: Int, right: Int, bottom: Int, s: Float) {
            if (dab.solid) return dabsOn(c, left, top, right, bottom, s)
            val b = block ?: blankMaskTile().also { block = it }
            for (by in top until bottom step TILE) for (bx in left until right step TILE) {
                // Past the region the block stays clear: no dab is put there
                dab.visits(xy, s, r * s, bx, by, minOf(TILE, right - bx), minOf(TILE, bottom - by), TILE, acc, cur)
                b.copyPixelsFromBuffer(ByteBuffer.wrap(acc))
                c.drawBitmap(b, bx.toFloat(), by.toFloat(), null)
            }
        }

        /** Coverage into [into] (ALPHA_8) from file px ([x0], [y0]) */
        fun cover(into: Bitmap, x0: Int, y0: Int) {
            into.eraseColor(Color.TRANSPARENT)
            dc.setBitmap(into)
            dc.translate(-x0.toFloat(), -y0.toFloat())
            if (clip != null) dc.clipPath(clip) // whole-px rects: no AA needed
            if (ms == 1f) laid(dc, x0, y0, x0 + into.width, y0 + into.height, 1f)
            else {
                // 1 px around: the stretch has no seams between tiles
                val ox = floor(x0 * ms).toInt() - 1
                val oy = floor(y0 * ms).toInt() - 1
                val mw = ceil((x0 + into.width) * ms).toInt() + 1 - ox
                val mh = ceil((y0 + into.height) * ms).toInt() + 1 - oy
                val m = coarse?.takeIf { it.width == mw && it.height == mh } ?: createBitmap(mw, mh, Bitmap.Config.ALPHA_8).also { coarse?.recycle(); coarse = it }
                m.eraseColor(Color.TRANSPARENT)
                cc.setBitmap(m)
                cc.translate(-ox.toFloat(), -oy.toFloat())
                laid(cc, ox, oy, ox + mw, oy + mh, ms)
                dc.drawBitmap(m, null, RectF(ox / ms, oy / ms, (ox + mw) / ms, (oy + mh) / ms), stretch)
            }
            figure?.let { dc.save(); dc.clipRect(0, 0, w, h); dc.concat(toFile); it.draw(dc, figurePaint); dc.restore() }
            if (feather != null) { dc.concat(toFile); feather.draw(dc, keep) }
        }

        /** The dabs onto [c] by [paint] */
        fun dabs(c: Canvas, x0: Int, y0: Int, paint: Paint) {
            cover(draft, x0, y0)
            c.drawBitmap(draft, x0.toFloat(), y0.toFloat(), paint)
        }

        fun recycle() { coarse?.recycle(); scratch?.recycle(); draft.recycle(); block?.recycle() }
    }
    val workers = arrayOfNulls<Worker>(WORKERS)
    fun worker(i: Int) = workers[i] ?: Worker().also { workers[i] = it }
    val ks = minOf(1f, SMEAR_MAX / r)
    // The live smudge over this very layer isn't made again
    val smear = smeared?.takeIf { it.mask === mask && it.patch === patch }?.smear ?: smudge?.let { keeps ->
        val sc = Canvas()
        val sp = MaskPaints()
        Smear(ceil(w * ks).toInt(), ceil(h * ks).toInt(), r * ks, hardness, keeps, base, ks < 1f) { into, x, y -> sc.composite(into, x, y, base, w, h, mask, patch, sp, ks) }
            .also { s -> for (i in 0 until xy.size / 2) s.dab(xy[2 * i] * ks, xy[2 * i + 1] * ks) }
    }
    val filtered = if (blur > 0f) Blurred(w, h, blur, BLUR_EXACT) else filter?.invoke(w, h, toFile)

    return try {
        val first = worker(0)
        // A figure's reach may hold tiles it misses: only covered ones become patch tiles
        if (figure != null) {
            val probe = blankMaskTile()
            val blank = blankMaskTile()
            for (k in touched.indices) if (touched[k]) { first.cover(probe, k % cols * TILE, k / cols * TILE); touched[k] = !probe.sameAs(blank) }
            probe.recycle()
            blank.recycle()
        }
        // A tile kept as it was stays out of the patch: no RAM, file or history weight. Contiguous: only px joined to the seeds, over the selection's bounds;
        // Highlights, Shadows: by the surroundings within them
        val rendered = { wk: Int, x: Int, y: Int -> worker(wk).run { under().also { render(it, x, y) } } }
        val joined = recolor?.joined(area.fileRect(l), toFile, rendered)
        val around = relight?.takeIf { it.local }?.let { surroundings(area.fileRect(l), rendered) }
        if (tone == null && filtered == null && (recolor != null || relight != null)) {
            val at = touched.indices.filter { touched[it] }
            parallel(at.size) { wk, i ->
                val k = at[i]
                val x = k % cols * TILE
                val y = k / cols * TILE
                touched[k] = if (joined != null) joined.at(x, y, worker(wk).joinedHere)
                else rendered(wk, x, y).let { u -> recolor?.swap(u, null) ?: relight?.adjust(u, null, x, y, 1, around) == true }
            }
        }
        val toPatch = BooleanArray(touched.size) { touched[it] && (paints || patched?.get(it) != null) }
        val toMask = BooleanArray(touched.size) { touched[it] && base != null && patched?.get(it) == null }
        if (toPatch.none { it } && toMask.none { it }) return null
        val heal = if (from?.heal == true) (healed(w, h, rects, first::cover, first::render, base) ?: return null) else null
        // Tiles are made on all cores: the smear tiles they read are made first
        if (smear != null) for (k in toPatch.indices) if (toPatch[k]) smear.prepare(k % cols * TILE, k / cols * TILE, ks)
        val a = (opacity * 255).roundToInt()
        // onMask: e + (1 − e)·opacity·stroke
        val onPatch = Paint().apply { if (ink != null) color = ink; alpha = a; if (!paints) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val onMask = Paint().apply { alpha = a }
        // Blur, tone lerp: DST_OUT the draft, then ADD the source × draft
        val takeOut = Paint().apply { alpha = a; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val addIn = tilePaint().apply { alpha = a; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        val copy = tilePaint()
        val cut = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val p = if (toPatch.none { it }) patch else patch.edited(patchId, w, h, toPatch) { wk, old, x0, y0 ->
            val v = worker(wk)
            tileDrawn(old, {
                patchTile(base).also { t ->
                    if (base != null) Canvas(t).run {
                        drawBitmap(base, -x0.toFloat(), -y0.toFloat(), copy)
                        erased?.get(y0 / TILE * cols + x0 / TILE)?.let { drawBitmap(it, 0f, 0f, cut) }
                    }
                }
            }, x0, y0, w, h) {
                when {
                    heal != null -> Unit
                    from != null || filtered != null || smear != null || tone != null || recolor != null || relight != null -> {
                        val under = v.under()
                        when {
                            from != null -> v.render(under, x0 + from.dx, y0 + from.dy)
                            smear != null -> { v.render(under, x0, y0); smear.into(under, x0, y0, ks, v.raw) }
                            else -> {
                                if (filtered != null) filtered.into(under, x0, y0, v::render) else v.render(under, x0, y0)
                                recolor?.swap(under, under, joined?.let { j -> v.joinedHere.also { j.at(x0, y0, it) } })
                                relight?.adjust(under, under, x0, y0, 1, around)
                            }
                        }
                        // Skia's software canvas draws an ALPHA_8 bitmap as coverage (DST_IN keeps everything): the draft goes in as a shader over a rect
                        v.cover(v.draft, x0, y0)
                        v.keepDraft.shader = BitmapShader(v.draft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        v.src.setBitmap(under)
                        v.src.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), v.keepDraft)
                        if (from == null) it.drawBitmap(v.draft, x0.toFloat(), y0.toFloat(), takeOut)
                        if (tone != null) tone.add(it, { c -> c.drawBitmap(under, x0.toFloat(), y0.toFloat(), null) })
                        else it.drawBitmap(under, x0.toFloat(), y0.toFloat(), if (from == null) addIn else onPatch)
                    }
                    else -> v.dabs(it, x0, y0, onPatch)
                }
            }.also { heal?.into(it, x0, y0, a, v.raw) }
        }
        val m = if (toMask.none { it }) mask else mask.edited(maskId, w, h, toMask) { wk, old, x0, y0 ->
            if (paints) cutOut else tileDrawn(old, ::blankMaskTile, x0, y0, w, h) { worker(wk).dabs(it, x0, y0, onMask) }
        }
        m to p
    } finally { workers.forEach { it?.recycle() }; smear?.recycle() }
}

/** A [select] stroke joined to [base] on whole px: those whose centers its dabs hold. Off the main thread */
fun Stroke.selected(cw: Int, ch: Int, base: Selection?): Selection? {
    val combine = select ?: return base
    val b = RectF()
    for (i in 0 until xy.size / 2) b.union(xy[2 * i] - r, xy[2 * i + 1] - r, xy[2 * i] + r, xy[2 * i + 1] + r)
    val at = Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
    // Off the canvas: a new shape deselects, a joined one changes nothing
    if (!at.intersect(0, 0, cw, ch)) return if (combine == Region.Op.REPLACE) null else base
    val cover = createBitmap(at.width(), at.height(), Bitmap.Config.ALPHA_8)
    Canvas(cover).run {
        translate(-at.left.toFloat(), -at.top.toFloat())
        val p = Paint().apply { isAntiAlias = false }
        for (i in 0 until xy.size / 2) drawCircle(xy[2 * i], xy[2 * i + 1], r, p)
    }
    val drawn = cover.alphaRegion(at.left, at.top, 1)
    cover.recycle()
    return Region(base?.region ?: Region()).apply { op(drawn, combine) }.toSelection()
}
