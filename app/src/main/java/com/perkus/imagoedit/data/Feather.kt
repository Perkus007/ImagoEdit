package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val MAX_FEATHER = 1000 // наибольший радиус размытия края и сглаживания контура выделения, px холста
private const val BLUR_SIGMA = 0.57735f // радиус BlurMaskFilter → σ гаусса: σ = 0.57735·радиус + 0.5, так его переводит платформа
private const val HALF = 128 // сглаженный контур — там, где размытие выделяет не меньше половины (из 255)
private const val SIGMA_PX = 64f // σ размытия в px битмапа, где оно считается, не больше: шире — в уменьшенной копии (радиус до ~110 px — в полном размере)
internal const val MITER = 4f // расширенная нарисованная фигура и обводка: угол острее ~29° срезается, а не тянется в длинный шип

/** A selection's soft edge (Selection transform's blur): its coverage [alpha] (ALPHA_8, 255 — fully selected) with its top-left at canvas px ([x], [y]), nothing past it.
[reach] — the canvas px it selects at all, [core] — fully; [radius] — the blur it was made by, canvas px (see grownEdge). Immutable: moves of the selection and history share the alpha */
class Feather(val alpha: Bitmap, val x: Int, val y: Int, val reach: Region, val core: Region, val radius: Int) {
    val bounds get() = Rect(x, y, x + alpha.width, y + alpha.height)

    /** Its coverage onto [c] (canvas px) by [paint]'s blend mode, as alpha. Skia's software canvas draws an ALPHA_8 bitmap as coverage, under which DST_IN keeps everything:
    there a shader carries it */
    fun draw(c: Canvas, paint: Paint) {
        if (c.isHardwareAccelerated) return c.drawBitmap(alpha, x.toFloat(), y.toFloat(), paint)
        paint.shader = BitmapShader(alpha, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix().apply { setTranslate(x.toFloat(), y.toFloat()) }) }
        c.drawRect(bounds, paint)
        paint.shader = null
    }
}

/** For Feather.draw: what's under it stays in proportion to the coverage */
fun keepPaint() = Paint().apply { isAntiAlias = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

/** The canvas rect a blur by [radius] px of an outline within [bounds] (canvas px) covers: the bounds and 3σ around */
private fun blurArea(bounds: Rect, radius: Int) = Rect(bounds).apply { val m = ceil(3 * (BLUR_SIGMA * radius + 0.5f)).toInt() + 1; inset(-m, -m) }

/** The scale of a copy of [area] at most [pixels] px: 1 — full size */
private fun copyScale(area: Rect, pixels: Int) = minOf(1f, sqrt(pixels / (area.width().toFloat() * area.height())))

/** A new ALPHA_8 for [area] scaled by [k] */
private fun maskOver(area: Rect, k: Float) = createBitmap((area.width() * k).roundToInt().coerceAtLeast(1), (area.height() * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ALPHA_8)

/** Draws onto [this], laid over [area], in canvas px */
private fun Bitmap.canvasOver(area: Rect) = Canvas(this).also { it.scale(width / area.width().toFloat(), height / area.height().toFloat()); it.translate(-area.left.toFloat(), -area.top.toFloat()) }

/** [this] laid over [area]: its px → canvas px */
fun Bitmap.toCanvas(area: Rect) = Matrix().apply { setScale(area.width() / width.toFloat(), area.height() / height.toFloat()); postTranslate(area.left.toFloat(), area.top.toFloat()) }

/** [this] (canvas px) filled into a new ALPHA_8 laid over [area] (canvas px), at most [pixels] px (smaller: the blur scales with it), the edge blurred by [radius] > 0 canvas px
both ways — BlurMaskFilter's Gaussian. A σ over SIGMA_PX of its px is blurred in a smaller copy, then stretched: Skia caps that σ at 128 px, and a smooth blur gains nothing from more px */
private fun Path.blurMask(radius: Int, area: Rect, pixels: Int = Int.MAX_VALUE): Bitmap {
    val fit = copyScale(area, pixels)
    val k = minOf(fit, SIGMA_PX / (BLUR_SIGMA * radius + 0.5f))
    val into = maskOver(area, k)
    into.canvasOver(area).drawPath(this, Paint().apply { isAntiAlias = k < 1f; maskFilter = BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL) })
    val w = (area.width() * fit).roundToInt().coerceAtLeast(1)
    val h = (area.height() * fit).roundToInt().coerceAtLeast(1)
    return if (into.width == w && into.height == h) into else into.scale(w, h)
}

/** [this] (canvas px) filled hard into a new ALPHA_8 laid over [area] (canvas px), at most [pixels] px (smaller: scaled down): the px whose centers it holds */
private fun Path.fillMask(area: Rect, pixels: Int) = maskOver(area, copyScale(area, pixels)).also { it.canvasOver(area).drawPath(this, Paint().apply { isAntiAlias = false }) }

/** Where the blur [this] (ALPHA_8, blurMask's), its top-left at ([x], [y]) on its own px grid, selects half: the outline smoothed — corners rounded, details and gaps narrower
than the blur gone; nowhere → null. Off the main thread: scans it */
private fun Bitmap.smoothedRegion(x: Int, y: Int) = alphaRegion(x, y, HALF).takeUnless { it.isEmpty }

/** The outline smoothed by [radius] px (smoothedRegion), the edge hard; nothing left → as it was. Off the main thread */
fun Selection.smoothed(radius: Int): Selection {
    val a = region.bounds // the half lies within the outline's convex hull
    return outline.blurMask(radius, a).smoothedRegion(a.left, a.top)?.let { Selection(it) } ?: this
}

/** [this] (canvas px) smoothed by [radius] px as Selection.smoothed smooths an outline, on a copy at most [pixels] px, on its whole px; nothing left → null. For a preview, off the main thread */
fun Path.smoothedOutline(radius: Int, pixels: Int): Path? {
    val a = pxBounds().takeUnless { it.isEmpty } ?: return null
    val b = blurMask(radius, a, pixels)
    val toCanvas = b.toCanvas(a)
    return b.smoothedRegion(0, 0).also { b.recycle() }?.boundaryPath?.apply { transform(toCanvas) }
}

/** The edge blurred by [radius] px both ways (blurMask), the outline kept. Off the main thread */
fun Selection.blurred(radius: Int): Selection {
    val a = blurArea(region.bounds, radius)
    return outline.blurMask(radius, a).toSoftSelection(a.left, a.top, region, radius, outline, drawn) ?: this
}

/** [this]'s (canvas px) edge blurred by [radius] px as Selection.blurred blurs it, on a copy at most [pixels] px: the coverage and the canvas rect it lies over. For a preview, off the main thread */
fun Path.blurredEdge(radius: Int, pixels: Int) = blurArea(pxBounds(), radius).let { blurMask(radius, it, pixels) to it }

/** Selection transform's edge: the outline smoothed by [smooth] px, then the edge blurred by [blur] px; 0 — neither. Off the main thread */
fun Selection.feathered(blur: Int, smooth: Int) = (if (smooth > 0) smoothed(smooth) else this).let { if (blur > 0) it.blurred(blur) else it }

/** [this] [op] [other] as a new path; Skia failed → null */
fun Path.opped(other: Path, op: Path.Op) = Path(this).takeIf { it.op(other, op) }

/** Within [r] (canvas px); Skia failed → null */
fun Path.clipped(r: Rect) = opped(r.asPath(), Path.Op.INTERSECT)

/** The shape [this] (canvas px) moved out by [r] px along its normals (in for r < 0): curves offset, corners kept — mitered, sharper than MITER allows beveled. Skia failed → null */
private fun Path.outset(r: Int): Path? {
    if (r == 0) return this
    val band = Path().also { Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f * abs(r); strokeJoin = Paint.Join.MITER; strokeMiter = MITER }.getFillPath(this, it) }
    return opped(band, if (r > 0) Path.Op.UNION else Path.Op.DIFFERENCE)
}

/** The whole canvas px around it */
@Suppress("DEPRECATION") // computeBounds(RectF) is API 34+
private fun Path.pxBounds() = RectF().also { computeBounds(it, true) }.let { Rect(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }

/** The px whose centers [this] (canvas px) holds. Off the main thread: filled at full size over its bounds */
private fun Path.pxRegion(): Region {
    val b = pxBounds().takeUnless { it.isEmpty } ?: return Region()
    val m = fillMask(b, Int.MAX_VALUE)
    return m.alphaRegion(b.left, b.top, 1).also { m.recycle() }
}

/** Where its outline grown by [r] px (Selection.grown) lies: a drawn shape's bounds grown alike; else its bounds grown by r, shrunk by less if they'd vanish — the center stays */
fun Selection.grownBounds(r: Int): Rect = drawn?.takeIf { r != 0 }?.outset(r)?.takeUnless { it.isEmpty }?.pxBounds()
    ?: Rect(region.bounds).apply { inset(-maxOf(r, -(width() - 1) / 2), -maxOf(r, -(height() - 1) / 2)) }

/** Where its outline grown by [r] px with corners rounded lies */
private fun Selection.growArea(r: Int) = Rect(region.bounds).apply { if (r > 0) inset(-r, -r) }

/** Its outline moved out by [r] px (in for r < 0) as grown moves it, canvas px: a drawn shape's exactly, else on a copy at most [pixels] px, on its whole px; nothing left → empty.
For a preview, off the main thread */
fun Selection.grownOutline(r: Int, pixels: Int): Path {
    drawn?.outset(r)?.let { return it }
    val a = growArea(r)
    val m = outline.fillMask(a, pixels)
    val toCanvas = m.toCanvas(a)
    val g = m.grownRegion(0, 0, r * (m.width / a.width().toFloat()))
    m.recycle()
    return g.boundaryPath.apply { transform(toCanvas) }
}

/** Its soft edge (none → null) moved along with its outline grown by [r] px to [to] (canvas px): the ring between the outlines blurred as the edge was (blurMask) added to its coverage,
for r < 0 taken from it — for a blurred outline that is the grown one blurred, for any other coverage the same change. On a copy at most [pixels] px: the coverage and the canvas rect
it lies over. Off the main thread */
fun Selection.grownEdge(to: Path, r: Int, pixels: Int): Pair<Bitmap, Rect>? {
    val f = feather ?: return null
    val a = blurArea(to.pxBounds().apply { union(region.bounds) }, f.radius).apply { union(f.bounds) }
    val ring = Path(outline).apply { addPath(to); fillType = Path.FillType.EVEN_ODD }.blurMask(f.radius, a, pixels)
    val out = createBitmap(ring.width, ring.height, Bitmap.Config.ALPHA_8)
    out.canvasOver(a).drawBitmap(f.alpha, f.x.toFloat(), f.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
    val sign = if (r > 0) 1 else -1
    val p = IntArray(out.width)
    val q = IntArray(out.width)
    for (y in 0 until out.height) {
        out.getPixels(p, 0, out.width, 0, y, out.width, 1)
        ring.getPixels(q, 0, out.width, 0, y, out.width, 1)
        for (x in p.indices) p[x] = ((p[x] ushr 24) + sign * (q[x] ushr 24)).coerceIn(0, 255) shl 24
        out.setPixels(p, 0, out.width, 0, y, out.width, 1)
    }
    ring.recycle()
    return out to a
}

/** Its outline moved out by [r] px (in for r < 0) — a drawn shape along its normals, corners kept (outset), else px by px, corners rounded (grownRegion) — a soft edge along with it
(grownEdge); 0 — as it is, nothing left → null. Off the main thread */
fun Selection.grown(r: Int): Selection? {
    if (r == 0) return this
    val v = drawn?.outset(r)
    val g = (v?.pxRegion() ?: growArea(r).let { a -> outline.fillMask(a, Int.MAX_VALUE).let { m -> m.grownRegion(a.left, a.top, r.toFloat()).also { m.recycle() } } }).toSelection(v)
    if (g == null || feather == null) return g
    val (alpha, at) = grownEdge(g.outline, r, Int.MAX_VALUE) ?: return g
    return alpha.toSoftSelection(at.left, at.top, g.region, feather.radius, g.outline, v) ?: g
}

/** Placed by [t] as the frame [src] (canvas px) gets it, on the [cw]×[ch] canvas: the px whose centers it covers (ContentTransform.carry) — a drawn shape's as it's carried, not its
staircase — a soft edge's coverage carried as a layer's pixels are (ContentTransform.render), the blur it was made by scaled along; nothing on the canvas → null. Off the main thread */
fun Selection.transformed(t: ContentTransform, src: Rect, cw: Int, ch: Int): Selection? {
    val box = Layer(x = src.left, y = src.top, w = src.width(), h = src.height()) // the frame's source as a layer lying over it
    val canvas = Rect(0, 0, cw, ch)
    val d = drawn?.let { Path(it).apply { transform(t.carrier(box)) }.clipped(canvas) }
    val r = (d?.pxRegion() ?: t.carry(region, box)).apply { op(canvas, Region.Op.INTERSECT) }
    if (r.isEmpty) return null
    val f = feather ?: return Selection(r, drawn = d)
    val ft = t.framing(src, f.bounds)
    val dst = ft.layerRect(cw, ch, f.alpha.width, f.alpha.height) ?: return Selection(r, drawn = d)
    val k = sqrt(t.w * t.h / (src.width().toFloat() * src.height()))
    return ft.render(f.alpha, dst, 0).toSoftSelection(dst.left, dst.top, r, (f.radius * k).roundToInt().coerceAtLeast(1), drawn = d) ?: Selection(r, drawn = d)
}

/** The selection the coverage [this] (ALPHA_8) lying at canvas px ([x], [y]) makes, blurred by [radius], outlined by [region] (empty — where it selects anything) as [outline] if given,
[drawn] as Selection's; selecting nothing → null. Off the main thread: scans it */
private fun Bitmap.toSoftSelection(x: Int, y: Int, region: Region, radius: Int, outline: Path? = null, drawn: Path? = null): Selection? {
    val reach = alphaRegion(x, y, 1)
    if (reach.isEmpty) return null
    val r = region.takeUnless { it.isEmpty } ?: Region(reach)
    return Selection(r, outline ?: r.boundaryPath, Feather(this, x, y, reach, alphaRegion(x, y, 255), radius), drawn)
}

/** Shifted by whole px, then clipped to [within] (canvas px) if given; nothing left → null. A soft edge keeps its coverage; the outline is rebuilt only when clipped */
fun Selection.moved(dx: Int, dy: Int, within: Rect? = null): Selection? {
    fun Region.shifted() = Region(this).apply { translate(dx, dy); if (within != null) op(within, Region.Op.INTERSECT) }
    val r = region.shifted().takeUnless { it.isEmpty } ?: return null
    val f = feather?.let { Feather(it.alpha, it.x + dx, it.y + dy, it.reach.shifted(), it.core.shifted(), it.radius) }
    val d = drawn?.let { Path(it).apply { offset(dx.toFloat(), dy.toFloat()) } }?.let { if (within != null) it.clipped(within) else it }
    return Selection(r, if (within == null) Path(outline).apply { offset(dx.toFloat(), dy.toFloat()) } else r.boundaryPath, f, d)
}

/** The rest of [within] (canvas px); a soft edge's coverage inverted over it. Off the main thread */
fun Selection.inverted(within: Rect): Selection? {
    val rest = Region(within).apply { op(region, Region.Op.DIFFERENCE) }
    val d = drawn?.let { within.asPath().opped(it, Path.Op.DIFFERENCE) }
    val f = feather ?: return rest.toSelection(d)
    val inv = createBitmap(within.width(), within.height(), Bitmap.Config.ALPHA_8).apply { eraseColor(Color.BLACK) }
    Canvas(inv).run {
        translate(-within.left.toFloat(), -within.top.toFloat())
        f.draw(this, Paint().apply { isAntiAlias = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
    }
    return inv.toSoftSelection(within.left, within.top, rest, f.radius, drawn = d)
}
