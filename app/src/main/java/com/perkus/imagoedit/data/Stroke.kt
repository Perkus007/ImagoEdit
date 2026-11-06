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

const val PATCH = "patch" // расширение файла патча слоя: тайлы нарисованного поверх его пикселей

/** Of [base]'s kind and color space; no base — sRGB */
fun patchTile(base: Bitmap?) = base?.blankLike(TILE, TILE) ?: createBitmap(TILE, TILE)

/** The mask's tile under every patch tile: the pixels there are erased whole, the patch replaces them. One immutable shared instance */
private val cutOut: Bitmap by lazy { blankMaskTile().apply { eraseColor(Color.BLACK) }.copy(Bitmap.Config.ALPHA_8, false) ?: throw OutOfMemoryError() }

/** A clone stroke's source: the layer's own pixels [dx], [dy] file px off, as they were before it (whole px: an exact copy); [heal] — only their texture, tone and color
matched to the surroundings (healed) */
class Clone(val dx: Int, val dy: Int, val heal: Boolean)

/** [layer] — Layer.id, [file] — its pixels at the start (null — none); [color] null — erases, unless [clone]. [xy] — dab centers x, y in turn, file px (Layer.paintable);
[r] — file px; [hardness], [flow], [opacity] 0..1, [spacing] — as Dab's; [area] — the selection at the start, null — anywhere.
[select] — the Selection tool's brush: canvas px, [layer] 0 (no layer's), joined to the selection so (see selected), not baked into a layer.
[figure] — the Fill/Stroke tool's: it covers in place of dabs (none then) */
class Stroke(val layer: Long, val file: String?, val color: Int?, val xy: FloatArray = FloatArray(0), val r: Float = 0f, val hardness: Float = 1f, val flow: Float = 1f,
    val spacing: Float = 2f, val opacity: Float = 1f, val area: Selection?, val clone: Clone? = null, val select: Region.Op? = null, val figure: Figure? = null)

/** Where a stroke lies against its outline (the Fill/Stroke tool). name is persisted */
enum class StrokeSide { Outside, Center, Inside }

/** Coverage in place of dabs (the Fill/Stroke tool), canvas px: [path] filled; with a [side] — its outline stroked [width] px wide on that side, corners [sharp] (mitered, as
Selection.grown keeps a drawn shape's) or round */
class Figure(val path: Path, val side: StrokeSide? = null, val width: Float = 0f, val sharp: Boolean = true) {
    /** The canvas px it may cover: a mitered corner reaches at most MITER half-widths out */
    @Suppress("DEPRECATION") // computeBounds(RectF) is API 34+
    fun reach() = RectF().also { path.computeBounds(it, true); if (side != null && side != StrokeSide.Inside) it.inset(-width * MITER, -width * MITER) }

    /** Onto [c] by [p]: its style and stroke are set here. A side stroke is twice as wide, half of it clipped off by the outline */
    fun draw(c: Canvas, p: Paint) {
        p.style = if (side == null) Paint.Style.FILL else Paint.Style.STROKE
        if (side == null) return c.drawPath(path, p)
        p.strokeWidth = if (side == StrokeSide.Center) width else 2 * width
        p.strokeJoin = if (sharp) Paint.Join.MITER else Paint.Join.ROUND
        p.strokeMiter = MITER
        c.save()
        if (side == StrokeSide.Inside) c.clipPath(path) else if (side == StrokeSide.Outside) c.clipOutPath(path)
        c.drawPath(path, p)
        c.restore()
    }
}

private const val RINGS = 64 // кольца профиля даба от центра к краю: стопы его градиента

/** Half the chord of ring [k] at offset [j] from the center, in ring widths */
private fun chord(k: Int, j: Int) = sqrt(((k + 1) * (k + 1) - j * j).toFloat()) - sqrt((k * k - j * j).toFloat())

/** A straight pass of dabs [spacing]·r apart leaves across itself [flow] up to [hardness]·r, then linearly to 0 at the edge, as BrushSettings' preview; [spacing] ≥ 2 (no overlap: a tap) — one dab.
A unit circle under a scale: the shader is built once. A hard one isn't anti-aliased: a pixel edge at any zoom */
class Dab(val hardness: Float, val flow: Float, val spacing: Float) {
    private val paint = Paint().apply {
        isAntiAlias = hardness < 1f
        // Rings from the edge in: at offset j a pass crosses rings ≥ j along their chords, their optical densities −ln(1 − alpha) add up
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

/** [l] — as Layer.paintable. Tile by tile: dabs or the figure into a draft (× a soft selection edge's coverage), the draft onto the tile with [opacity] → overlapping dabs build up to it, never past.
A clone puts the layer's own pixels through the draft; a healing one takes the tile toward its healed pixels (Heal.kt) by the coverage.
Painting and cloning go into the patch: a new patch tile starts as base × mask, the mask's tile there becomes cutOut. Erasing goes into the patch where it has a tile, else into the mask (pixels only).
null — nothing reaches the layer; else its mask and patch after the stroke (unchanged ones as they were) */
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
    val paints = ink != null || from != null
    val patched = patch?.levels?.get(0)
    val erased = mask?.levels?.get(0)
    val clip = rects?.let { Path().apply { for (k in it.indices step 4) addRect(it[k].toFloat(), it[k + 1].toFloat(), it[k + 2].toFloat(), it[k + 3].toFloat(), Path.Direction.CW) } }
    val dc = Canvas()
    val dab = Dab(hardness, flow, spacing)
    val feather = area?.feather
    val keep = keepPaint()
    val figurePaint = Paint().apply { isAntiAlias = true }
    val src = Canvas()
    val mp = MaskPaints()

    /** The stroke's coverage over [into] (ALPHA_8) from file px ([x0], [y0]) */
    fun cover(into: Bitmap, x0: Int, y0: Int) {
        into.eraseColor(Color.TRANSPARENT)
        dc.setBitmap(into)
        dc.translate(-x0.toFloat(), -y0.toFloat())
        if (clip != null) dc.clipPath(clip) // whole-px rects: each pixel fully in or out
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

    // A figure's reach may hold tiles it misses (a frame's middle): only the ones it covers become patch tiles
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

    /** The layer as it is (before the stroke) from file px ([x], [y]) */
    fun render(into: Bitmap, x: Int, y: Int) = src.composite(into, x, y, base, w, h, mask, patch, mp)

    val heal = if (from?.heal == true) (healed(w, h, rects, ::cover, ::render, base) ?: return null) else null
    val draft = blankMaskTile()
    val under = if (from != null && heal == null) patchTile(base) else null // the source under a tile
    val a = (opacity * 255).roundToInt()
    // onMask adds to the erased amount: e + (1 − e)·opacity·stroke
    val onPatch = Paint().apply { if (ink != null) color = ink; alpha = a; if (!paints) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    val onMask = Paint().apply { alpha = a }
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
                        drawBitmap(base, -x0.toFloat(), -y0.toFloat(), copy) // whole px: an exact copy
                        erased?.get(y0 / TILE * cols + x0 / TILE)?.let { drawBitmap(it, 0f, 0f, cut) }
                    }
                }
            }, x0, y0, w, h) {
                when {
                    heal != null -> Unit
                    under != null && from != null -> {
                        // The source × the draft, then onto the tile as a whole bitmap. Skia's software canvas places a paint's shader on an ALPHA_8 bitmap by the bitmap,
                        // not the canvas, and draws it as coverage, under which DST_IN keeps everything: the draft goes in as a shader over a rect (as Feather.draw)
                        render(under, x0 + from.dx, y0 + from.dy)
                        cover(draft, x0, y0)
                        keepDraft.shader = BitmapShader(draft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        src.setBitmap(under)
                        src.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), keepDraft)
                        it.drawBitmap(under, x0.toFloat(), y0.toFloat(), onPatch)
                    }
                    else -> it.dabs(x0, y0, onPatch)
                }
            }.also { heal?.into(it, x0, y0, a) }
        }
        val m = if (toMask.none { it }) mask else mask.edited(maskId, w, h, toMask) { old, x0, y0 ->
            if (paints) cutOut else tileDrawn(old, ::blankMaskTile, x0, y0, w, h) { it.dabs(x0, y0, onMask) }
        }
        m to p
    } finally { draft.recycle(); under?.recycle() }
}

/** A [select] stroke joined to [base] (null — none) over the [cw]×[ch] canvas, on whole px as the other selection styles: the px whose centers its dabs hold. Off the main thread */
fun Stroke.selected(cw: Int, ch: Int, base: Selection?): Selection? {
    val combine = select ?: return base
    val b = RectF()
    for (i in 0 until xy.size / 2) b.union(xy[2 * i] - r, xy[2 * i + 1] - r, xy[2 * i] + r, xy[2 * i + 1] + r)
    val at = Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
    // Off the canvas: as with the other styles, a new shape deselects, a joined one changes nothing
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
