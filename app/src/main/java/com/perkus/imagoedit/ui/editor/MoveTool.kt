package com.perkus.imagoedit.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.toOpacity
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.theme.BarBg
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private const val ROTATE = 8
private const val MIN_SIZE = 1f // px холста

private val ROT_HIT = 14.dp // радиус касания значка поворота
private val ROT_DIST = 44.dp // от правого маркера до значка поворота, экранные dp
private val ROT_R = 14.dp
private val ROT_ICON = 24.dp
private const val ICON_VIEWPORT = 24f
private val rotIconPath = PathParser().addPathNodes((AppIcons.CurveArrow.root[0] as VectorPath).pathData).toPath().asAndroidPath() // рисуется своей кистью без сглаживания

private const val ROT_SNAP_DEG = 2f // °: прилипание к 0/90/180/270

// Screen px from the canvas origin
private fun ContentTransform.rotPoint(scale: Float, dist: Float) = handle(3) * scale + dir() * dist

private fun norm(a: Float): Float {
    val r = a % 360f
    return when { r > 180f -> r - 360f; r <= -180f -> r + 360f; else -> r }
}

private fun snapAngle(raw: Float, snap: Boolean): Float {
    if (snap) {
        val ortho = (raw / 90f).roundToInt() * 90f
        if (abs(raw - ortho) <= ROT_SNAP_DEG) return norm(ortho) // exact: the ortho check in resized() works
    }
    return norm(raw.roundToInt().toFloat())
}

/** A layer's content placed by its frame: handles, rotation, flips, nudges, opacity. [id] — the placed Layer.id (a new image's or text's future one), 0 — the selection.
[S] — a step: the frame, opacity and what the tool adds */
@Stable
abstract class PlaceState<S>(val id: Long, cw: Int, ch: Int, initial: ContentTransform, initialOpacity: Int) :
    FrameState<S>(cw, ch, initial, free = false, min = MIN_SIZE, inside = false) {
    var opacity by mutableIntStateOf(initialOpacity) // read in the draw phase; reaches the layer on ✓
        protected set
    var precise by mutableStateOf(false)

    val alpha get() = opacity / MAX_OPACITY.toFloat()

    /** Nothing to frame yet: no handles, a drag only moves */
    open val empty: Boolean get() = false

    /** The frame's sides at the original size; null — no double tap fit */
    protected open val original: Size? get() = null

    private var fitted: ContentTransform? = null // the last fit: rounding makes a refit of it unstable, so it's compared as is

    /** [w]×[h] centered, the bounds' left/top on whole px (as center snapping) */
    private fun centered(w: Float, h: Float): ContentTransform {
        val b = t.copy(cx = 0f, cy = 0f, w = w, h = h).bounds()
        return t.copy(cx = (cw / 2f + b.left).roundToInt() - b.left, cy = (ch / 2f + b.top).roundToInt() - b.top, w = w, h = h)
    }

    /** The bounds fit into the canvas, sides on whole px; on the last fit — the original size. Both centered, angle and flips kept */
    override fun doubleTap() {
        val o = original
        if (o == null || empty) return
        hold {
            t = if (t == fitted) centered(o.width, o.height) else {
                val b = t.bounds()
                val k = minOf(cw / b.width(), ch / b.height())
                centered((t.w * k).roundToInt().toFloat().coerceAtLeast(MIN_SIZE), (t.h * k).roundToInt().toFloat().coerceAtLeast(MIN_SIZE)).also { fitted = it }
            }
        }
    }

    override fun hit(p: Offset, scale: Float, d: Density): Int {
        if (precise || empty) return MOVE
        val rotR = with(d) { ROT_HIT.toPx() }
        if ((p - t.rotPoint(scale, with(d) { ROT_DIST.toPx() })).getDistance() <= rotR) return ROTATE
        return super.hit(p, scale, d)
    }

    override fun dragged(mode: Int, start: ContentTransform, p0: Offset, p: Offset, thr: Float): ContentTransform {
        if (mode != ROTATE) return super.dragged(mode, start, p0, p, thr)
        // Add the angle DELTA to the start angle: the icon doesn't jump under the finger
        val a0 = atan2(p0.y - start.cy, p0.x - start.cx)
        val a = atan2(p.y - start.cy, p.x - start.cx)
        return start.copy(angle = snapAngle(start.angle + (a - a0) / DEG, !snapOff))
    }

    fun nudge(dx: Float, dy: Float) = edit { t = t.copy(cx = t.cx + dx, cy = t.cy + dy) }

    /** One step on finish */
    fun slideOpacity(v: Int) = hold { opacity = v }

    // Flip around screen axes: the frame mirrors (angle negates), pixels via the flag
    fun flipH() = edit { t = t.copy(angle = norm(-t.angle), flipH = !t.flipH) }
    fun flipV() = edit { t = t.copy(angle = norm(-t.angle), flipV = !t.flipV) }

    override fun setField(f: FrameField, v: Float) = edit {
        when (f) {
            FrameField.X -> t = t.copy(cx = t.cx + v.coerceIn(-MAX_SIZE, MAX_SIZE) - t.bounds().left)
            FrameField.Y -> t = t.copy(cy = t.cy + v.coerceIn(-MAX_SIZE, MAX_SIZE) - t.bounds().top)
            FrameField.W -> (v / t.w).let { k -> resize(k, if (free) 1f else k) }
            FrameField.H -> (v / t.h).let { k -> resize(if (free) 1f else k, k) }
            FrameField.ANGLE -> t = t.copy(angle = norm(v))
            FrameField.OPACITY -> opacity = v.toOpacity()
        }
    }

    /** Keeps the bounds' top-left: X and Y stay after W/H input */
    protected fun resize(kw: Float, kh: Float) {
        val old = t.bounds()
        val n = t.copy(w = (t.w * kw).coerceIn(MIN_SIZE, MAX_SIZE), h = (t.h * kh).coerceIn(MIN_SIZE, MAX_SIZE))
        val b = n.bounds()
        t = n.copy(cx = n.cx + old.left - b.left, cy = n.cy + old.top - b.top)
    }

    /** On the native canvas: crisp, not anti-aliased. None in the nudge mode or with nothing to frame */
    override fun DrawScope.drawOverlay(s: Float) {
        if (precise || empty) return
        drawHandles(t, s)
        val c = drawContext.canvas.nativeCanvas
        val rc = t.rotPoint(s, ROT_DIST.toPx())
        val r = ROT_R.toPx()
        overlayPaint.fill(android.graphics.Color.WHITE)
        c.drawCircle(rc.x, rc.y, r, overlayPaint)
        overlayPaint.outline(HANDLE_LINE.toPx())
        c.drawCircle(rc.x, rc.y, r, overlayPaint)
        // CurveArrow is centered in its viewport: align the squares' centers
        val side = ROT_ICON.toPx()
        c.save()
        c.translate(rc.x - side / 2, rc.y - side / 2)
        c.scale(side / ICON_VIEWPORT, side / ICON_VIEWPORT)
        overlayPaint.fill(BarBg.toArgb())
        c.drawPath(rotIconPath, overlayPaint)
        c.restore()
    }
}

/** Move on layer [layer] (index; an added image — its future one). A step: the frame and opacity */
@Stable
class MoveState(val layer: Int, id: Long, cw: Int, ch: Int, private val initial: ContentTransform, private val initialOpacity: Int = MAX_OPACITY) :
    PlaceState<Pair<ContentTransform, Int>>(id, cw, ch, initial, initialOpacity) {
    val changed get() = t != initial || opacity != initialOpacity

    override val original get() = Size(initial.w, initial.h)

    override var step: Pair<ContentTransform, Int>
        get() = t to opacity
        set(v) {
            t = v.first
            opacity = v.second
        }
}
