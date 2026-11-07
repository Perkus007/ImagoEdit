package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
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
[area] — the selection at the start. [select] — Selection brush: canvas px, [layer] 0, joined to the selection. [figure] — Fill/Stroke: covers in place of dabs.
[blur] > 0 — Blur Area, σ file px; [smudge] — Blur Finger, Smear.keep */
class Stroke(val layer: Long, val file: String?, val color: Int?, val xy: FloatArray = FloatArray(0), val r: Float = 0f, val hardness: Float = 1f, val flow: Float = 1f,
    val spacing: Float = 2f, val opacity: Float = 1f, val area: Selection?, val clone: Clone? = null, val select: Region.Op? = null, val figure: Figure? = null,
    val blur: Float = 0f, val smudge: Float? = null)

/** Fill/Stroke side; name is persisted */
enum class StrokeSide { Outside, Center, Inside }

/** Fill/Stroke coverage, canvas px: [path] filled, or with a [side] its outline stroked [width] px on that side, corners [sharp] (mitered) or round */
class Figure(val path: Path, val side: StrokeSide? = null, val width: Float = 0f, val sharp: Boolean = true) {
    /** A side stroke is twice as wide, half clipped off by the outline */
    private fun styled(p: Paint) = p.apply {
        style = if (side == null) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = if (side == StrokeSide.Center) width else 2 * width
        strokeJoin = if (sharp) Paint.Join.MITER else Paint.Join.ROUND
        strokeMiter = MITER
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

/** The canvas rect layer [l] must cover for the figure to land whole: its own ∪ the figure's reach within canvas and selection; null — it already does */
fun Stroke.room(l: Layer, cw: Int, ch: Int): Rect? {
    val f = figure ?: return null
    val r = Rect().also { f.reach().roundOut(it) }
    val own = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    if (!r.intersect(0, 0, cw, ch) || area?.let { r.intersect(it.reach.bounds) } == false || own.contains(r)) return null
    return r.apply { union(own) }
}

private const val RINGS = 64 // стопы градиента даба

/** Half chord of ring [k] at offset [j], in ring widths */
private fun chord(k: Int, j: Int) = sqrt(((k + 1) * (k + 1) - j * j).toFloat()) - sqrt((k * k - j * j).toFloat())

/** A straight pass of dabs [spacing]·r apart leaves [flow] up to [hardness]·r, then linearly 0 at the edge; [spacing] ≥ 2 — a single dab. A hard dab isn't anti-aliased */
class Dab(val hardness: Float, val flow: Float, val spacing: Float) {
    private val paint = Paint().apply {
        isAntiAlias = hardness < 1f
        // At offset j a pass crosses rings ≥ j; their optical densities −ln(1 − alpha) add up
        val od = FloatArray(RINGS)
        val colors = IntArray(RINGS + 1)
        for (j in RINGS - 1 downTo 0) {
            val y = j.toFloat() / RINGS
            val t = flow * (if (y <= hardness) 1f else (1 - y) / (1 - hardness))
            val a = if (spacing >= 2f) t else {
                od[j] = if (t >= 1f) Float.POSITIVE_INFINITY else {
                    var d = -ln(1 - t) * spacing * RINGS / 2
                    for (k in j + 1 until RINGS) d -= od[k] * chord(k, j)
                    maxOf(0f, d / chord(j, j))
                }
                1 - exp(-od[j])
            }
            colors[j] = (a * 255).roundToInt() shl 24
        }
        colors[RINGS] = if (hardness < 1f) 0 else colors[RINGS - 1]
        if (colors.all { it == colors[0] }) alpha = colors[0] ushr 24 else shader = RadialGradient(0f, 0f, 1f, colors, null, Shader.TileMode.CLAMP)
    }

    fun draw(c: Canvas, x: Float, y: Float, r: Float) {
        c.save()
        c.translate(x, y)
        c.scale(r, r)
        c.drawCircle(0f, 0f, 1f, paint)
        c.restore()
    }
}

/** Tile by tile: dabs or the figure into a draft (× soft selection edge), the draft onto the tile with [opacity]: overlapping dabs build up to it, never past.
Clone — the layer's own px through the draft; healing — toward the healed px (Heal.kt); blur — lerp toward the blurred or smudged layer (smudge coverage is hard).
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
    val paints = ink != null || from != null || blur > 0f || smudge != null
    val patched = patch?.levels?.get(0)
    val erased = mask?.levels?.get(0)
    val clip = rects?.let { Path().apply { for (k in it.indices step 4) addRect(it[k].toFloat(), it[k + 1].toFloat(), it[k + 2].toFloat(), it[k + 3].toFloat(), Path.Direction.CW) } }
    val dc = Canvas()
    val dab = Dab(if (smudge != null) 1f else hardness, flow, spacing)
    val feather = area?.feather
    val keep = keepPaint()
    val figurePaint = Paint().apply { isAntiAlias = true }
    val src = Canvas()
    val mp = MaskPaints()

    /** Coverage into [into] (ALPHA_8) from file px ([x0], [y0]) */
    fun cover(into: Bitmap, x0: Int, y0: Int) {
        into.eraseColor(Color.TRANSPARENT)
        dc.setBitmap(into)
        dc.translate(-x0.toFloat(), -y0.toFloat())
        if (clip != null) dc.clipPath(clip) // whole-px rects: no AA needed
        val x1 = x0 + into.width
        val y1 = y0 + into.height
        for (i in 0 until xy.size / 2) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            if (x + r > x0 && y + r > y0 && x - r < x1 && y - r < y1) dab.draw(dc, x, y, r)
        }
        figure?.let { dc.save(); dc.clipRect(0, 0, w, h); dc.concat(toFile); it.draw(dc, figurePaint); dc.restore() }
        if (feather != null) { dc.concat(toFile); feather.draw(dc, keep) }
    }

    // A figure's reach may hold tiles it misses: only covered ones become patch tiles
    if (figure != null) {
        val probe = blankMaskTile()
        val blank = blankMaskTile()
        for (k in touched.indices) if (touched[k]) { cover(probe, k % cols * TILE, k / cols * TILE); touched[k] = !probe.sameAs(blank) }
        probe.recycle()
        blank.recycle()
    }
    val toPatch = BooleanArray(touched.size) { touched[it] && (paints || patched?.get(it) != null) }
    val toMask = BooleanArray(touched.size) { touched[it] && base != null && patched?.get(it) == null }
    if (toPatch.none { it } && toMask.none { it }) return null

    /** The layer before the stroke from file px ([x], [y]) */
    fun render(into: Bitmap, x: Int, y: Int) = src.composite(into, x, y, base, w, h, mask, patch, mp)

    val heal = if (from?.heal == true) (healed(w, h, rects, ::cover, ::render, base) ?: return null) else null
    val ks = minOf(1f, SMEAR_MAX / r)
    val smear = smudge?.let { keeps ->
        Smear(ceil(w * ks).toInt(), ceil(h * ks).toInt(), r * ks, hardness, keeps, base, ks < 1f) { into, x, y -> src.composite(into, x, y, base, w, h, mask, patch, mp, ks) }
            .also { s -> for (i in 0 until xy.size / 2) s.dab(xy[2 * i] * ks, xy[2 * i + 1] * ks) }
    }
    val draft = blankMaskTile()
    val under = if (heal == null && (from != null || blur > 0f || smear != null)) patchTile(base) else null
    val a = (opacity * 255).roundToInt()
    // onMask: e + (1 − e)·opacity·stroke
    val onPatch = Paint().apply { if (ink != null) color = ink; alpha = a; if (!paints) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val onMask = Paint().apply { alpha = a }
    // Blur lerp: DST_OUT the draft, then ADD the source × draft
    val takeOut = Paint().apply { alpha = a; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val addIn = tilePaint().apply { alpha = a; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    val copy = tilePaint()
    val cut = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val keepDraft = keepPaint()

    fun Canvas.dabs(x0: Int, y0: Int, paint: Paint) {
        cover(draft, x0, y0)
        drawBitmap(draft, x0.toFloat(), y0.toFloat(), paint)
    }

    return try {
        val p = if (toPatch.none { it }) patch else patch.edited(patchId, w, h, toPatch) { old, x0, y0 ->
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
                    under != null -> {
                        when {
                            from != null -> render(under, x0 + from.dx, y0 + from.dy)
                            smear != null -> { render(under, x0, y0); smear.into(under, x0, y0, ks) }
                            else -> blurredTile(under, x0, y0, w, h, blur, BLUR_EXACT, ::render)
                        }
                        // Skia's software canvas draws an ALPHA_8 bitmap as coverage (DST_IN keeps everything): the draft goes in as a shader over a rect
                        cover(draft, x0, y0)
                        keepDraft.shader = BitmapShader(draft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        src.setBitmap(under)
                        src.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), keepDraft)
                        if (from == null) it.drawBitmap(draft, x0.toFloat(), y0.toFloat(), takeOut)
                        it.drawBitmap(under, x0.toFloat(), y0.toFloat(), if (from == null) addIn else onPatch)
                    }
                    else -> it.dabs(x0, y0, onPatch)
                }
            }.also { heal?.into(it, x0, y0, a) }
        }
        val m = if (toMask.none { it }) mask else mask.edited(maskId, w, h, toMask) { old, x0, y0 ->
            if (paints) cutOut else tileDrawn(old, ::blankMaskTile, x0, y0, w, h) { it.dabs(x0, y0, onMask) }
        }
        m to p
    } finally { draft.recycle(); under?.recycle(); smear?.recycle() }
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
