package com.perkus.imagoedit.ui.editor

import android.graphics.Paint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.MAX_SIDE
import com.perkus.imagoedit.ui.theme.BarBg
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

const val MOVE = -1 // жест «перенос»; 0..7 — индекс маркера размера
const val IDLE = -2 // жест не трогает рамку
const val DEG = 0.017453292f // радиан в градусе
const val MAX_SIZE = 4f * MAX_SIDE // максимальная сторона рамки (и предел для X/Y)
private const val MAX_FRAME_STEPS = 100 // лимит шагов Undo/Redo инструмента рамки, не связан с MAX_LIGHT_CHECKPOINTS истории проекта; шаги только в RAM и живут, пока открыт инструмент

private val HANDLE_HALF = 5.dp // половина стороны квадрата-маркера
val HANDLE_LINE = 1.dp // контур маркеров и значка поворота
private val HIT = 8.dp // радиус зоны касания маркера размера (диаметр 16dp)
private val SNAP = 6.dp // порог прилипания к краям и центру холста (на экране; в px холста = SNAP / zoom)
val overlayPaint = Paint().apply { isAntiAlias = false } // маркеры и значок поворота; один объект на все кадры, без аллокаций в draw

// 8 маркеров по часовой от левого верхнего: (sx, sy) → позиция (sx·w/2, sy·h/2) в осях рамки. Чётные индексы — углы, нечётные — середины рёбер; 3 = правая середина (от неё идёт значок поворота)
private val DIR = intArrayOf(-1, -1, 0, -1, 1, -1, 1, 0, 1, 1, 0, 1, -1, 1, -1, 0)

fun ContentTransform.dir() = Offset(cos(angle * DEG), sin(angle * DEG))

private fun ContentTransform.at(sx: Float, sy: Float): Offset {
    val u = dir()
    val x = sx * w / 2
    val y = sy * h / 2
    return Offset(cx + u.x * x - u.y * y, cy + u.y * x + u.x * y)
}

fun ContentTransform.handle(i: Int) = at(DIR[2 * i].toFloat(), DIR[2 * i + 1].toFloat())

private fun snapTo(v: Float, size: Float, thr: Float): Float {
    val edge = if (abs(v) <= abs(v - size)) 0f else size
    val p = if (abs(v - size / 2) < abs(v - edge)) size / 2 else edge
    return if (abs(v - p) <= thr) p else v
}

private fun snapShift(lo: Float, hi: Float, size: Float, thr: Float): Float {
    val e = if (abs(lo) <= abs(size - hi)) -lo else size - hi
    val c = ((size - (hi - lo)) / 2).roundToInt() - lo // to center: the left edge stays on a whole px so X/Y and sharpness don't suffer
    val s = if (abs(c) < abs(e)) c else e
    return if (abs(s) <= thr) s else 0f
}

/** The bounds' top-left (X/Y fields) always lands on whole canvas px unless edge snapping fired: it takes priority */
private fun ContentTransform.moved(d: Offset, snap: Float, cw: Float, ch: Float): ContentTransform {
    val m = copy(cx = cx + d.x, cy = cy + d.y)
    val b = m.bounds()
    // snapShift = 0 both when there's nothing to snap to and when the threshold is 0 → round the bounds corner to an integer either way
    val sx = snapShift(b.left, b.right, cw, snap).takeIf { it != 0f } ?: (b.left.roundToInt() - b.left)
    val sy = snapShift(b.top, b.bottom, ch, snap).takeIf { it != 0f } ?: (b.top.roundToInt() - b.top)
    return m.copy(cx = m.cx + sx, cy = m.cy + sy)
}

/** Room along an axis of [size] for a side growing from the anchor's coordinate [a] toward [s]; s = 0 — both ways from the center */
private fun room(s: Int, a: Float, size: Float) = when {
    s > 0 -> size - a
    s < 0 -> a
    else -> 2 * minOf(a, size - a)
}

/** The opposite handle (anchor) stays put. [prop] — keep the current frame's proportions; [snap] — threshold, 0 = off; [min] — the least side.
[inside] — an unrotated frame on the canvas stays on it: the dragged sides stop at its edges */
private fun ContentTransform.resized(i: Int, target: Offset, prop: Boolean, snap: Float, cw: Float, ch: Float, min: Float, inside: Boolean): ContentTransform {
    val sx = DIR[2 * i]
    val sy = DIR[2 * i + 1]
    val anchor = at(-sx.toFloat(), -sy.toFloat())
    val u = dir()
    val v = Offset(-u.y, u.x)
    // Snapping to canvas edges only at multiples of 90°: otherwise the dragged edge isn't axis-aligned
    val ortho = snap > 0f && axisAligned
    val nw: Float
    val nh: Float
    if (prop) {
        val vx = u.x * sx * w + v.x * sy * h
        val vy = u.y * sx * w + v.y * sy * h
        val k0 = ((target.x - anchor.x) * vx + (target.y - anchor.y) * vy) / (vx * vx + vy * vy)
        var k = k0
        if (ortho) {
            var best = snap + 1f
            for (axis in 0..1) {
                val a = if (axis == 0) anchor.x else anchor.y
                val vc = if (axis == 0) vx else vy
                if (abs(vc) < 1e-3f) continue
                val pos = a + k0 * vc
                val edge = snapTo(pos, if (axis == 0) cw else ch, snap)
                val dist = abs(edge - pos)
                if (dist > 0f && dist < best) { best = dist; k = (edge - a) / vc }
            }
        }
        // One factor for both sides: the proportions hold at the limits too
        if (inside) k = k.coerceIn(maxOf(min / w, min / h), minOf(room(sx, anchor.x, cw) / w, room(sy, anchor.y, ch) / h))
        nw = w * k
        nh = h * k
    } else {
        val tx = (if (ortho) snapTo(target.x, cw, snap) else target.x).let { if (inside) it.coerceIn(0f, cw) else it }
        val ty = (if (ortho) snapTo(target.y, ch, snap) else target.y).let { if (inside) it.coerceIn(0f, ch) else it }
        val dx = tx - anchor.x
        val dy = ty - anchor.y
        nw = if (sx != 0) sx * (dx * u.x + dy * u.y) else w
        nh = if (sy != 0) sy * (dx * v.x + dy * v.y) else h
    }
    // Sides are always whole canvas px. Rounded BEFORE computing the center: the anchor stays fixed, the handle lands exactly on an integer
    val w2 = nw.coerceIn(min, MAX_SIZE).roundToInt().toFloat()
    val h2 = nh.coerceIn(min, MAX_SIZE).roundToInt().toFloat()
    val ox = sx * w2 / 2
    val oy = sy * h2 / 2
    return copy(cx = anchor.x + u.x * ox + v.x * oy, cy = anchor.y + u.y * ox + v.y * oy, w = w2, h = h2)
}

/** An unrotated frame whose whole-px sides fit the canvas: moved onto it, its top-left on whole px */
private fun ContentTransform.inCanvas(cw: Int, ch: Int): ContentTransform {
    val x = (cx - w / 2).roundToInt().coerceIn(0, cw - w.roundToInt())
    val y = (cy - h / 2).roundToInt().coerceIn(0, ch - h.roundToInt())
    return copy(cx = x + w / 2, cy = y + h / 2)
}

/** Fields of the frame tools' second row */
enum class FrameField { X, Y, W, H, ANGLE, OPACITY }

/** A frame tool's persisted toggles under [tool]'s keys: snapping and the unfolded fields row */
@Stable
class FrameOptions(settings: AppSettings, tool: String) {
    val snapOff = BoolOption(settings, "$tool.snapOff", false)
    val expanded = BoolOption(settings, "$tool.expanded", false)
}

/** A frame [t] on the canvas [cw]×[ch]: dragged by its 8 handles or as a whole, the X/Y/W/H fields, the tool session's own Undo/Redo.
[S] — a step: the tool's whole state, compared by value. [min] — the least side, canvas px; [inside] — an unrotated frame that stays on the canvas, its sides and top-left on whole px */
@Stable
abstract class FrameState<S>(protected val cw: Int, protected val ch: Int, initial: ContentTransform, free: Boolean, private val min: Float, private val inside: Boolean) {
    var t by mutableStateOf(initial)
        protected set
    var free by mutableStateOf(free) // free aspect ratio
    var snapOff by mutableStateOf(false)
    var expanded by mutableStateOf(false)

    /** The tool's whole state now: Undo/Redo saves and restores it */
    protected abstract var step: S

    // Read from composition (button state) → Compose state
    private val past = mutableStateListOf<S>()
    private val future = mutableStateListOf<S>()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    // The gesture under way, fixed at the touch
    private var start = initial
    private var before: S? = null
    private var p0 = Offset.Zero
    private var mode = MOVE
    private var scale = 1f
    private var thr = 0f

    private fun record(before: S) {
        if (before == step) return
        future.clear()
        past.add(before)
        if (past.size > MAX_FRAME_STEPS) past.removeAt(0)
    }

    /** [change] = one step */
    protected fun edit(change: () -> Unit) {
        val before = step
        change()
        record(before)
    }

    /** [change] — part of one under way (a slider's drag): the whole of it = one step, recorded by finish */
    protected fun hold(change: () -> Unit) {
        if (before == null) before = step
        change()
    }

    fun undo() = travel(past, future)

    fun redo() = travel(future, past)

    private fun travel(from: MutableList<S>, to: MutableList<S>) {
        val s = from.removeLastOrNull() ?: return
        to.add(step)
        step = s
    }

    abstract fun setField(f: FrameField, v: Float)

    /** The toggles as [o] keeps them */
    fun restore(o: FrameOptions) {
        snapOff = o.snapOff.value
        expanded = o.expanded.value
    }

    /** The toggles into [o] */
    fun keep(o: FrameOptions) {
        o.snapOff.value = snapOff
        o.expanded.value = expanded
    }

    /** Over the picture: the canvas origin at (0, 0), [s] screen px per canvas px */
    abstract fun DrawScope.drawOverlay(s: Float)

    /** [r] within the limits */
    protected fun limited(r: ContentTransform) = if (inside) r.inCanvas(cw, ch) else r

    /** [from] with handle [i] dragged to [target], canvas px; [snap] — threshold, 0 = off */
    protected fun resizedTo(from: ContentTransform, i: Int, target: Offset, snap: Float) =
        limited(from.resized(i, target, !free, snap, cw.toFloat(), ch.toFloat(), min, inside))

    /** [p] — screen px from the canvas origin. Neighboring handle zones may overlap (small frame) — the nearest wins */
    protected open fun hit(p: Offset, scale: Float, d: Density): Int {
        val r = with(d) { HIT.toPx() }
        var best = MOVE
        var near = r
        for (i in 0..7) {
            val dist = (p - t.handle(i) * scale).getDistance()
            if (dist < near) { near = dist; best = i }
        }
        return best
    }

    /** The frame so far. Everything is computed from the START frame [start] and absolute points [p0] (touch down) and [p] (now), canvas px:
    errors don't accumulate and snapping doesn't "stick" (computed from the raw position, not the already snapped one) */
    protected open fun dragged(mode: Int, start: ContentTransform, p0: Offset, p: Offset, thr: Float): ContentTransform {
        val snap = if (snapOff) 0f else thr
        return if (mode == MOVE) limited(start.moved(p - p0, snap, cw.toFloat(), ch.toFloat()))
        else resizedTo(start, mode, p + start.handle(mode) - p0, snap) // keep the handle where it was grabbed: target = finger + (handle − touch point)
    }

    /** Type (handle / move / the tool's own) is fixed by the touch point; [down] — screen px from the canvas origin, [scale] screen px per canvas px */
    fun begin(down: Offset, scale: Float, d: Density) {
        start = t
        before = step
        p0 = down / scale
        mode = hit(down, scale, d)
        this.scale = scale
        thr = with(d) { SNAP.toPx() } / scale
    }

    /** [p] — screen px from the canvas origin */
    fun drag(p: Offset) {
        if (mode != IDLE) t = dragged(mode, start, p0, p / scale, thr)
    }

    /** The whole gesture (or hold) = one step */
    fun finish() {
        before?.let { record(it) }
        before = null
    }
}

/** On the native canvas: Compose draws anti-aliased only, these are crisp */
fun DrawScope.drawHandles(t: ContentTransform, s: Float) {
    val line = HANDLE_LINE.toPx()
    val half = HANDLE_HALF.toPx()
    val c = drawContext.canvas.nativeCanvas
    for (i in 0..7) {
        val h = t.handle(i) * s
        overlayPaint.fill(android.graphics.Color.WHITE)
        c.drawRect(h.x - half, h.y - half, h.x + half, h.y + half, overlayPaint)
        overlayPaint.outline(line)
        c.drawRect(h.x - half, h.y - half, h.x + half, h.y + half, overlayPaint)
    }
}

fun Paint.fill(argb: Int) {
    style = Paint.Style.FILL
    color = argb
}

fun Paint.outline(width: Float) {
    style = Paint.Style.STROKE
    strokeWidth = width
    color = BarBg.toArgb()
}
