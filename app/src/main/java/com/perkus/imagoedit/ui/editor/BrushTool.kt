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
import com.perkus.imagoedit.data.composite
import com.perkus.imagoedit.data.drawMasked
import com.perkus.imagoedit.data.drawTiles
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.keepPaint
import com.perkus.imagoedit.data.patchTile
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

/** A stroke's live ALPHA_8 coverage on layer [layer] (Layer.id), [k] tile px per file px; [color] null — erases, unless [clone] or [blur]; [clip], [feather] — the selection, file px;
[toFile] — canvas px → file px; [select] — the Selection brush's. A not solid Dab's [tiles] come from their [visits] */
private class Live(val layer: Long, val color: Int?, val clone: Clone?, val k: Float, val cols: Int, val rows: Int, val opacity: Float, val clip: Path?, val feather: Feather?,
    val toFile: Matrix, val select: Region.Op?, val blur: LiveBlur?) {
    val tiles = arrayOfNulls<Bitmap>(cols * rows)
    val visits = arrayOfNulls<Visited>(cols * rows)
    val bounds = RectF() // the dabs' reach, file px
}

/** Drawing, Eraser, Stamp and Blur on the active layer ([aim]), and the Selection brush over the canvas; screen px. Dabs start past touch slop or on lift: a pinch leaves nothing.
Points are joined by quadratic curves; live tiles at about screen resolution; on release [onStroke] bakes at full resolution off the main thread. Live state is read in the draw phase.
Stamp: a pick places the source mark; the first stroke after it fixes the offset, the next keep it. [idle] — no overlay open: a press that closes one isn't a stroke */
@Stable
class BrushTool(private val o: ToolOptions, private val idle: () -> Boolean, private val onStroke: (Stroke, () -> Unit) -> Unit) : MaskOverlay {
    private var layer by mutableStateOf<Layer?>(null) // read by has() in the draw phase
    private var tool by mutableStateOf(Tool.Eraser)
    private val fileM = Matrix() // file px → canvas px
    private var sel: Selection? = null
    private var clip: Path? = null // file px; a soft one's whole rect
    private var layerBmp: Bitmap? = null
    private var layerMask: LayerMask? = null
    private var layerPatch: Tiles? = null

    /** [l] — as Layer.paintable, [b], [m], [p] — its pixels, mask, patch; for the Selection tool the canvas as a layer, id 0: not clipped by the selection */
    fun aim(l: Layer?, s: Selection?, t: Tool, b: Bitmap?, m: LayerMask?, p: Tiles?) {
        val area = s.takeUnless { t == Tool.Selection }
        if (l != null && (l != layer || area !== sel)) {
            fileM.set(l.fileToCanvas())
            clip = area?.let { (it.feather?.let { f -> Path().apply { addRect(RectF(f.bounds), Path.Direction.CW) } } ?: Path(it.outline)).apply { transform(l.canvasToFile()) } }
        }
        layer = l
        sel = area
        tool = t
        layerBmp = b
        layerMask = m
        layerPatch = p
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
    private var curFile: String? = null
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
    // DIFFERENCE: visible on any color
    private val ringPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE }
    private val invertPaint = tilePaint().apply { isFilterBitmap = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE } // whole px, as the selection will be
    private val markPath = Path()
    private val rect = RectF()

    fun begin(p: Offset, offset: Offset, scale: Float, density: Float) {
        if (!idle()) return
        viewOffset = offset
        viewScale = scale
        val at = (p - offset) / scale
        touch = at
        markWas = mark
        val stamp = tool == Tool.Stamp
        if (stamp && (o.stamp.source || source == null)) {
            picking = true
            return
        }
        val l = layer
        val selects = tool == Tool.Selection
        if (l == null || !l.visible || tool != Tool.Drawing && !selects && !l.hasImage) return
        val b = when (tool) {
            Tool.Drawing -> o.drawing
            Tool.Stamp -> o.stamp.brush
            Tool.Selection -> o.selection.brush
            Tool.Blur -> o.blur.brush
            Tool.Eraser -> o.eraser
        }
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
        ringR = b[BrushParam.Size].value * density / 2
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
        val k = minOf(1f, scale, if (finger) SMEAR_MAX / r else 1f)
        val cols = ceil(l.fileW * k / TILE).toInt()
        val rows = ceil(l.fileH * k / TILE).toInt()
        val blur = if (tool != Tool.Blur) null else {
            val lw = ceil(l.fileW * k).toInt()
            val lh = ceil(l.fileH * k).toInt()
            val lb = layerBmp
            val lm = layerMask
            val lp = layerPatch
            val fw = l.fileW
            val fh = l.fileH
            // Its own canvas: the bake may go on with a handed smudge
            val rc = Canvas()
            val rp = MaskPaints()
            val render = { into: Bitmap, x: Int, y: Int -> rc.composite(into, x, y, lb, fw, fh, lm, lp, rp, k) }
            // keep q moves px by 2r·q/(1 − q) = push diameters
            val push = intensity * intensity * SMEAR_PUSH
            val smear = if (finger) Smear(lw, lh, r * k, hard, push / (1 + push), lb, k < 1f, render) else null
            val sigma = MAX_BLUR * intensity * intensity * density // screen px
            val fileSigma = toFile.mapRadius(sigma)
            // Copy px about LIVE_GRID screen px; a wide σ capped at BLUR_EXACT, as the bake's
            val area = if (finger) null else Blurred(lw, lh, fileSigma * k, (sigma / LIVE_GRID).coerceIn(LIVE_SIGMA, BLUR_EXACT))
            // At the bake's raster (Stroke.bake: ks) the bake takes the smudge as is
            LiveBlur(fileSigma, area, smear, render, lb, cols, cols * rows, smear?.takeIf { k == minOf(1f, SMEAR_MAX / r) }?.let { Smeared(it, lm, lp) })
        }
        cur = Live(l.id, if (tool == Tool.Drawing) o.color.value.toArgb() else null, clone, k, cols, rows, b[BrushParam.Opacity].value / 100f, clip, sel?.feather, back,
            if (selects) o.selection.mode.value.op else null, blur)
        curFile = l.file
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
        onStroke(Stroke(s.layer, curFile, s.color, xy.copyOf(2 * count), r, smear?.hardness ?: d.hardness, d.flow, s.opacity, curArea, s.clone, s.select,
            blur = if (lb != null && smear == null) lb.sigma else 0f, smudge = smear?.keep, smeared = lb?.smeared)) { drop(s) }
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
        val c0 = floor((lx - lr) / TILE).toInt().coerceAtLeast(0)
        val c1 = floor((lx + lr) / TILE).toInt().coerceAtMost(s.cols - 1)
        val r0 = floor((ly - lr) / TILE).toInt().coerceAtLeast(0)
        val r1 = floor((ly + lr) / TILE).toInt().coerceAtMost(s.rows - 1)
        val b = s.blur
        for (ty in r0..r1) for (tx in c0..c1) {
            val k = ty * s.cols + tx
            val t = s.tiles[k] ?: (spare.removeLastOrNull() ?: createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)).also { s.tiles[k] = it }
            if (d.solid) {
                canvas.setBitmap(t)
                canvas.translate(-tx * TILE.toFloat(), -ty * TILE.toFloat())
                d.draw(canvas, lx, ly, lr)
            } else {
                val v = s.visits[k] ?: Visited().also { s.visits[k] = it }
                d.visit(lx, ly, prevX, prevY, lr, tx * TILE, ty * TILE, TILE, TILE, TILE, v.acc, v.cur, v.out)
                t.copyPixelsFromBuffer(ByteBuffer.wrap(v.out))
            }
            if (b != null && b.area != null && b.px[k] == null) b.area.into(b.tile(k), tx * TILE, ty * TILE, b.render)
        }
        prevX = lx
        prevY = ly
        b?.smear?.dab(lx, ly)
    }

    /** ARGB tiles aren't recycled: the last frame may still draw them */
    private fun drop(s: Live) {
        shown.remove(s)
        for (t in s.tiles) if (t != null) { t.eraseColor(Color.TRANSPARENT); spare += t }
        s.blur?.recycle()
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
        for (k in shown.indices) if (shown[k].layer == id) any = true
        if (any || id == layer?.id) version
        return any
    }

    /** As baking applies it; [filter] — the layer's. A clone copies the layer as shown ([bmp] … [k]). A blur lerps: DST_OUT the tiles, then ADD its result × the tiles */
    override fun draw(c: Canvas, key: Long, filter: Boolean, bmp: Bitmap?, w: Int, h: Int, mask: LayerMask?, patch: Tiles?, k: Float) {
        for (i in shown.indices) {
            val s = shown[i]
            if (s.layer != key) continue
            val ink = s.color
            val from = s.clone
            val lb = s.blur
            val paint = if (ink == null && from == null) erasePaint else colorPaint.apply { if (ink != null) color = ink }
            paint.isFilterBitmap = filter
            paint.alpha = (s.opacity * 255).roundToInt()
            c.save()
            s.clip?.let { c.clipPath(it) }
            when {
                lb != null -> {
                    lb.upload()
                    c.clipRect(s.bounds)
                    c.layered(s, paint, filter)
                    addPaint.alpha = paint.alpha
                    c.layered(s, addPaint, filter) { resultPaint.isFilterBitmap = filter; drawTiles(lb.px, s.cols, 1f / s.k, resultPaint, rect); true }
                }
                s.feather == null && from == null -> c.drawTiles(s.tiles, s.cols, 1f / s.k, paint, rect)
                from == null -> c.layered(s, paint, filter, ink ?: Color.BLACK)
                else -> {
                    c.clipRect(s.bounds)
                    c.layered(s, paint, filter) {
                        save()
                        translate(-from.dx.toFloat(), -from.dy.toFloat())
                        drawMasked(bmp, w, h, mask, patch, k, 255, filter, sourcePaints)
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
        saveLayer(null, paint)
        if (source()) {
            keepTiles.isFilterBitmap = filter
            drawTiles(s.tiles, s.cols, 1f / s.k, keepTiles, rect, clearPaint)
        } else {
            coverPaint.color = color
            coverPaint.isFilterBitmap = filter
            drawTiles(s.tiles, s.cols, 1f / s.k, coverPaint, rect)
        }
        s.feather?.let { concat(s.toFile); it.draw(this, featherPaint) }
        restore()
    }

    /** Selection brush strokes inverted, the ring under the finger, the Stamp's source mark */
    fun DrawScope.drawRing(offset: Offset, scale: Float, cw: Int, ch: Int) {
        val c = drawContext.canvas.nativeCanvas
        version
        c.save()
        c.translate(offset.x, offset.y)
        c.scale(scale, scale)
        c.clipRect(0f, 0f, cw.toFloat(), ch.toFloat())
        for (i in shown.indices) shown[i].let { if (it.select != null) c.drawTiles(it.tiles, it.cols, 1f / it.k, invertPaint, rect) }
        c.restore()
        ringPaint.strokeWidth = RING_W.toPx()
        val p = ring
        if (!p.isUnspecified) c.drawCircle(p.x, p.y, ringR, ringPaint)
        val m = mark
        if (tool != Tool.Stamp || m.isUnspecified) return
        val x = offset.x + m.x * scale
        val y = offset.y + m.y * scale
        val arm = MARK_ARM.toPx()
        // One path: DIFFERENCE isn't applied twice where the lines cross
        markPath.rewind()
        markPath.moveTo(x - arm, y)
        markPath.lineTo(x + arm, y)
        markPath.moveTo(x, y - arm)
        markPath.lineTo(x, y + arm)
        ringPaint.strokeWidth = MARK_W.toPx()
        c.drawPath(markPath, ringPaint)
    }
}
