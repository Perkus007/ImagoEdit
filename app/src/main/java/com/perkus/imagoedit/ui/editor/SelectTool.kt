package com.perkus.imagoedit.ui.editor

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.clipped
import com.perkus.imagoedit.data.moved
import com.perkus.imagoedit.data.opped
import com.perkus.imagoedit.data.toSelection
import com.perkus.imagoedit.data.unionAll
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val ANTS_DASH = 4.dp // длина штриха контура выделения (по горизонтали и вертикали); в dp — одинаково заметен на экране любой плотности
private const val ANTS_WIDTH = 2f // толщина контура выделения, экранных px при любом масштабе
const val ANTS_STEP_MS = 60L // контур сдвигается на 1 экранный px за столько мс: ~16 перерисовок в секунду вместо 60
private const val BAND_ROWS = 256 // полоса перевода контура в выделение, строк холста: рабочий буфер setPath = высота × все отрезки контура
val FIRST_R = 8.dp // кольцо первой вершины: тап по ней замыкает многоугольник
val CLOSE_REACH = 24.dp // тап ближе этого к первой вершине замыкает многоугольник: с запасом под палец
private const val POLY_STEPS = 50 // вершин многоугольника, отменяемых подряд

/** Pixels whose centers are inside, clipped to the canvas [cw]×[ch]: curves become steps along the canvas grid.
By bands of rows within the path's bounds, merged by unionAll: in one go a long lasso over a tall canvas would take hundreds of MB */
@Suppress("DEPRECATION") // computeBounds(RectF) is API 34+
private fun Path.toRegion(cw: Int, ch: Int): Region {
    val b = RectF().also { computeBounds(it, true) }
    val bottom = ceil(b.bottom).toInt().coerceAtMost(ch)
    return (floor(b.top).toInt().coerceAtLeast(0) until bottom step BAND_ROWS).asSequence()
        .map { top -> Region().apply { setPath(this@toRegion, Region(0, top, cw, minOf(top + BAND_ROWS, bottom))) } }
        .unionAll()
}

// One shader for any outline: a march step only moves its matrix, the path isn't re-dashed. A unit pattern: the matrix sizes it to ANTS_DASH
private val antsMatrix = Matrix()
private val antsPaint = Paint().apply {
    style = Paint.Style.STROKE
    isAntiAlias = false // crisp; Paint() anti-aliases since API 31
    shader = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE, Color.WHITE), floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT)
}

/** Diagonal black-and-white stripes fixed to the screen, shifted by [phase] screen px along the diagonal: along the outline they flow out of the top-left corner both ways into the bottom-right one.
[outline] in canvas px, the canvas origin at [offset] on screen, [scale] screen px per canvas px */
fun DrawScope.drawAnts(outline: Path, offset: Offset, scale: Float, phase: Int) {
    val dash = ANTS_DASH.toPx()
    antsMatrix.setScale(dash, dash)
    antsMatrix.postTranslate(phase % (2 * dash) - offset.x, -offset.y)
    antsMatrix.postScale(1 / scale, 1 / scale)
    antsPaint.shader.setLocalMatrix(antsMatrix)
    antsPaint.strokeWidth = ANTS_WIDTH / scale // the canvas below is scaled
    drawContext.canvas.nativeCanvas.run {
        save()
        translate(offset.x, offset.y)
        scale(scale, scale)
        drawPath(outline, antsPaint)
        restore()
    }
}

/** Canvas gesture of the selection styles, canvas px. The kind is fixed at the touch: inside the selection with both mode buttons off — moving it, otherwise a new shape.
Polygon: a tap places the first vertex; then each touch adds one — a tap where it was, a drag one that follows the finger until it lifts — released on the first vertex, it closes the polygon. The unfinished polygon outlives gestures until [dropPolygon].
Wand: only a tap on the canvas selects, not one inside the selection with both mode buttons off — [onWand] at its canvas px with the options and how to join the selection.
Brush: BrushTool paints the shape (see [moves]); here only a touch that moves the selection, a tap on it deselects, as the wand's.
[shape], [trace], [poly] and [shift] are read only in the draw phase. [idle] — no overlay is open: a press that closes one isn't a gesture. [onSelect] commits a new selection */
@Stable
class SelectTool(private val o: SelectionOptions, private val cw: Int, private val ch: Int, private val idle: () -> Boolean, private val onSelect: (Selection?) -> Unit,
    private val onWand: (x: Int, y: Int, tolerance: Int, contiguous: Boolean, combine: Region.Op) -> Unit) {
    var shape by mutableStateOf<Selection?>(null) // Square, Circle: the new shape while it's drawn
        private set
    private var shapePath: Path? = null // [shape]'s own path
    var trace by mutableStateOf<Path?>(null, neverEqualPolicy()) // Lasso: the line while it's drawn — one Path grows, re-set on each point to redraw
        private set
    var shift by mutableStateOf(IntOffset.Zero) // the selection while it's dragged
        private set
    var poly by mutableStateOf<Path?>(null, neverEqualPolicy()) // Polygon: the line through the vertices — one Path, rebuilt and re-set on each change to redraw
        private set
    private val corners = ArrayList<Offset>()
    val firstVertex get() = corners.firstOrNull() // changes only together with [poly]
    private var adding = false // the last vertex follows the finger
    private var undoable by mutableIntStateOf(0) // placed vertices that can be undone
    private val undone = mutableStateListOf<Offset>() // the latest last; a new vertex clears it
    val canUndoVertex get() = undoable > 0
    val canRedoVertex get() = undone.isNotEmpty()

    private var on = false
    private var mode = SelectMode.New
    private var base: Selection? = null
    private var dragged: Selection? = null
    private var p0 = Offset.Zero
    private var reach = 0f
    private var last = Offset.Zero // the lasso's last point
    private var rect = Rect()

    /** The current selection is hidden while the shape that will replace it is drawn. The live mode: a polygon spans many touches */
    val replacing get() = (shape != null || trace != null || poly != null) && o.mode.value == SelectMode.New

    /** Since [begin]: the touch moves the selection, it doesn't draw a shape */
    val moves get() = dragged != null

    /** [reach] — CLOSE_REACH in canvas px */
    fun begin(p: Offset, sel: Selection?, reach: Float) {
        on = idle()
        mode = o.mode.value
        base = sel
        // An unfinished polygon takes every touch
        dragged = sel?.takeIf { mode == SelectMode.New && poly == null && it.region.contains(floor(p.x).toInt(), floor(p.y).toInt()) }
        p0 = p
        this.reach = reach
    }

    fun drag(p: Offset) {
        if (!on) return
        if (dragged != null) {
            shift = IntOffset((p.x - p0.x).roundToInt(), (p.y - p0.y).roundToInt())
            return
        }
        if (o.style.value == SelectStyle.Wand || o.style.value == SelectStyle.Brush) return
        if (o.style.value == SelectStyle.Polygon) {
            if (corners.isEmpty()) return
            if (adding) corners[corners.lastIndex] = p else { corners += p; adding = true }
            redrawPoly()
            return
        }
        if (o.style.value == SelectStyle.Lasso) {
            val t = trace ?: Path().apply { moveTo(p0.x, p0.y) }.also { last = p0 }
            if ((p - last).getDistance() < 1f) return // nearer than a canvas px adds no pixels, only edges
            t.lineTo(p.x, p.y)
            last = p
            trace = t
            return
        }
        // Corners on the nearest canvas pixel boundaries; 1:1 — the longer side both ways, toward the finger
        val x0 = p0.x.roundToInt()
        val y0 = p0.y.roundToInt()
        var dx = p.x.roundToInt() - x0
        var dy = p.y.roundToInt() - y0
        if (o.fixedRatio.value) {
            val side = maxOf(abs(dx), abs(dy))
            dx = if (p.x < p0.x) -side else side
            dy = if (p.y < p0.y) -side else side
        }
        val r = Rect(x0, y0, x0 + dx, y0 + dy).apply { sort() }
        if (r == rect) return
        rect = r
        val f = RectF(r)
        val rad = o.corner.value.toFloat()
        // Circle: an oval inscribed in the frame
        val path = Path().apply { if (o.style.value == SelectStyle.Circle) addOval(f, Path.Direction.CW) else addRoundRect(f, rad, rad, Path.Direction.CW) }
        shape = path.toRegion(cw, ch).toSelection()
        shapePath = path
    }

    /** The finger lifted; [tap] — it didn't move. A shape's or a moved selection's outline is reused, not rebuilt */
    fun finish(tap: Boolean) {
        if (!on) return
        val d = dragged
        val style = o.style.value
        val polygon = style == SelectStyle.Polygon
        when {
            polygon && (adding || tap) -> { // a tap without [adding] places the first vertex
                val p = if (adding) corners.removeAt(corners.lastIndex) else p0
                adding = false
                val closes = corners.isNotEmpty() && (p - corners[0]).getDistance() <= reach
                if (!closes) {
                    corners += p
                    undoable = minOf(undoable + 1, POLY_STEPS)
                    undone.clear()
                }
                redrawPoly()
                if (closes && corners.size >= 3) poly?.let { dropPolygon(); commit(it) } // fewer vertices enclose nothing
            }
            d == null && style == SelectStyle.Brush -> Unit // BrushTool's stroke
            tap -> {
                val x = floor(p0.x).toInt()
                val y = floor(p0.y).toInt()
                // Off the canvas or on the selection it may move ([dragged]) the wand's tap deselects, as any other style's
                if (style == SelectStyle.Wand && d == null && x in 0 until cw && y in 0 until ch) onWand(x, y, o.tolerance.value, o.contiguous.value, mode.op) else onSelect(null)
            }
            d != null -> if (shift != IntOffset.Zero) onSelect(d.moved(shift.x, shift.y))
            !polygon && style != SelectStyle.Wand -> commit(trace)
        }
    }

    /** [path] closes itself: from the last point straight back to the first. Crossing loops are all inside (non-zero winding). Without it — the drawn [shape].
    A shape's or a polygon's own path goes along within the canvas (Selection.drawn), joined as the px are; a lasso's doesn't: grown, its hand-made corners would turn spikes */
    private fun commit(path: Path?) {
        val traced = path?.let { it.close(); it.toRegion(cw, ch) }?.takeUnless { it.isEmpty }
        val own = (if (path == null) shapePath else path.takeIf { o.style.value == SelectStyle.Polygon })?.clipped(Rect(0, 0, cw, ch))
        if (mode == SelectMode.New) return onSelect(traced?.let { Selection(it, drawn = own) } ?: shape?.let { Selection(it.region, it.outline, drawn = own) })
        val s = traced ?: shape?.region ?: return
        val b = base
        val d = if (b == null) own.takeIf { mode == SelectMode.Add } else own?.let { b.drawn?.opped(it, if (mode == SelectMode.Add) Path.Op.UNION else Path.Op.DIFFERENCE) }
        onSelect(Region(b?.region ?: Region()).apply { op(s, mode.op) }.toSelection(d))
    }

    private fun redrawPoly() {
        poly = if (corners.isEmpty()) null else (poly ?: Path()).apply { rewind(); corners.forEachIndexed { i, c -> if (i == 0) moveTo(c.x, c.y) else lineTo(c.x, c.y) } }
    }

    /** Ignored while a vertex follows the finger */
    fun undoVertex() {
        if (adding || undoable == 0) return
        undone += corners.removeAt(corners.lastIndex)
        undoable--
        redrawPoly()
    }

    fun redoVertex() {
        if (adding || undone.isEmpty()) return
        corners += undone.removeAt(undone.lastIndex)
        undoable++
        redrawPoly()
    }

    fun dropPolygon() {
        poly = null
        corners.clear()
        adding = false
        undoable = 0
        undone.clear()
    }

    /** Also drops the vertex that followed the finger: the touch turned out a pinch */
    fun cancel() {
        shape = null
        shapePath = null
        trace = null
        shift = IntOffset.Zero
        rect = Rect()
        if (adding) {
            adding = false
            corners.removeAt(corners.lastIndex)
            redrawPoly()
        }
    }
}
