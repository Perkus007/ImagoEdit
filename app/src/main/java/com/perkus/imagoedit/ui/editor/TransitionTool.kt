package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.Figure
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.MaskOverlay
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.asPath
import com.perkus.imagoedit.data.canvasToFile
import com.perkus.imagoedit.data.keepPaint
import com.perkus.imagoedit.data.saveClipLayer
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.PopoverCard
import com.perkus.imagoedit.ui.common.consumeTaps
import com.perkus.imagoedit.ui.common.rememberCheckerBrush
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

// pts: Linear — 0 и 1; Radial — центр, конец первой полуоси (радиус), конец второй (форма)
private const val CENTER = 2
private const val RADIUS = 3
private const val SIDE = 4
private const val MIN_AXIS = 1f // px холста: короче полуось не задаёт направление
private val POINT_R = 6.dp
private val POINT_HIT = 16.dp // радиус касания точки
private val HALO_W = 3.dp // тёмная подложка линий: видны на любом фоне
private val STRIP_W = 200.dp
private val STRIP_H = 16.dp
private val BTN = 32.dp // = ToolBtn
private val CHECKER = 4.dp
private val PANEL_W = STRIP_W + rowWidth(7) + 32.dp // = BottomPanel: полоса, отступ 16, 7 кнопок, поля 2·8
private val guidePaint = Paint().apply { isAntiAlias = true } // main thread only

/** name is persisted */
enum class TransitionMode(val icon: ImageVector) { Linear(AppIcons.Linear), Radial(AppIcons.Radial) }

/** Persisted at once. Opacity, %: [start] — at point 1 / the center, [end] — at point 2 / the edge */
@Stable
class TransitionOptions(settings: AppSettings) {
    val mode = EnumOption(settings, "transition.mode", TransitionMode.entries)
    val snapOff = BoolOption(settings, "transition.snapOff", false)
    val start = IntOption(settings, "transition.start", 0..MAX_OPACITY, MAX_OPACITY)
    val end = IntOption(settings, "transition.end", 0..MAX_OPACITY, 0)
}

/** [pts], canvas px: Linear — 0 (start), 1 (end); Radial — CENTER, RADIUS (the first semi-axis' end), SIDE (the second's, perpendicular) */
data class TransitionStep(val mode: TransitionMode, val start: Int, val end: Int, val pts: List<Offset>)

private fun erased(opacity: Int) = ((MAX_OPACITY - opacity) * 255f / MAX_OPACITY).roundToInt() shl 24

private fun Offset.dot(o: Offset) = x * o.x + y * o.y

/** Turned 90° */
private val Offset.normal get() = Offset(-y, x)

/** The erased part (1 − opacity) as alpha from the start to the end, clamped past them; Radial — over the ellipse from its center */
private fun TransitionStep.shader(): Shader {
    if (mode == TransitionMode.Linear) return LinearGradient(pts[0].x, pts[0].y, pts[1].x, pts[1].y, erased(start), erased(end), Shader.TileMode.CLAMP)
    val c = pts[CENTER]
    val u = pts[RADIUS] - c
    val m = Matrix().apply {
        setScale(u.getDistance().coerceAtLeast(MIN_AXIS), (pts[SIDE] - c).getDistance().coerceAtLeast(MIN_AXIS))
        postRotate(atan2(u.y, u.x) / DEG)
        postTranslate(c.x, c.y)
    }
    return RadialGradient(0f, 0f, 1f, erased(start), erased(end), Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
}

/** Transition on layer [l] (with an image): its opacity by a gradient within [sel] (null — all of it). Points are dragged one by one, a drag off them moves all.
Previewed in the layer (MaskOverlay), while points are dragged — as before the drag; ✓ erases it as a brush stroke. [blocked] — a touch then only closes a popover.
A step: mode, opacities, points */
@Stable
class TransitionTool(l: Layer, val options: TransitionOptions, cw: Int, ch: Int, private val sel: Selection?, private val blocked: () -> Boolean) :
    CanvasTool<TransitionStep>(), MaskOverlay {
    val layer = l.id
    private val file = l.file
    private val width = cw.toFloat()
    private val height = ch.toFloat()
    private val rect = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    private val toFile = l.canvasToFile()

    // Around the layer's part on the canvas
    private var pts by mutableStateOf(RectF(0f, 0f, width, height).run {
        intersect(RectF(rect))
        val r = minOf(width(), height()) / 4
        listOf(Offset(left + width() / 4, centerY()), Offset(right - width() / 4, centerY()), Offset(centerX(), centerY()), Offset(centerX() + r, centerY()), Offset(centerX(), centerY() - r))
    })
    private var held by mutableStateOf<TransitionStep?>(null) // the preview's during a drag
    private var start = pts
    private var grab = IDLE

    override var snapOff by options.snapOff::value

    override var step: TransitionStep
        get() = TransitionStep(options.mode.value, options.start.value, options.end.value, pts)
        set(v) {
            options.mode.value = v.mode
            options.start.value = v.start
            options.end.value = v.end
            pts = v.pts
        }

    private val handles get() = if (options.mode.value == TransitionMode.Linear) 0..1 else CENTER..SIDE

    fun switchMode() = edit { options.mode.value = if (options.mode.value == TransitionMode.Linear) TransitionMode.Radial else TransitionMode.Linear }

    /** The points swap places: the opacities swap */
    fun invert() = edit { options.start.value = options.end.value.also { options.end.value = options.start.value } }

    private fun opacity(start: Boolean) = if (start) options.start else options.end

    /** A slider's drag: one step on finish */
    fun slideOpacity(start: Boolean, v: Int) = hold { opacity(start).value = v }

    fun setOpacity(start: Boolean, v: Int) = edit { opacity(start).value = v }

    override fun begin(down: Offset, scale: Float, d: Density) {
        super.begin(down, scale, d)
        start = pts
        grab = if (blocked()) IDLE else hit(down, with(d) { POINT_HIT.toPx() })
    }

    /** The nearest point within [r] screen px; the center or none — all move */
    private fun hit(p: Offset, r: Float): Int {
        var best = MOVE
        var near = r
        for (i in handles) {
            val dist = (p - pts[i] * scale).getDistance()
            if (dist < near) { near = dist; best = i }
        }
        return if (best == CENTER) MOVE else best
    }

    /** From the start points and absolute positions: snapping doesn't stick */
    override fun drag(p: Offset) {
        if (grab == IDLE) return
        if (held == null) held = step
        val d = p / scale - p0
        val snap = if (snapOff) 0f else thr
        val c = start[CENTER]
        pts = when (grab) {
            MOVE -> moved(d, snap)
            RADIUS -> {
                val r = snapped(start[RADIUS] + d, snap)
                val u = r - c
                if (u.getDistance() < MIN_AXIS) return
                // The second semi-axis turns along and keeps its share of the first: a circle stays a circle
                val u0 = start[RADIUS] - c
                val k = (start[SIDE] - c).dot(u0.normal) / u0.getDistanceSquared()
                start.toMutableList().apply { this[RADIUS] = r; this[SIDE] = c + u.normal * k }
            }
            // Only along the second axis: stays on the ellipse
            SIDE -> {
                val n = (start[RADIUS] - c).normal
                start.toMutableList().apply { this[SIDE] = c + n * ((snapped(start[SIDE] + d, snap) - c).dot(n) / n.getDistanceSquared()) }
            }
            else -> start.toMutableList().apply { this[grab] = snapped(start[grab] + d, snap) }
        }
    }

    /** The mode's points by [d], then per axis by the least shift that snaps one of them */
    private fun moved(d: Offset, snap: Float): List<Offset> {
        val hs = handles
        var sx = 0f
        var sy = 0f
        for (i in hs) {
            val q = start[i] + d
            val s = snapped(q, snap) - q
            if (s.x != 0f && (sx == 0f || abs(s.x) < abs(sx))) sx = s.x
            if (s.y != 0f && (sy == 0f || abs(s.y) < abs(sy))) sy = s.y
        }
        val k = d + Offset(sx, sy)
        return start.mapIndexed { i, q -> if (i in hs) q + k else q }
    }

    /** Onto the canvas' middle lines, a corner or a side's middle within [snap] canvas px */
    private fun snapped(p: Offset, snap: Float): Offset {
        val x = snapTo(p.x, width, snap)
        val y = snapTo(p.y, height, snap)
        val ex = x == 0f || x == width
        val ey = y == 0f || y == height
        // An edge only where the other axis snapped too: a corner or a side's middle
        return Offset(if (ex && !ey && y != height / 2) p.x else x, if (ey && !ex && x != width / 2) p.y else y)
    }

    override fun finish() {
        held = null
        super.finish()
    }

    /** null — nothing to erase */
    fun stroke(): Stroke? {
        val v = step
        if (v.start == MAX_OPACITY && v.end == MAX_OPACITY) return null
        return Stroke(layer, file, null, area = sel, figure = Figure(rect.asPath(), shader = v::shader))
    }

    private val shade = Paint().apply { if (sel == null) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val keep = keepPaint().apply { isFilterBitmap = true }
    private val area = RectF(rect)
    private var shaded: TransitionStep? = null

    /** In its layer's offscreen, as the bake erases: the gradient by DST_OUT within the selection. Draw phase */
    override fun draw(c: Canvas, l: Layer, filter: Boolean, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, k: Float) {
        val v = held ?: step
        if (v != shaded) { shade.shader = v.shader(); shaded = v }
        c.save()
        c.concat(toFile)
        if (sel != null) {
            c.saveClipLayer(cut)
            val f = sel.feather
            if (f != null) c.clipRect(f.bounds) else c.clipPath(sel.outline)
        }
        c.drawRect(area, shade)
        if (sel != null) { sel.feather?.draw(c, keep); c.restore() }
        c.restore()
    }

    /** Native canvas: lines and the ellipse over a dark halo, then the points */
    override fun DrawScope.drawOverlay(s: Float) {
        val c = drawContext.canvas.nativeCanvas
        val p = pts
        val radial = options.mode.value == TransitionMode.Radial
        val from = p[if (radial) CENTER else 0] * s
        val to = p[if (radial) RADIUS else 1] * s
        val u = p[RADIUS] - p[CENTER]
        fun guides() {
            c.drawLine(from.x, from.y, to.x, to.y, guidePaint)
            if (!radial) return
            val a = u.getDistance() * s
            val b = (p[SIDE] - p[CENTER]).getDistance() * s
            c.save()
            c.translate(from.x, from.y)
            c.rotate(atan2(u.y, u.x) / DEG)
            c.drawOval(-a, -b, a, b, guidePaint)
            c.restore()
        }
        guidePaint.outline(HALO_W.toPx())
        guides()
        guidePaint.outline(HANDLE_LINE.toPx())
        guidePaint.color = android.graphics.Color.WHITE
        guides()
        val r = POINT_R.toPx()
        for (i in handles) {
            val q = p[i] * s
            guidePaint.fill(android.graphics.Color.WHITE)
            c.drawCircle(q.x, q.y, r, guidePaint)
            guidePaint.outline(HANDLE_LINE.toPx())
            c.drawCircle(q.x, q.y, r, guidePaint)
        }
    }
}

/** One row: the opacity strip, the mode (shows the current one, switches), Inversion, snapping, Undo/Redo, Cancel, ✓ */
@Composable
fun TransitionOverlay(s: TransitionTool, onPopup: (OpenPopup) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        val o = s.options
        BottomPanel(false, null, Modifier, buttons = {
            OpacityStrip(s, onPopup)
            ToolBtn(o.mode.value.icon, onClick = s::switchMode)
            ToolBtn(AppIcons.ArrowRightLeft, onClick = s::invert)
            SnapHistoryButtons(s)
            ConfirmButtons(onCancel, onConfirm)
        }) {}
    }
}

private fun IntOption.red() = Color.Red.copy(alpha = value / MAX_OPACITY.toFloat())

/** Red by opacity over a checker from the start to the end; the buttons at its ends open their sliders. Read in the draw phase: follows the slider */
@Composable
private fun OpacityStrip(s: TransitionTool, onPopup: (OpenPopup) -> Unit) = Box(Modifier.width(STRIP_W).height(BTN), Alignment.Center) {
    val o = s.options
    val checker = rememberCheckerBrush(CHECKER)
    Box(Modifier.fillMaxWidth().padding(horizontal = BTN / 2).height(STRIP_H).drawBehind {
        drawRect(checker)
        drawRect(Brush.horizontalGradient(listOf(o.start.red(), o.end.red())))
    })
    for (start in listOf(true, false)) {
        RoundBtn(null, false, { (if (start) o.start else o.end).red() }, Modifier.align(if (start) Alignment.CenterStart else Alignment.CenterEnd).background(checker, CircleShape), BTN) {
            onPopup(OpenPopup(ToolPopup.Opacity, it) { OpacityPopover(s, start) })
        }
    }
}

/** Over the panel, as wide */
@Composable
private fun OpacityPopover(s: TransitionTool, start: Boolean) =
    PopoverCard(Modifier.fillMaxSize().padding(bottom = BAR_H + PANEL_GAP).wrapContentSize(Alignment.BottomCenter).consumeTaps().width(PANEL_W)) {
        Row(Modifier.fillMaxWidth()) {
            PopoverSlider(AnnotatedString(stringResource(R.string.opacity)), (if (start) s.options.start else s.options.end).value, 0..MAX_OPACITY, "%", s::finish,
                { s.slideOpacity(start, it) }) { s.setOpacity(start, it) }
        }
    }
