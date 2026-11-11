package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.ContentBox
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.MAX_FEATHER
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.blurredEdge
import com.perkus.imagoedit.data.grownBounds
import com.perkus.imagoedit.data.grownEdge
import com.perkus.imagoedit.data.grownOutline
import com.perkus.imagoedit.data.smoothedOutline
import com.perkus.imagoedit.data.toCanvas
import com.perkus.imagoedit.ui.common.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

private const val MIN_GROW = -512 // сужение контура, px выделения
private const val MAX_GROW = 1000
private const val FOLDED = 2 // рядов, пока шеврон не раскрыл ряды 3 и 4
private const val UNFOLDED = 4
private val ROW_W = rowWidth(10) // вровень с рядом 1: 10 кнопок
private const val PREVIEW_PIXELS = 1 shl 20 // предпросмотр считается в копии не больше стольких px
private val tintPaint = Paint().apply { color = 0x80FF0000.toInt(); isAntiAlias = false }
private val cutPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = true; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

/** Before the frame, selection px: grown by [grow], smoothed by [smooth], edge blurred by [blur]; 0 — not */
data class Edge(val grow: Int = 0, val blur: Int = 0, val smooth: Int = 0)

/** Persisted: the frame's toggles and the last ✓'s edge — the next open starts from it */
@Stable
class TransformOptions(settings: AppSettings) {
    val frame = FrameOptions(settings, "transform")
    private val grow = IntOption(settings, "transform.grow", MIN_GROW..MAX_GROW, 0)
    private val blur = IntOption(settings, "transform.blur", 0..MAX_FEATHER, 0)
    private val smooth = IntOption(settings, "transform.smooth", 0..MAX_FEATHER, 0)
    var edge: Edge
        get() = Edge(grow.value, blur.value, smooth.value)
        set(e) {
            grow.value = e.grow
            blur.value = e.blur
            smooth.value = e.smooth
        }
}

/** The selection as its Edge makes it, before the frame: outline and a soft edge's coverage with its canvas rect */
private class Edged(val outline: Path, val edge: Pair<Bitmap, Rect>?)

/** The canvas tinted outside what [cut] draws */
private fun DrawScope.drawOutside(offset: Offset, scale: Float, cw: Int, ch: Int, cut: android.graphics.Canvas.(Paint) -> Unit) {
    val c = drawContext.canvas.nativeCanvas
    c.save()
    c.translate(offset.x, offset.y)
    c.scale(scale, scale)
    c.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), tintPaint)
    c.cut(cutPaint)
    c.restore()
}

/** The selection [sel] changed by its [edge] and placed by the frame as Move places a layer; the frame frames its grown bounds ([src]). A step: the frame and the edge */
@Stable
class TransformState(cw: Int, ch: Int, private val sel: Selection, private val options: TransformOptions) :
    PlaceState<Pair<ContentTransform, Edge>>(0L, cw, ch, sel.region.bounds.let { ContentTransform.initial(ContentBox(), it.left, it.top, it.width(), it.height()) }, MAX_OPACITY),
    SelectionPreview {
    private val bounds = sel.region.bounds
    private val initial = t
    var edge by mutableStateOf(Edge())
        private set
    private val own = Edged(sel.outline, sel.feather?.let { it.alpha to it.bounds })
    private var shape by mutableStateOf(own)

    val changed get() = t != initial || edge != Edge()

    // [src] for the growth it was found for: a drawn shape's takes a path op
    private var srcGrow = 0
    private var srcRect = bounds

    // After srcGrow/srcRect: reedge reads src. The saved edge is the start, not a step
    init {
        restore(options.frame)
        reedge(options.edge)
    }

    /** Where the selection grown by its growth lies */
    val src: Rect
        get() {
            val g = edge.grow
            if (g != srcGrow) {
                srcRect = sel.grownBounds(g)
                srcGrow = g
            }
            return srcRect
        }

    override var step: Pair<ContentTransform, Edge>
        get() = t to edge
        set(v) {
            t = v.first
            edge = v.second
        }

    /** Each value within its limits: one step */
    fun edgeTo(e: Edge) = edit { reedge(e) }

    /** A slider's drag: one step on finish */
    fun slideEdge(e: Edge) = hold { reedge(e) }

    /** A new growth moves the frame onto the grown bounds: the selection grows where it is */
    private fun reedge(e: Edge) {
        val old = src
        edge = Edge(e.grow.coerceIn(MIN_GROW, MAX_GROW), e.blur.coerceIn(0, MAX_FEATHER), e.smooth.coerceIn(0, MAX_FEATHER))
        src.let { if (it != old) t = t.framing(old, it) }
    }

    fun save() = keep(options.frame)

    /** While running: the shape for the latest edge on a copy ≤ PREVIEW_PIXELS; regrown only for a new growth, resmoothed only for a new growth or smoothing */
    suspend fun preview() {
        var at = Edge()
        var grown = sel.outline
        var smoothed = grown
        snapshotFlow { edge }.conflate().collect { e ->
            shape = if (e == Edge()) own else withContext(Dispatchers.Default) {
                if (e.grow != at.grow) grown = if (e.grow == 0) sel.outline else sel.grownOutline(e.grow, PREVIEW_PIXELS)
                if (e.grow != at.grow || e.smooth != at.smooth) smoothed = if (e.smooth == 0) grown else grown.smoothedOutline(e.smooth, PREVIEW_PIXELS) ?: grown
                at = e
                Edged(smoothed, when {
                    e.blur > 0 -> smoothed.blurredEdge(e.blur, PREVIEW_PIXELS)
                    e.smooth > 0 -> null // smoothing makes the edge hard
                    else -> sel.grownEdge(grown, e.grow, PREVIEW_PIXELS)
                })
            }
        }
    }

    // The placed shape (outline, soft edge px → canvas px), rebuilt in the draw phase only when the frame, growth or shape changes
    private val placing = Matrix()
    private val edgePlacing = Matrix()
    private val placed = Path()
    private var builtT: ContentTransform? = null
    private var builtGrow = 0
    private var builtShape: Edged? = null

    private fun build(): Edged {
        val sh = shape
        val f = t
        val g = edge.grow
        if (f !== builtT || g != builtGrow || sh !== builtShape) {
            val r = src
            placing.set(f.matrix(r.width(), r.height(), 0))
            placing.preTranslate(-r.left.toFloat(), -r.top.toFloat())
            sh.outline.transform(placing, placed)
            sh.edge?.let { (b, at) -> edgePlacing.set(b.toCanvas(at)); edgePlacing.postConcat(placing) }
            builtT = f
            builtGrow = g
            builtShape = sh
        }
        return sh
    }

    override fun outline(s: Selection): Path {
        build()
        return placed
    }

    override fun DrawScope.drawPreview(s: Selection, offset: Offset, scale: Float, cw: Int, ch: Int) = drawOutside(offset, scale, cw, ch) { p ->
        val e = build().edge
        if (e == null) drawPath(placed, p) else drawBitmap(e.first, edgePlacing, p)
    }
}

/** Move's panel without opacity: a growth row under the buttons; the chevron unfolds blur and smoothing over the fields */
@Composable
fun TransformOverlay(s: TransformState, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    if (s.precise) Nudges(s, if (s.expanded) UNFOLDED else FOLDED)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        FramePanel(s, Modifier, extra = { PlaceButtons(s) }, extraFields = { AngleField(s) }, fieldsGap = 16.dp, more = {
            EdgeSlider(s, { FieldText(stringResource(R.string.expand)) }, s.edge.grow, MIN_GROW..MAX_GROW, Modifier.width(ROW_W)) { copy(grow = it) }
        }, unfolded = {
            Row(Modifier.width(ROW_W), Arrangement.spacedBy(16.dp)) {
                EdgeSlider(s, { FieldIcon(AppIcons.Opacity, Modifier.rotate(180f)) }, s.edge.blur, 0..MAX_FEATHER, Modifier.weight(1f)) { copy(blur = it) }
                EdgeSlider(s, { FieldIcon(AppIcons.WaveToStraightLine) }, s.edge.smooth, 0..MAX_FEATHER, Modifier.weight(1f)) { copy(smooth = it) }
            }
        }, onCancel = onCancel, onConfirm = onConfirm)
    }
}

/** One edge value: [label], field and slider; [set] — the edge with it */
@Composable
private fun EdgeSlider(s: TransformState, label: @Composable () -> Unit, value: Int, range: IntRange, modifier: Modifier, set: Edge.(Int) -> Edge) =
    PanelSlider(label, value, range, modifier, onFinished = s::finish, onChange = { s.slideEdge(s.edge.set(it)) }) { s.edgeTo(s.edge.set(it)) }
