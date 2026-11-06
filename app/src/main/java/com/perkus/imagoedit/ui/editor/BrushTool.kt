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
import com.perkus.imagoedit.data.Clone
import com.perkus.imagoedit.data.Dab
import com.perkus.imagoedit.data.Feather
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MaskOverlay
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.Selection
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
import com.perkus.imagoedit.data.tilePaint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val SPACING = 0.05f // шаг дабов вдоль штриха, доля диаметра кисти
private const val MIN_STEP = 0.5f // наименьший шаг дабов, px файла слоя: тонкая кисть при большом зуме
private const val MAX_PIECES = 32 // наибольшее число отрезков кривой между двумя точками пальца
private val RING_W = 1.dp // толщина кольца кисти под пальцем
private val MARK_ARM = 8.dp // плечо креста метки образца штампа

/** A stroke's live ALPHA_8 coverage on layer [layer] (Layer.id), [k] tile px per file px (about the screen's); [color] null — erases, unless [clone]; [clip] — the selection, file px;
[feather] — its soft edge, [toFile] — canvas px → the layer's file px; [select] — the Selection brush's (see Stroke.select) */
private class Live(val layer: Long, val color: Int?, val clone: Clone?, val k: Float, val cols: Int, val rows: Int, val opacity: Float, val clip: Path?, val feather: Feather?,
    val toFile: Matrix, val select: Region.Op?) {
    val tiles = arrayOfNulls<Bitmap>(cols * rows)
    val bounds = RectF() // the dabs' reach, file px
}

/** Drawing, Eraser and Stamp gesture on the active layer ([aim]), and the Selection tool's brush over the canvas (shown inverted, then joined to the selection), canvas view screen px. Dabs start only past touch slop or on lift (a tap — a dot): a pinch leaves nothing.
Finger points are joined by quadratic curves through their midpoints; dabs go into live tiles at about screen resolution: a move costs only its new dabs.
On release [onStroke] bakes at full resolution off the main thread; until then the live tiles are drawn over the layer (MaskOverlay). Live state is read only in the draw phase.
Stamp: while picking (StampOptions.source, or no source yet) a tap or drag places the source mark; the first stroke after it fixes the offset to it, the next ones keep it.
A healing stroke shows as a clone until baked. [idle] — no overlay open: a press that closes one isn't a stroke */
@Stable
class BrushTool(private val o: ToolOptions, private val idle: () -> Boolean, private val onStroke: (Stroke, () -> Unit) -> Unit) : MaskOverlay {
    private var layer: Layer? = null
    private var tool by mutableStateOf(Tool.Eraser) // the source mark is drawn for the Stamp only
    private val fileM = Matrix() // the aimed layer's file px → canvas px
    private var sel: Selection? = null
    private var clip: Path? = null // [sel] in the aimed layer's file px: its outline, a soft one's whole rect (its coverage does the rest)

    /** [l] — as Layer.paintable; for the Selection tool the canvas as a layer, id 0 (no layer's): not clipped by the selection */
    fun aim(l: Layer?, s: Selection?, t: Tool) {
        val area = s.takeUnless { t == Tool.Selection }
        if (l != null && (l != layer || area !== sel)) {
            fileM.set(l.fileToCanvas())
            clip = area?.let { (it.feather?.let { f -> Path().apply { addRect(RectF(f.bounds), Path.Direction.CW) } } ?: Path(it.outline)).apply { transform(l.canvasToFile()) } }
        }
        layer = l
        sel = area
        tool = t
    }

    private var version by mutableIntStateOf(0)
    private var ring by mutableStateOf(Offset.Unspecified) // the finger during a stroke
    private var ringR = 0f

    // Stamp, canvas px: the picked source, the offset strokes copy from (whole px; null — the next stroke fixes it), the current stroke's, and where the source is now (the mark)
    private var source: Offset? = null
    private var shift: Offset? = null
    private var curShift = Offset.Zero
    private var mark by mutableStateOf(Offset.Unspecified) // moves only once a stroke or a pick really starts: past touch slop or on lift, as dabs do
    private var markWas = Offset.Unspecified // restored if the gesture turns out a pinch
    private var picking = false
    private var touch = Offset.Zero // the finger, canvas px
    private var viewOffset = Offset.Zero
    private var viewScale = 1f

    private val shown = ArrayList<Live>() // unbaked strokes in order, the current one last
    private var cur: Live? = null
    private var curFile: String? = null
    private var curArea: Selection? = null
    private val spare = ArrayList<Bitmap>() // cleared tiles for the next strokes
    private val canvas = Canvas()
    private val toFile = Matrix()
    private val pt = FloatArray(2)
    private var xy = FloatArray(1024) // dab centers of the current stroke, file px; reused
    private var count = 0
    private var startX = 0f // the touch, file px
    private var startY = 0f
    private var lastX = 0f // the finger's last point, file px: the next curve's control
    private var lastY = 0f
    private var endX = 0f // where the curves so far end: the midpoint before the last point
    private var endY = 0f
    private var r = 0f // dab radius, file px
    private var step = 0f // file px
    private var dab: Dab? = null
    private val erasePaint = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val colorPaint = tilePaint() // ALPHA_8 tiles drawn in the paint's color
    private val coverPaint = tilePaint() // a stroke's tiles in its own layer, there × the soft edge
    private val keepTiles = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) } // a clone's source × its tiles
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) } // where it has no tile
    private val featherPaint = keepPaint().apply { isFilterBitmap = true }
    private val sourcePaints = MaskPaints() // the layer drawn again under a clone stroke
    // DIFFERENCE: visible on any color
    private val ringPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE }
    private val invertPaint = tilePaint().apply { isFilterBitmap = false; color = Color.WHITE; blendMode = BlendMode.DIFFERENCE } // magnified on whole px, as the selection will be
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
            else -> o.eraser
        }
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
        val spacing = maxOf(2 * SPACING, MIN_STEP / r)
        step = spacing * r
        val hard = b[BrushParam.Hardness].value / 100f
        val flow = b[BrushParam.Paint].value / 100f
        if (dab.let { it == null || it.hardness != hard || it.flow != flow || it.spacing != spacing }) dab = Dab(hard, flow, spacing)
        val k = minOf(1f, scale)
        cur = Live(l.id, if (tool == Tool.Drawing) o.color.value.toArgb() else null, clone, k, ceil(l.fileW * k / TILE).toInt(), ceil(l.fileH * k / TILE).toInt(),
            b[BrushParam.Opacity].value / 100f, clip, sel?.feather, back, if (selects) o.selection.mode.value.op else null)
        curFile = l.file
        curArea = sel
        count = 0
        pt[0] = p.x
        pt[1] = p.y
        toFile.mapPoints(pt)
        startX = pt[0]
        startY = pt[1]
    }

    /** Past touch slop only */
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
        var d = dab ?: return
        if (count == 0) { d = Dab(d.hardness, d.flow, 2f); dab = d; put(startX, startY) } else walk(lastX, lastY)
        cur = null
        ring = Offset.Unspecified
        if (s.clone != null) {
            shift = curShift
            mark = touch + curShift
        }
        version++
        onStroke(Stroke(s.layer, curFile, s.color, xy.copyOf(2 * count), r, d.hardness, d.flow, d.spacing, s.opacity, curArea, s.clone, s.select)) { drop(s) }
    }

    fun cancel() {
        if (picking) { picking = false; mark = markWas; return }
        val s = cur ?: return
        cur = null
        ring = Offset.Unspecified
        if (s.clone != null) mark = markWas
        drop(s)
    }

    /** Quadratic curve from (endX, endY) via control ([cx], [cy]) to ([x], [y]) in pieces about a step long */
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

    /** Dabs every step from the last dab toward ([x], [y]); the remainder carries over to the next piece */
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
        for (ty in r0..r1) for (tx in c0..c1) {
            val k = ty * s.cols + tx
            val t = s.tiles[k] ?: (spare.removeLastOrNull() ?: createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)).also { s.tiles[k] = it }
            canvas.setBitmap(t) // resets the matrix
            canvas.translate(-tx * TILE.toFloat(), -ty * TILE.toFloat())
            d.draw(canvas, lx, ly, lr)
        }
    }

    private fun drop(s: Live) {
        shown.remove(s)
        for (t in s.tiles) if (t != null) { t.eraseColor(Color.TRANSPARENT); spare += t }
        version++
    }

    /** A Selection brush stroke replacing the selection (New mode) is shown, until its selection is in: the one it replaces is hidden meanwhile. Read in the draw phase */
    val replacing: Boolean get() {
        version
        for (k in shown.indices) if (shown[k].select == Region.Op.REPLACE) return true
        return false
    }

    /** Whether layer [id] has live strokes. Reads [version] only for the aimed layer or one with strokes: CanvasView's cached groups aren't invalidated */
    fun has(id: Long): Boolean {
        var any = false
        for (k in shown.indices) if (shown[k].layer == id) any = true
        if (any || id == layer?.id) version
        return any
    }

    /** As baking applies it; [filter] — the layer's: a magnified hard edge stays as crisp as baked. A clone copies the layer as shown ([bmp] … [k]): a healing one too, healed once baked */
    override fun draw(c: Canvas, key: Long, filter: Boolean, bmp: Bitmap?, w: Int, h: Int, mask: LayerMask?, patch: Tiles?, k: Float) {
        for (i in shown.indices) {
            val s = shown[i]
            if (s.layer != key) continue
            val ink = s.color
            val from = s.clone
            val paint = if (ink == null && from == null) erasePaint else colorPaint.apply { if (ink != null) color = ink }
            paint.isFilterBitmap = filter
            paint.alpha = (s.opacity * 255).roundToInt()
            c.save()
            s.clip?.let { c.clipPath(it) }
            val f = s.feather
            if (f == null && from == null) c.drawTiles(s.tiles, s.cols, 1f / s.k, paint, rect)
            else {
                // Whole in a layer of its own — the ink, or the layer the clone's offset away, × the tiles — × the soft edge there, then put as [paint] puts it
                if (from != null) c.clipRect(s.bounds)
                c.saveLayer(null, paint)
                if (from == null) {
                    coverPaint.color = ink ?: Color.BLACK
                    coverPaint.isFilterBitmap = filter
                    c.drawTiles(s.tiles, s.cols, 1f / s.k, coverPaint, rect)
                } else {
                    c.save()
                    c.translate(-from.dx.toFloat(), -from.dy.toFloat())
                    c.drawMasked(bmp, w, h, mask, patch, k, 255, filter, sourcePaints)
                    c.restore()
                    keepTiles.isFilterBitmap = filter
                    c.drawTiles(s.tiles, s.cols, 1f / s.k, keepTiles, rect, clearPaint)
                }
                if (f != null) { c.concat(s.toFile); f.draw(c, featherPaint) }
                c.restore()
            }
            c.restore()
        }
    }

    /** The Selection brush's strokes inverted, until their selection is in, within the [cw]×[ch] canvas; the ring under the finger; for the Stamp the source mark — a cross.
    [offset], [scale] — the view's */
    fun DrawScope.drawRing(offset: Offset, scale: Float, cw: Int, ch: Int) {
        val c = drawContext.canvas.nativeCanvas
        version // a selection stroke's tiles change, and it goes once its selection is in
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
        // One path: where the lines cross DIFFERENCE isn't applied twice
        markPath.rewind()
        markPath.moveTo(x - arm, y)
        markPath.lineTo(x + arm, y)
        markPath.moveTo(x, y - arm)
        markPath.lineTo(x, y + arm)
        c.drawPath(markPath, ringPaint)
    }
}
