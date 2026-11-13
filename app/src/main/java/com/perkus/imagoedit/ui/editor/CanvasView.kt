package com.perkus.imagoedit.ui.editor

import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import com.perkus.imagoedit.data.Blend
import com.perkus.imagoedit.data.LayerOverlay
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.alphaAt
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.SIDEBAR_W
import com.perkus.imagoedit.ui.common.drawChecker
import com.perkus.imagoedit.ui.common.rememberCheckerBrush
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.SmallShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor
import kotlin.math.roundToInt

private const val PCT = 100f // 1.0 = 100%
private const val MIN_SCALE = 1f / PCT // zoomPct 0 = плашка скрыта
private const val REST_DETAIL = 0.70710677f // 1/√2: копия растянута или сжата не больше √2× — вдвое меньше чтения на кадр
private const val ZOOM_DETAIL = 0.5f // при зуме все слои перерисовываются каждый кадр: копия не больше экрана
class Viewport {
    private var fit = 1f
    private var area = Rect.Zero
    var scale by mutableFloatStateOf(1f) // screen px per canvas px
        private set
    var offset by mutableStateOf(Offset.Zero) // canvas top-left on screen
        private set
    var zoomPct by mutableIntStateOf(0) // > 0 only while zooming: badge shown, coarser copies drawn

    /** [area] — between the bars; the canvas keeps [margin] from its edges */
    fun fitInto(area: Rect, margin: Float, canvasW: Int, canvasH: Int) {
        this.area = area.deflate(margin)
        refit(canvasW, canvasH)
    }

    /** Into the last area: also for a new canvas size */
    fun refit(canvasW: Int, canvasH: Int) {
        fit = minOf(area.width / canvasW, area.height / canvasH).coerceAtLeast(MIN_SCALE)
        scale = fit
        offset = Offset(centered(area.left, area.width, canvasW * fit), centered(area.top, area.height, canvasH * fit))
    }

    /** Start of [len] centered in [room] from [from], both ends ≥ ¼ px off screen px centers: an edge on a center is a tie the screen and an offscreen layer
    (a stroke's, drawMasked) may break apart — a 1 px line */
    private fun centered(from: Float, room: Float, len: Float): Float {
        val f = len - floor(len)
        val a = if (f <= 0.5f) -f / 2 else (1 - f) / 2
        return (from + (room - len) / 2 - a).roundToInt() + a
    }

    fun pinch(zoom: Float, centroid: Offset, pan: Offset) {
        val s = (scale * zoom).coerceIn(maxOf(fit / 4, MIN_SCALE), maxOf(fit, 32f))
        offset = centroid - (centroid - offset) * (s / scale) + pan
        scale = s
        zoomPct = (s * PCT).roundToInt()
    }
}

/** Shown layers around the hot one */
enum class Part { Below, Hot, Above }

/** What CanvasView shows; hoisted for the pipette. [size], [layers], [checker] hold the latest composition's values: the color panel may keep its instance */
@Stable
class CanvasPicture(private val size: State<IntSize>, private val layers: State<List<Drawn>>, private val levels: State<Levels>, private val preview: LayerPreview,
    private val checker: State<Brush>, private val strokes: BrushTool) : PipetteSource {
    val vp = Viewport()
    private val isolated = Paint()
    val canvasW get() = size.value.width
    val canvasH get() = size.value.height

    fun DrawScope.drawBoard(offset: Offset, scale: Float) = translate(offset.x, offset.y) { drawChecker(checker.value, canvasW * scale, canvasH * scale) }

    /** Not shown → all are below it */
    fun range(part: Part, hot: Long): IntRange {
        val shown = layers.value
        val h = shown.indexOfFirst { it.layer.id == hot }.let { if (it < 0) shown.size else it }
        return when (part) {
            Part.Below -> 0 until h
            Part.Hot -> h until minOf(h + 1, shown.size)
            Part.Above -> h + 1 until shown.size
        }
    }

    /** A PlaceState [tool]'s layer drawn with its transform (a text by the tool itself), a TransitionTool's or [overlay]'s (corrections, an effect) with its preview unless
    live strokes are on it; [fill]'s gets its preview */
    fun DrawScope.drawLayers(offset: Offset, scale: Float, range: IntRange, tool: CanvasTool<*>? = null, detail: Float = 1f, fill: FillTool? = null, overlay: LayerOverlay? = null) {
        val lv = levels.value
        val shown = layers.value
        val place = tool as? PlaceState<*>
        val transition = tool as? TransitionTool
        translate(offset.x, offset.y) {
            for (i in range) {
                val d = shown[i]
                val l = d.layer
                val active = place?.takeIf { l.id == it.id }
                val alpha = active?.alpha ?: preview.alpha(l.id, l.opacity)
                val blend = preview.blend(l.id, l.blend).mode
                if (active is TextState) with(active) { drawLive(scale, canvasW, canvasH) }
                else drawPlaced(d.img, l.fileW, l.fileH, active?.t ?: l.inPlace, scale, canvasW, canvasH, d.img?.let { lv[it.asAndroidBitmap()] }, alpha, l.orient, d.mask, d.patch,
                    strokes.takeIf { it.has(l.id) } ?: transition?.takeIf { l.id == it.layer } ?: overlay?.takeIf { l.id == it.layer && it.shows }, l, detail, blend)
                if (fill != null && l.id == fill.layer) with(fill) { drawPreview(alpha, blend, scale, canvasW, canvasH) }
            }
        }
    }

    /** A blend mode among [range]: such a layer must see the layers below it, not the checker */
    fun blends(range: IntRange = layers.value.indices) = range.any { i -> layers.value[i].layer.let { preview.blend(it.id, it.blend) } != Blend.NORMAL }

    override fun DrawScope.drawAround(focus: Offset, zoom: Float) {
        val o = focus + (vp.offset - focus) * zoom
        val s = vp.scale * zoom
        drawBoard(o, s)
        // Own layer (loupe-sized: bounds meet its clip) as on the canvas: blend modes see only the layers
        drawContext.canvas.withSaveLayer(Rect(o, Size(canvasW * s, canvasH * s)), isolated) { drawLayers(o, s, layers.value.indices) }
    }

    /** Visible layers composited under [p] from their own pixels and patches; transparent or off the canvas → null */
    override fun canvasAt(p: Offset) = (p - vp.offset) / vp.scale

    override fun colorAt(p: Offset): Color? {
        val c = canvasAt(p)
        if (c.x < 0f || c.y < 0f || c.x >= canvasW || c.y >= canvasH) return null
        val m = Matrix()
        val pt = FloatArray(2)
        val src = FloatArray(3)
        val rgb = FloatArray(3) // premultiplied
        var a = 0f
        for (d in layers.value) {
            val l = d.layer
            if (!l.fileToCanvas().invert(m)) continue
            pt[0] = floor(c.x) + 0.5f
            pt[1] = floor(c.y) + 0.5f
            m.mapPoints(pt)
            val x = floor(pt[0]).toInt()
            val y = floor(pt[1]).toInt()
            if (x !in 0 until l.fileW || y !in 0 until l.fileH) continue
            val tile = d.patch?.let { it.levels[0][y / TILE * it.cols(0) + x / TILE] }
            val px = Color(tile?.getPixel(x % TILE, y % TILE) ?: d.img?.asAndroidBitmap()?.getPixel(x, y) ?: continue)
            val k = px.alpha * (if (tile == null) d.mask?.alphaAt(x, y) ?: 1f else 1f) * preview.alpha(l.id, l.opacity)
            src[0] = px.red; src[1] = px.green; src[2] = px.blue
            a = preview.blend(l.id, l.blend).over(src, k, rgb, a)
        }
        // Float error may push a channel out of 0..1: Color() would throw
        return if (a > 0f) Color((rgb[0] / a).coerceIn(0f, 1f), (rgb[1] / a).coerceIn(0f, 1f), (rgb[2] / a).coerceIn(0f, 1f)) else null
    }
}

@Composable
fun rememberCanvasPicture(canvasW: Int, canvasH: Int, layers: List<Drawn>, levels: State<Levels>, preview: LayerPreview, strokes: BrushTool): CanvasPicture {
    val size = rememberUpdatedState(IntSize(canvasW, canvasH))
    val shown = rememberUpdatedState(layers)
    val checker = rememberUpdatedState(rememberCheckerBrush(8.dp))
    return remember { CanvasPicture(size, shown, levels, preview, checker, strokes) }
}

/** A frame tool showing the selection it will make: the outside tinted, its outline in place of [s]'s */
interface SelectionPreview {
    /** In its own offscreen layer */
    fun DrawScope.drawPreview(s: Selection, offset: Offset, scale: Float, cw: Int, ch: Int)

    /** Canvas px; null — [s]'s own */
    fun outline(s: Selection): Path?
}

/** [tool] — Move, Text, Crop, Selection transform or Transition while open; [select], [brush] — the active tool's gesture (both for the Selection brush); [selection] — drawn always,
as a SelectionPreview [tool] makes it; [active] — Layer.id, 0 — none; [fill] — the Fill/Stroke tool while open; [overlay] — the carousel's corrections or the open
effect; [onTap] — a one-finger touch that didn't move; [cursor] — the virtual cursor while on: a finger holds it instead of touching, its press is such a touch;
[bars] — the editor's bars and side panels shown */
@Composable
fun CanvasView(picture: CanvasPicture, tool: CanvasTool<*>?, select: SelectTool?, brush: BrushTool?, selection: Selection?, active: Long, fill: FillTool?, overlay: LayerOverlay?,
    onTap: (() -> Unit)?, cursor: CanvasCursor?, bars: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx() }
    // Bars overlay the canvas: fit is for the area between them
    val sideW = with(density) { SIDEBAR_W.toPx() }
    val topH = with(density) { BAR_H.toPx() }
    val vp = picture.vp
    val cw = picture.canvasW
    val ch = picture.canvasH
    // A new canvas size is fit before the frame is drawn
    DisposableEffect(cw, ch) {
        vp.refit(cw, ch)
        onDispose {}
    }
    val committed = rememberUpdatedState(selection)
    val ants = remember { mutableIntStateOf(0) }
    // Marches only while an outline is shown
    LaunchedEffect(select) {
        snapshotFlow { committed.value != null || select?.shape != null || select?.trace != null || select?.poly != null }.collectLatest { on -> if (on) while (true) { delay(ANTS_STEP_MS); ants.intValue++ } }
    }
    val move = tool as? PlaceState<*>
    val preview = tool as? SelectionPreview
    val hot = move?.id ?: active // moved layer or text, else the active one
    val grabIcon = rememberVectorPainter(AppIcons.HandGrab)
    val clickIcon = rememberVectorPainter(AppIcons.MousePointerClick)

    fun CanvasCursor.aim(p: Offset, view: Size) = spots(p, view, bars, brush?.takeUnless { it.picks }?.radius(density.density) ?: 0f, density)

    Box(
        modifier
            .onSizeChanged { s -> vp.fitInto(Rect(sideW, topH, s.width - sideW, s.height.toFloat()), margin, picture.canvasW, picture.canvasH) }
            .pointerInput(tool, select, brush, onTap, cursor, bars) {
                val slop = viewConfiguration.touchSlop
                val hit = CURSOR_HIT.toPx()
                var lastTap = 0L // a lone tap's release, uptime ms; 0 — none to pair with
                fun press(p: Offset) = Press(p, vp, tool, select, brush, committed.value, this, slop)

                /** A pinch discards [t]; one that didn't move is a tap, the second of a quick pair — the tool's double tap */
                fun release(t: Press, pinched: Boolean, downAt: Long, upAt: Long) {
                    if (!pinched) t.finish()
                    val paired = downAt - lastTap <= viewConfiguration.doubleTapTimeoutMillis
                    lastTap = 0L
                    if (!pinched && !t.moving) if (paired && tool != null) tool.doubleTap() else { onTap?.invoke(); lastTap = upAt }
                }

                fun pinch(e: PointerEvent) {
                    vp.pinch(e.calculateZoom(), e.calculateCentroid(), e.calculatePan())
                    e.changes.forEach { it.consume() }
                }

                /** Till all fingers lift */
                suspend fun AwaitPointerEventScope.zoom() {
                    try {
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) pinch(e) else vp.zoomPct = 0
                        } while (e.changes.any { it.pressed })
                    } finally { vp.zoomPct = 0 }
                }

                /** The finger [down] holds the cursor's handle once past touchSlop (a second finger before that zooms); then a second finger on the click button presses
                at the cursor till either lifts, elsewhere — zooms */
                suspend fun AwaitPointerEventScope.steer(c: CanvasCursor, down: PointerInputChange) {
                    val view = size.toSize()
                    val place = { p: Offset -> c.aim(p, view).spot }
                    var t: Press? = null
                    var by = down.id // the press's finger
                    var downAt = 0L
                    var shown = false
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            val hand = e.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed } ?: break
                            if (!shown) {
                                if (e.changes.any { it.changedToDownIgnoreConsumed() }) return zoom()
                                if ((hand.position - down.position).getDistance() <= slop) continue
                                shown = true
                            }
                            c.at = hand.position
                            val p = t
                            if (p != null) {
                                val f = e.changes.firstOrNull { it.id == by }
                                if (f != null && f.pressed) p.move(hand.position, hand.historical, place)
                                else { release(p, false, downAt, f?.uptimeMillis ?: hand.uptimeMillis); p.end(); t = null }
                            } else e.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { f ->
                                if ((f.position - c.aim(hand.position, view).click).getDistance() > hit) {
                                    c.at = Offset.Unspecified
                                    return zoom()
                                }
                                t = press(place(hand.position))
                                by = f.id
                                downAt = f.uptimeMillis
                            }
                            e.changes.forEach { it.consume() }
                        }
                        t?.let { release(it, false, downAt, currentEvent.changes[0].uptimeMillis) } // the handle let go first: the press ends with it
                    } finally {
                        t?.end()
                        c.at = Offset.Unspecified
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (cursor != null) {
                        steer(cursor, down)
                        return@awaitEachGesture
                    }
                    val t = press(down.position)
                    var multi = false // after a second finger single-finger input is ignored
                    try {
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                if (!multi) t.cancel() // the discarded gesture vanishes as the pinch starts
                                multi = true
                                pinch(e)
                            } else {
                                vp.zoomPct = 0
                                val c = e.changes.firstOrNull { it.id == down.id }
                                if (!multi && c != null && c.pressed && t.move(c.position, c.historical)) c.consume()
                            }
                        } while (e.changes.any { it.pressed })
                        release(t, multi, down.uptimeMillis, currentEvent.changes[0].uptimeMillis)
                    } finally { vp.zoomPct = 0; t.end() } // also on cancellation; the tool's history step once per gesture
                }
            }
            .clipToBounds()
            // Read only in the draw phase: gestures don't recompose
            .drawBehind { with(picture) { drawBoard(vp.offset, vp.scale) } }
    ) {
        // With a blend mode all groups go into one offscreen: it sees only the layers below, not the checker
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = if (picture.blends()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }) {
            // Each group its own layer: a stroke, move or opacity preview redraws the hot layer alone, the others stay Offscreen textures (not while zooming, nor empty,
            // nor the layers above with a blend mode: their own texture would hide the layers below)
            for (part in Part.entries) Spacer(Modifier.fillMaxSize()
                .graphicsLayer {
                    if (part != Part.Hot) compositingStrategy = if (vp.zoomPct == 0 && picture.range(part, hot).let { !it.isEmpty() && (part == Part.Below || !picture.blends(it)) })
                        CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawBehind { with(picture) { drawLayers(vp.offset, vp.scale, range(part, hot), tool, if (vp.zoomPct > 0) ZOOM_DETAIL else REST_DETAIL, fill, overlay) } })
        }
        // Offscreen: the cut-out stays in it
        if (preview != null) Spacer(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
            committed.value?.let { with(preview) { drawPreview(it, vp.offset, vp.scale, cw, ch) } }
        })
        // Own layer: a march step or a drag re-records only the overlays
        Spacer(Modifier.fillMaxSize().graphicsLayer {}.drawBehind {
            val s = vp.scale
            val phase = ants.intValue
            val shift = select?.shift?.toOffset() ?: Offset.Zero
            committed.value?.takeUnless { select?.replacing == true || brush?.replacing == true }?.let { drawAnts(preview?.outline(it) ?: it.outline, vp.offset + shift * s, s, phase) }
            tool?.run { translate(vp.offset.x, vp.offset.y) { drawOverlay(vp.scale) } } // over the outline: handles stay whole
            select?.shape?.let { drawAnts(it.outline, vp.offset, s, phase) }
            select?.trace?.let { drawAnts(it, vp.offset, s, phase) }
            select?.poly?.let { drawAnts(it, vp.offset, s, phase) }
            // First vertex: a white ring with a dark rim
            select?.firstVertex?.let { v ->
                val c = vp.offset + v * s
                val ring = FIRST_R.toPx()
                drawCircle(Color.Black, ring, c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, ring, c, style = Stroke(1.5.dp.toPx()))
            }
            val aimed = cursor?.takeIf { it.at.isSpecified }?.let { it.aim(it.at, size) }
            brush?.run { drawRing(vp.offset, s, cw, ch, cursor?.let { aimed?.spot ?: Offset.Unspecified }) }
            if (aimed != null) drawCursor(aimed, brush == null || brush.picks, grabIcon, clickIcon)
        })
        ZoomBadge({ vp.zoomPct }, Modifier.align(Alignment.TopCenter).padding(top = BAR_H))
    }
}

/** One press on the canvas from [from], screen px — a finger's or the cursor's: [tool], [select] and [brush] begin their gestures (the Selection brush doesn't paint a touch
that moves the selection); moves past [slop] drag them */
private class Press(private val from: Offset, private val vp: Viewport, private val tool: CanvasTool<*>?, private val select: SelectTool?, brush: BrushTool?, sel: Selection?,
    d: Density, private val slop: Float) {
    private val painting: BrushTool?
    var moving = false // past touchSlop: a pinch start won't move things
        private set

    init {
        tool?.begin(from - vp.offset, vp.scale, d)
        select?.begin((from - vp.offset) / vp.scale, sel, with(d) { CLOSE_REACH.toPx() } / vp.scale)
        painting = brush?.takeUnless { select?.moves == true }
        painting?.begin(from, vp.offset, vp.scale, d.density)
    }

    /** To [p] placed by [at]; the brush gets [past] points before it too: a fast stroke keeps its curves. True — something is dragged */
    fun move(p: Offset, past: List<HistoricalChange>, at: (Offset) -> Offset = { it }): Boolean {
        val q = at(p)
        if (!moving && (q - from).getDistance() > slop) moving = true
        if (!moving || tool == null && select == null && painting == null) return false
        painting?.let { b -> for (h in past.indices) b.drag(at(past[h].position)); b.drag(q) }
        tool?.drag(q - vp.offset)
        select?.drag((q - vp.offset) / vp.scale)
        return true
    }

    fun cancel() { select?.cancel(); painting?.cancel() }

    fun finish() { select?.finish(tap = !moving); painting?.finish() }

    /** Also on cancellation; the tool's history step once per gesture */
    fun end() { tool?.finish(); select?.cancel(); painting?.cancel() }
}

/** Read only here: a zoom step recomposes only the badge */
@Composable
private fun ZoomBadge(pct: () -> Int, modifier: Modifier) {
    val p = pct()
    if (p > 0) Text("$p%", modifier.background(BarBg.copy(alpha = 0.6f), SmallShape).padding(4.dp), color = Color.White, fontSize = 14.sp, lineHeight = 16.sp)
}
