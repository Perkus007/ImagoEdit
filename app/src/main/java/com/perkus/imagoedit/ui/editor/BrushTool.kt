package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Region
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.perkus.imagoedit.data.BLUR_EXACT
import com.perkus.imagoedit.data.Blurred
import com.perkus.imagoedit.data.Clone
import com.perkus.imagoedit.data.Dab
import com.perkus.imagoedit.data.EdgePx
import com.perkus.imagoedit.data.Edges
import com.perkus.imagoedit.data.Feather
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MaskOverlay
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.SMEAR_MAX
import com.perkus.imagoedit.data.SMEAR_STEP
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Smear
import com.perkus.imagoedit.data.Smeared
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.canvasToFile
import com.perkus.imagoedit.data.drawMasked
import com.perkus.imagoedit.data.drawTiles
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.keepPaint
import com.perkus.imagoedit.data.patchTile
import com.perkus.imagoedit.data.renderer
import com.perkus.imagoedit.data.saveClipLayer
import com.perkus.imagoedit.data.tilePaint
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val SPACING = 0.05f // доля диаметра
private const val MIN_STEP = 0.5f // px файла
private const val MAX_PIECES = 32
private val RING_W = 1.dp
private val MARK_ARM = 8.dp
private val MARK_W = 2.dp
private const val MAX_BLUR = 20f // σ «Области» при Интенсивности 100%, dp; по квадрату
private const val LIVE_SIGMA = 2f // наименьший σ уменьшенной копии живой «Области», px
private const val LIVE_GRID = 4f // px экрана на px этой копии: крупнее после растяжения видна гранями — σ копии растёт до BLUR_EXACT
private const val SMEAR_PUSH = 1f // сдвиг пикселей «Пальцем» при Интенсивности 100%, диаметров; по квадрату

// DIFFERENCE: visible on any color
private val ringPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE }
private val ringPath = Path()

/** An inverting cross at ([x], [y]), screen px: the Stamp's source mark, the cursor's. One path: DIFFERENCE isn't applied twice where the lines cross */
fun DrawScope.drawCross(x: Float, y: Float) {
    val a = MARK_ARM.toPx()
    ringPath.rewind()
    ringPath.moveTo(x - a, y)
    ringPath.lineTo(x + a, y)
    ringPath.moveTo(x, y - a)
    ringPath.lineTo(x, y + a)
    ringPaint.strokeWidth = MARK_W.toPx()
    drawContext.canvas.nativeCanvas.drawPath(ringPath, ringPaint)
}

/** Blur stroke's live result on its Live's tile grid ([cols] wide, [n] tiles) of the layer [render] draws: Area — [area] (σ [sigma] file px), Finger — [smear];
[smeared] — it for the bake, if at the bake's raster */
private class LiveBlur(val sigma: Float, val area: Blurred?, val smear: Smear?, val render: (Bitmap, Int, Int) -> Unit, private val like: Bitmap?, private val cols: Int, n: Int,
    val smeared: Smeared?) {
    val px = arrayOfNulls<Bitmap>(n)

    fun tile(k: Int) = px[k] ?: patchTile(like).also { px[k] = it }

    fun upload() {
        val s = smear ?: return
        for (k in px.indices) if (s.dirty[k]) s.into(tile(k), k % cols * TILE, k / cols * TILE)
    }

    fun recycle() { smear?.recycle() }
}

/** A live tile's visits (Dab.visit): [out] — the coverage its bitmap gets */
private class Visited {
    val acc = ByteArray(TILE * TILE)
    val cur = ByteArray(TILE * TILE)
    val out = ByteArray(TILE * TILE)
}

/** A stroke's live ALPHA_8 coverage in layer [on]'s file px (as aimed), [k] tile px per file px, tile 0 at tile px ([ox], [oy]): the grid covers the canvas, past the layer too;
[color] null — erases, unless [clone] or [blur]; [clip], [feather] — the selection, file px; [toFile] — canvas px → file px; [select] — the Selection brush's.
[edges] — cuts the dabs by layer [from]'s px: [on]'s, the Selection brush's — the active one's. Edge-aware or not solid Dab's [tiles] come from their [visits] */
private class Live(val on: Layer, val from: Long, val color: Int?, val clone: Clone?, val k: Float, val ox: Int, val oy: Int, val cols: Int, val rows: Int, val opacity: Float,
    val clip: Path?, val feather: Feather?, val toFile: Matrix, val select: Region.Op?, val blur: LiveBlur?, val edges: Edges?) {
    val tiles = arrayOfNulls<Bitmap>(cols * rows)
    val visits = arrayOfNulls<Visited>(cols * rows)
    private val stale = BooleanArray(cols * rows) // visits not in their tiles yet: put once a frame, not a dab
    val bounds = RectF() // the dabs' reach, file px

    fun visited(k: Int) { stale[k] = true }

    /** [tiles] get their [visits]. Draw phase */
    fun upload() {
        for (k in stale.indices) if (stale[k]) {
            stale[k] = false
            tiles[k]?.copyPixelsFromBuffer(ByteBuffer.wrap(visits[k]!!.out))
        }
    }

    /** Grid tiles [t] onto [c], file px */
    fun draw(c: Canvas, t: Array<Bitmap?>, paint: Paint, r: RectF, blank: Paint? = null) = c.drawTiles(t, cols, 1f / k, paint, r, blank, ox / k, oy / k)
}

/** Drawing, Eraser, Stamp and Blur on the active layer ([aim]), and the Selection brush over the canvas; screen px. Dabs start past touch slop or on lift: a pinch leaves nothing.
Points are joined by quadratic curves; live tiles at about screen resolution (edge-aware ones at full: as baked); on release [onStroke] bakes at full resolution off the main thread.
Live state is read in the draw phase.
Stamp: a pick places the source mark; the first stroke after it fixes the offset, the next keep it. [idle] — no overlay open: a press that closes one isn't a stroke.
«С учётом краёв» (BrushOptions.edges): each dab cut to its center's area of the active layer's px without own edge-aware paint (EdgePx) */
@Stable
class BrushTool(private val o: ToolOptions, private val idle: () -> Boolean, private val onStroke: (Stroke, () -> Unit) -> Unit) : MaskOverlay {
    private var layer by mutableStateOf<Layer?>(null) // read by has() in the draw phase
    private var cut: EdgePx? = null // the active layer's px edge-aware dabs are cut by
    private var tool by mutableStateOf(Tool.Eraser)
    private val fileM = Matrix() // file px → canvas px
    private var sel: Selection? = null
    private var clip: Path? = null // file px; a soft one's whole rect
    private var layerBmp: Bitmap? = null
    private var layerMask: LayerMask? = null
    private var layerPatch: Tiles? = null
    private var canvasW = 0
    private var canvasH = 0

    /** [l] — the active layer as Layer.paintable, [b], [m], [p] — its pixels, mask, patch, [c] — its px edge-aware dabs are cut by; the Selection tool paints
    the canvas as a layer, id 0: not clipped by the selection. [cw]×[ch] — the canvas */
    fun aim(l: Layer?, s: Selection?, t: Tool, b: Bitmap?, m: LayerMask?, p: Tiles?, c: EdgePx?, cw: Int, ch: Int) {
        val on = if (t == Tool.Selection) Layer(w = cw, h = ch, id = 0) else l
        val area = s.takeUnless { t == Tool.Selection }
        if (on != null && (on != layer || area !== sel)) {
            fileM.set(on.fileToCanvas())
            clip = area?.let { (it.feather?.let { f -> Path().apply { addRect(RectF(f.bounds), Path.Direction.CW) } } ?: Path(it.outline)).apply { transform(on.canvasToFile()) } }
        }
        layer = on
        cut = c
        sel = area
        tool = t
        layerBmp = b
        layerMask = m
        layerPatch = p
        canvasW = cw
        canvasH = ch
    }

    private var version by mutableIntStateOf(0)
    private var ring by mutableStateOf(Offset.Unspecified) // the finger during a stroke
    private var ringR = 0f

    // Stamp, canvas px: the picked source, the fixed offset (null — the next stroke fixes it), the current stroke's
    private var source: Offset? = null
    private var shift: Offset? = null
    private var curShift = Offset.Zero
    private var mark by mutableStateOf(Offset.Unspecified) // moves only once a stroke really starts
    private var markWas = Offset.Unspecified // restored on a pinch
    private var picking = false
    private var touch = Offset.Zero // canvas px
    private var viewOffset = Offset.Zero
    private var viewScale = 1f

    private val shown = ArrayList<Live>() // unbaked, the current one last
    private var cur: Live? = null
    private var curArea: Selection? = null
    private val spare = ArrayList<Bitmap>()
    private val canvas = Canvas()
    private val toFile = Matrix()
    private val pt = FloatArray(2)
    private var xy = FloatArray(1024) // file px
    private var count = 0
    private var startX = 0f // file px
    private var startY = 0f
    private var lastX = 0f // the next curve's control
    private var lastY = 0f
    private var endX = 0f // midpoint before the last point
    private var endY = 0f
    private var r = 0f // file px
    private var step = 0f
    private var dab: Dab? = null
    private var prevX = Float.NaN // the dab before, tile px
    private var prevY = Float.NaN
    private val erasePaint = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val colorPaint = tilePaint()
    private val coverPaint = tilePaint()
    private val keepTiles = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val featherPaint = keepPaint().apply { isFilterBitmap = true }
    private val addPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val resultPaint = tilePaint()
    private val sourcePaints = MaskPaints()
    private val invertPaint = tilePaint().apply { isFilterBitmap = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE } // whole px, as the selection will be
    private val rect = RectF()

    private val brush get() = when (tool) {
        Tool.Drawing -> o.drawing
        Tool.Stamp -> o.stamp.brush
        Tool.Selection -> o.selection.brush
        Tool.Blur -> o.blur.brush
        Tool.Eraser -> o.eraser
    }

    /** The ring's radius at the aimed tool's brush size, screen px */
    fun radius(density: Float) = brush[BrushParam.Size].value * density / 2

    /** This press or the next picks the Stamp's source: a point, no ring */
    val picks get() = picking || tool == Tool.Stamp && (o.stamp.source || source == null)

    fun begin(p: Offset, offset: Offset, scale: Float, density: Float) {
        if (!idle()) return
        viewOffset = offset
        viewScale = scale
        val at = (p - offset) / scale
        touch = at
        markWas = mark
        val stamp = tool == Tool.Stamp
        if (picks) {
            picking = true
            return
        }
        val l = layer
        val selects = tool == Tool.Selection
        if (l == null || !l.visible || tool != Tool.Drawing && !selects && !l.hasImage) return
        val b = brush
        val finger = tool == Tool.Blur && o.blur.style.value == BlurStyle.Finger
        val intensity = b[BrushParam.Intensity].value / 100f
        if (tool == Tool.Blur && intensity == 0f) return
        val back = Matrix().also { fileM.invert(it) }
        val clone = if (!stamp) null else {
            val d = shift ?: ((source ?: return) - at).let { Offset(it.x.roundToInt().toFloat(), it.y.roundToInt().toFloat()) }
            curShift = d
            pt[0] = d.x
            pt[1] = d.y
            back.mapVectors(pt)
            Clone(pt[0].roundToInt(), pt[1].roundToInt(), o.stamp.style.value == StampStyle.Healing)
        }
        toFile.set(fileM)
        toFile.postScale(scale, scale)
        toFile.postTranslate(offset.x, offset.y)
        toFile.invert(toFile)
        ringR = radius(density)
        r = toFile.mapRadius(ringR)
        // Smudge: dabs ≤ SMEAR_STEP apart in the bake's raster (the finest)
        val spacing = maxOf(2 * SPACING, MIN_STEP / r).let { if (finger) minOf(it, SMEAR_STEP / minOf(r, SMEAR_MAX)) else it }
        step = spacing * r
        val hard = b[BrushParam.Hardness].value / 100f
        val flow = b[BrushParam.Paint].value / 100f
        val edge = if (finger) 1f else hard // the smudge has the profile itself
        if (dab.let { it == null || it.hardness != edge || it.flow != flow }) dab = Dab(edge, flow)
        prevX = Float.NaN
        prevY = Float.NaN
        val aware = cut?.takeIf { b.edges.value && it.layer.hasImage }
        // Edge-aware: at full size, as the bake makes them
        val k = if (aware != null) 1f else minOf(1f, scale, if (finger) SMEAR_MAX / r else 1f)
        // The canvas too, in file px: a stroke past the layer grows it (Stroke.room)
        val grid = RectF(0f, 0f, canvasW.toFloat(), canvasH.toFloat()).apply { back.mapRect(this); union(0f, 0f, l.fileW.toFloat(), l.fileH.toFloat()) }
        val ox = floor(grid.left * k).toInt()
        val oy = floor(grid.top * k).toInt()
        val lw = ceil(grid.right * k).toInt() - ox
        val lh = ceil(grid.bottom * k).toInt() - oy
        val cols = (lw + TILE - 1) / TILE
        val rows = (lh + TILE - 1) / TILE
        val lb = layerBmp
        val lm = layerMask
        val lp = layerPatch
        val blur = if (tool != Tool.Blur) null else {
            // Its own canvas: the bake may go on with a handed smudge
            val render = l.renderer(lb, lm, lp, k, ox = ox, oy = oy)
            // keep q moves px by 2r·q/(1 − q) = push diameters
            val push = intensity * intensity * SMEAR_PUSH
            val smear = if (finger) Smear(lw, lh, r * k, hard, push / (1 + push), lb, k < 1f, render) else null
            val sigma = MAX_BLUR * intensity * intensity * density // screen px
            val fileSigma = toFile.mapRadius(sigma)
            // Copy px about LIVE_GRID screen px; a wide σ capped at BLUR_EXACT, as the bake's
            val area = if (finger) null else Blurred(lw, lh, fileSigma * k, (sigma / LIVE_GRID).coerceIn(LIVE_SIGMA, BLUR_EXACT))
            // At the bake's raster (Stroke.bake: ks, from file px 0) the bake takes the smudge as is
            LiveBlur(fileSigma, area, smear, render, lb, cols, cols * rows, smear?.takeIf { k == minOf(1f, SMEAR_MAX / r) && ox == 0 && oy == 0 }?.let { Smeared(it, lm, lp) })
        }
        // Over the px the bake reads: an Eraser's layer doesn't grow (Stroke.room), past it its edge px repeat. The Selection brush's: on the canvas
        val edges = aware?.let {
            val t = b.edgeTolerance
            if (tool == Tool.Eraser) Edges(0, 0, l.fileW, l.fileH, k, t, it.bmp, it.renderer(back, k)) else Edges(ox, oy, lw, lh, k, t, it.bmp, it.renderer(back, k, ox, oy))
        }
        cur = Live(l, if (selects) cut?.layer?.id ?: 0 else l.id, if (tool == Tool.Drawing) o.color.value.toArgb() else null, clone, k, ox, oy, cols, rows, b[BrushParam.Opacity].value / 100f, clip,
            sel?.feather, back, if (selects) o.selection.mode.value.op else null, blur, edges)
        curArea = sel
        count = 0
        pt[0] = p.x
        pt[1] = p.y
        toFile.mapPoints(pt)
        startX = pt[0]
        startY = pt[1]
    }

    fun drag(p: Offset) {
        touch = (p - viewOffset) / viewScale
        if (picking) { mark = touch; return }
        val s = cur ?: return
        ring = p
        if (s.clone != null) mark = touch + curShift
        val n = count
        if (count == 0) {
            put(startX, startY)
            lastX = startX
            lastY = startY
            endX = startX
            endY = startY
        }
        pt[0] = p.x
        pt[1] = p.y
        toFile.mapPoints(pt)
        curve(lastX, lastY, (lastX + pt[0]) / 2, (lastY + pt[1]) / 2)
        lastX = pt[0]
        lastY = pt[1]
        if (count != n) version++
    }

    fun finish() {
        if (picking) {
            picking = false
            source = touch
            mark = touch
            shift = null
            o.stamp.source = false
            return
        }
        val s = cur ?: return
        val d = dab ?: return
        val lb = s.blur
        if (count == 0 && lb?.smear != null) return cancel()
        if (count == 0) put(startX, startY) else walk(lastX, lastY)
        cur = null
        ring = Offset.Unspecified
        if (s.clone != null) {
            shift = curShift
            mark = touch + curShift
        }
        version++
        val smear = lb?.smear
        onStroke(Stroke(s.from, s.on.file, s.color, xy.copyOf(2 * count), r, smear?.hardness ?: d.hardness, d.flow, s.opacity, curArea, s.clone, s.select,
            blur = if (lb != null && smear == null) lb.sigma else 0f, smudge = smear?.keep, smeared = lb?.smeared, edges = s.edges?.tolerance)) { drop(s) }
    }

    fun cancel() {
        if (picking) { picking = false; mark = markWas; return }
        val s = cur ?: return
        cur = null
        ring = Offset.Unspecified
        if (s.clone != null) mark = markWas
        drop(s)
    }

    /** Quadratic curve from (endX, endY) via ([cx], [cy]) to ([x], [y]) in step-long pieces */
    private fun curve(cx: Float, cy: Float, x: Float, y: Float) {
        val n = ((hypot(cx - endX, cy - endY) + hypot(x - cx, y - cy)) / step).toInt().coerceIn(1, MAX_PIECES)
        for (i in 1..n) {
            val t = i.toFloat() / n
            val u = 1 - t
            walk(u * u * endX + 2 * u * t * cx + t * t * x, u * u * endY + 2 * u * t * cy + t * t * y)
        }
        endX = x
        endY = y
    }

    /** Dabs every step toward ([x], [y]); the remainder carries over */
    private fun walk(x: Float, y: Float) {
        var px = xy[2 * count - 2]
        var py = xy[2 * count - 1]
        var d = hypot(x - px, y - py)
        while (d >= step) {
            px += (x - px) * step / d
            py += (y - py) * step / d
            put(px, py)
            d -= step
        }
    }

    private fun put(x: Float, y: Float) {
        val s = cur ?: return
        if (count == 0) shown += s
        if (2 * count + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * count] = x
        xy[2 * count + 1] = y
        count++
        s.bounds.union(x - r, y - r, x + r, y + r)
        val d = dab ?: return
        val lx = x * s.k
        val ly = y * s.k
        val lr = r * s.k
        val c0 = floor((lx - lr - s.ox) / TILE).toInt().coerceAtLeast(0)
        val c1 = floor((lx + lr - s.ox) / TILE).toInt().coerceAtMost(s.cols - 1)
        val r0 = floor((ly - lr - s.oy) / TILE).toInt().coerceAtLeast(0)
        val r1 = floor((ly + lr - s.oy) / TILE).toInt().coerceAtMost(s.rows - 1)
        val b = s.blur
        val e = s.edges?.takeIf { c0 <= c1 && r0 <= r1 }?.also { it.cut(lx, ly, lr) }
        for (ty in r0..r1) for (tx in c0..c1) {
            val k = ty * s.cols + tx
            val bx = s.ox + tx * TILE
            val by = s.oy + ty * TILE
            // Mipmapped: full-size ones shrink on screen as smoothly as the baked layer's smaller copies
            val t = s.tiles[k] ?: (spare.removeLastOrNull() ?: createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8).apply { setHasMipMap(true) }).also { s.tiles[k] = it }
            if (d.solid && e == null) {
                canvas.setBitmap(t)
                canvas.translate(-bx.toFloat(), -by.toFloat())
                d.draw(canvas, lx, ly, lr)
            } else {
                val v = s.visits[k] ?: Visited().also { s.visits[k] = it }
                d.visit(lx, ly, prevX, prevY, lr, bx, by, TILE, TILE, TILE, v.acc, v.cur, v.out, e)
                s.visited(k)
            }
            if (b != null && b.area != null && b.px[k] == null) b.area.into(b.tile(k), tx * TILE, ty * TILE, b.render)
        }
        prevX = lx
        prevY = ly
        b?.smear?.dab(lx - s.ox, ly - s.oy)
    }

    /** ARGB tiles aren't recycled: the last frame may still draw them */
    private fun drop(s: Live) {
        shown.remove(s)
        for (t in s.tiles) if (t != null) { t.eraseColor(Color.TRANSPARENT); spare += t }
        s.blur?.recycle()
        s.edges?.recycle()
        version++
    }

    /** A New-mode Selection brush stroke is shown: the selection it replaces is hidden meanwhile. Draw phase */
    val replacing: Boolean get() {
        version
        for (k in shown.indices) if (shown[k].select == Region.Op.REPLACE) return true
        return false
    }

    /** Reads [version] only for the aimed layer or one with strokes: cached groups in CanvasView aren't invalidated */
    fun has(id: Long): Boolean {
        var any = false
        for (k in shown.indices) if (shown[k].on.id == id) any = true
        if (any || id == layer?.id) version
        return any
    }

    /** As baking applies it; [filter] — the layer's. A clone copies the layer as shown ([bmp] … [k]). A blur lerps: DST_OUT the tiles, then ADD its result × the tiles.
    A stroke begun before an earlier one grew [l] (new file) is carried onto it, as the bake carries it */
    override fun draw(c: Canvas, l: Layer, filter: Boolean, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, k: Float) {
        for (i in shown.indices) {
            val s = shown[i]
            if (s.on.id != l.id) continue
            s.upload()
            val onto = if (s.on.file == l.file) null else s.on.fileToCanvas().apply { postConcat(l.canvasToFile()) }
            val ink = s.color
            val from = s.clone
            val lb = s.blur
            val paint = if (ink == null && from == null) erasePaint else colorPaint.apply { if (ink != null) color = ink }
            paint.isFilterBitmap = filter
            paint.alpha = (s.opacity * 255).roundToInt()
            c.save()
            onto?.let { c.concat(it) }
            s.clip?.let { c.clipPath(it) }
            when {
                lb != null -> {
                    lb.upload()
                    c.clipRect(s.bounds)
                    c.layered(s, paint, filter)
                    addPaint.alpha = paint.alpha
                    c.layered(s, addPaint, filter) { resultPaint.isFilterBitmap = filter; s.draw(this, lb.px, resultPaint, rect); true }
                }
                s.feather == null && from == null -> s.draw(c, s.tiles, paint, rect)
                from == null -> c.layered(s, paint, filter, ink ?: Color.BLACK)
                else -> {
                    c.clipRect(s.bounds)
                    c.layered(s, paint, filter) {
                        save()
                        translate(-from.dx.toFloat(), -from.dy.toFloat())
                        onto?.let { concat(Matrix().apply { it.invert(this) }) }
                        drawMasked(bmp, l.fileW, l.fileH, mask, patch, k, 255, filter, sourcePaints)
                        restore()
                        true
                    }
                }
            }
            c.restore()
        }
    }

    /** A layer put by [paint]: [s]'s tiles × soft edge in [color], or × what [source] draws (returns true) */
    private inline fun Canvas.layered(s: Live, paint: Paint, filter: Boolean, color: Int = Color.BLACK, source: Canvas.() -> Boolean = { false }) {
        saveClipLayer(paint)
        if (source()) {
            keepTiles.isFilterBitmap = filter
            s.draw(this, s.tiles, keepTiles, rect, clearPaint)
        } else {
            coverPaint.color = color
            coverPaint.isFilterBitmap = filter
            s.draw(this, s.tiles, coverPaint, rect)
        }
        s.feather?.let { concat(s.toFile); it.draw(this, featherPaint) }
        restore()
    }

    /** Selection brush strokes inverted, the ring under the finger and the one at [cursor] — the virtual cursor's spot (Unspecified — no finger holds it; null — off),
    the Stamp's source mark: with the cursor only while it shows a ring, following it once the offset is fixed */
    fun DrawScope.drawRing(offset: Offset, scale: Float, cw: Int, ch: Int, cursor: Offset?) {
        val c = drawContext.canvas.nativeCanvas
        version
        c.save()
        c.translate(offset.x, offset.y)
        c.scale(scale, scale)
        c.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        for (i in shown.indices) shown[i].let { if (it.select != null) { it.upload(); it.draw(c, it.tiles, invertPaint, rect) } }
        c.restore()
        // One path: DIFFERENCE isn't applied twice where the rings meet — the cursor's stroke has both in one place
        ringPath.rewind()
        val p = ring
        if (!p.isUnspecified) ringPath.addCircle(p.x, p.y, ringR, Path.Direction.CW)
        val at = cursor?.takeUnless { it.isUnspecified || picks }
        if (at != null) ringPath.addCircle(at.x, at.y, radius(density), Path.Direction.CW)
        ringPaint.strokeWidth = RING_W.toPx()
        c.drawPath(ringPath, ringPaint)
        if (tool != Tool.Stamp || cursor != null && at == null) return
        val s = shift
        val m = if (at != null && s != null) (at - offset) / scale + s else mark
        if (m.isUnspecified) return
        drawCross(offset.x + m.x * scale, offset.y + m.y * scale)
    }
}
