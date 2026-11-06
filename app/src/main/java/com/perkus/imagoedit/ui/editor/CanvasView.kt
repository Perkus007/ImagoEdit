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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.alphaAt
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
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

private const val PCT = 100f // масштаб 1.0 = 100%; плашка показывает целые проценты, сам масштаб непрерывный
private const val MIN_SCALE = 1f / PCT // zoomPct = 0 зарезервирован под «плашка скрыта» → масштаб не опускается ниже 1%
private const val REST_DETAIL = 0.70710677f // 1/√2: копия слоя ближайшего к экрану размера — растянута или сжата не больше √2×, а не сжата до 2×: вдвое меньше чтения на кадр
private const val ZOOM_DETAIL = 0.5f // при зуме двумя пальцами все слои перерисовываются каждый кадр: копия не больше экрана (растянута до 2×), резкость — после отпускания
class Viewport {
    private var fit = 1f
    private var area = Rect.Zero // where the canvas is fit: between the bars, the margin taken off
    var scale by mutableFloatStateOf(1f) // screen px per canvas px
        private set
    var offset by mutableStateOf(Offset.Zero) // canvas top-left on screen
        private set
    var zoomPct by mutableIntStateOf(0) // > 0 only while two fingers zoom: the badge shows it, the canvas draws coarser copies (CanvasView)

    /** [area] — screen space between the bars; the canvas keeps [margin] from its edges */
    fun fitInto(area: Rect, margin: Float, canvasW: Int, canvasH: Int) {
        this.area = area.deflate(margin)
        refit(canvasW, canvasH)
    }

    /** Into the last laid out area: also for a new canvas size (crop, its Undo/Redo) */
    fun refit(canvasW: Int, canvasH: Int) {
        fit = minOf(area.width / canvasW, area.height / canvasH).coerceAtLeast(MIN_SCALE)
        scale = fit
        offset = area.center - Offset(canvasW.toFloat(), canvasH.toFloat()) * (fit / 2)
    }

    fun pinch(zoom: Float, centroid: Offset, pan: Offset) {
        val s = (scale * zoom).coerceIn(maxOf(fit / 4, MIN_SCALE), maxOf(fit, 32f))
        offset = centroid - (centroid - offset) * (s / scale) + pan
        scale = s
        zoomPct = (s * PCT).roundToInt()
    }
}

/** Shown layers around the hot one (CanvasView): those below it, it, those above it */
enum class Part { Below, Hot, Above }

/** What CanvasView shows and where. Hoisted: the pipette draws it magnified and samples it.
[size] (the canvas), [layers] (with their masks and patches) and [checker] hold the latest composition's values, so the color panel may keep the instance it was opened with.
[strokes] — the brush's live ones */
@Stable
class CanvasPicture(private val size: State<IntSize>, private val layers: State<List<Drawn>>, private val levels: State<Levels>, private val preview: OpacityPreview,
    private val checker: State<Brush>, private val strokes: BrushTool) : PipetteSource {
    val vp = Viewport()
    val canvasW get() = size.value.width
    val canvasH get() = size.value.height

    /** The checkerboard under the canvas: its top-left at [offset], [scale] screen px per canvas px */
    fun DrawScope.drawBoard(offset: Offset, scale: Float) = translate(offset.x, offset.y) { drawChecker(checker.value, canvasW * scale, canvasH * scale) }

    /** Indices of the shown layers in [part] around [hot] (Layer.id; not shown → all of them are below it) */
    fun range(part: Part, hot: Long): IntRange {
        val shown = layers.value
        val h = shown.indexOfFirst { it.layer.id == hot }.let { if (it < 0) shown.size else it }
        return when (part) {
            Part.Below -> 0 until h
            Part.Hot -> h until minOf(h + 1, shown.size)
            Part.Above -> h + 1 until shown.size
        }
    }

    /** The shown layers [range] over the canvas at [offset], [scale]; [tool]'s layer is drawn with the tool's transform, a text being added by the tool itself; [detail] — as drawPlaced's.
    [fill]'s layer gets what it will paint over it */
    fun DrawScope.drawLayers(offset: Offset, scale: Float, range: IntRange, tool: PlaceState<*>? = null, detail: Float = 1f, fill: FillTool? = null) {
        val lv = levels.value
        val shown = layers.value
        translate(offset.x, offset.y) {
            for (i in range) {
                val d = shown[i]
                val l = d.layer
                val active = tool?.takeIf { l.id == it.id }
                val alpha = active?.alpha ?: preview.alpha(l.id, l.opacity)
                if (active is TextState) with(active) { drawLive(scale, canvasW, canvasH) }
                else drawPlaced(d.img, l.fileW, l.fileH, active?.t ?: l.inPlace, scale, canvasW, canvasH, d.img?.let { lv[it.asAndroidBitmap()] }, alpha, l.orient, d.mask, d.patch, strokes, l.id, detail)
                if (fill != null && l.id == fill.layer) with(fill) { drawPreview(l, alpha, scale, canvasW, canvasH) }
            }
        }
    }

    override fun DrawScope.drawAround(focus: Offset, zoom: Float) {
        val o = focus + (vp.offset - focus) * zoom
        val s = vp.scale * zoom
        drawBoard(o, s)
        drawLayers(o, s, layers.value.indices)
    }

    /** Visible layers composited at the canvas pixel under [p], from the layers' own pixels and patches (not the downscaled screen copies); transparent or off the canvas → null */
    override fun colorAt(p: Offset): Color? {
        val c = (p - vp.offset) / vp.scale
        if (c.x < 0f || c.y < 0f || c.x >= canvasW || c.y >= canvasH) return null
        val m = Matrix()
        val pt = FloatArray(2)
        var r = 0f; var g = 0f; var b = 0f; var a = 0f // premultiplied, bottom layer up
        for (d in layers.value) {
            val l = d.layer
            if (!l.fileToCanvas().invert(m)) continue
            pt[0] = floor(c.x) + 0.5f
            pt[1] = floor(c.y) + 0.5f
            m.mapPoints(pt)
            val x = floor(pt[0]).toInt()
            val y = floor(pt[1]).toInt()
            if (x !in 0 until l.fileW || y !in 0 until l.fileH) continue
            // A patch's tile there is shown in place of the pixels × mask
            val tile = d.patch?.let { it.levels[0][y / TILE * it.cols(0) + x / TILE] }
            val px = Color(tile?.getPixel(x % TILE, y % TILE) ?: d.img?.asAndroidBitmap()?.getPixel(x, y) ?: continue)
            val k = px.alpha * (if (tile == null) d.mask?.alphaAt(x, y) ?: 1f else 1f) * preview.alpha(l.id, l.opacity)
            r = px.red * k + r * (1 - k)
            g = px.green * k + g * (1 - k)
            b = px.blue * k + b * (1 - k)
            a = k + a * (1 - k)
        }
        // Float error may push a channel a hair past 1: Color() would throw
        return if (a > 0f) Color((r / a).coerceAtMost(1f), (g / a).coerceAtMost(1f), (b / a).coerceAtMost(1f)) else null
    }
}

@Composable
fun rememberCanvasPicture(canvasW: Int, canvasH: Int, layers: List<Drawn>, levels: State<Levels>, preview: OpacityPreview, strokes: BrushTool): CanvasPicture {
    val size = rememberUpdatedState(IntSize(canvasW, canvasH))
    val shown = rememberUpdatedState(layers)
    val checker = rememberUpdatedState(rememberCheckerBrush(8.dp))
    return remember { CanvasPicture(size, shown, levels, preview, checker, strokes) }
}

/** A frame tool showing the selection it will make (Selection transform): the canvas outside it tinted, its outline in place of the selection's [s] */
interface SelectionPreview {
    /** In its own offscreen layer, over the canvas [cw]×[ch]: its origin at [offset], [scale] screen px per canvas px */
    fun DrawScope.drawPreview(s: Selection, offset: Offset, scale: Float, cw: Int, ch: Int)

    /** Canvas px; null — [s]'s own */
    fun outline(s: Selection): Path?
}

/** [tool] — the Move, Text, Crop or Selection transform tool while it's open; [select], [brush] — the Selection tool's or the Drawing, Eraser and Stamp tools' gesture while one is active
(both for the Selection brush); [selection] is drawn always, as a SelectionPreview [tool] will make it if it is one; [active] — the active layer (Layer.id), 0 — none;
[fill] — the Fill/Stroke tool while it's open (on the active layer) */
@Composable
fun CanvasView(picture: CanvasPicture, tool: FrameState<*>?, select: SelectTool?, brush: BrushTool?, selection: Selection?, active: Long, fill: FillTool?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx() } // checkerboard inset from the edges
    // Side and top bars overlay the canvas: fit is computed for the area between them
    val sideW = with(density) { SIDEBAR_W.toPx() }
    val topH = with(density) { BAR_H.toPx() }
    val vp = picture.vp
    val cw = picture.canvasW
    val ch = picture.canvasH
    // A new canvas size is fit before the frame is drawn (the effect runs on apply). Before the first layout onSizeChanged fits it anew
    DisposableEffect(cw, ch) {
        vp.refit(cw, ch)
        onDispose {}
    }
    val committed = rememberUpdatedState(selection)
    val ants = remember { mutableIntStateOf(0) }
    // Marches only while an outline is shown: no idle redraws
    LaunchedEffect(select) {
        snapshotFlow { committed.value != null || select?.shape != null || select?.trace != null || select?.poly != null }.collectLatest { on -> if (on) while (true) { delay(ANTS_STEP_MS); ants.intValue++ } }
    }
    val move = tool as? PlaceState<*>
    val preview = tool as? SelectionPreview
    val hot = move?.id ?: active // edited live: the moved layer or the text being added, else the active one (brush, opacity preview)

    Box(
        modifier
            .onSizeChanged { s -> vp.fitInto(Rect(sideW, topH, s.width - sideW, s.height.toFloat()), margin, picture.canvasW, picture.canvasH) }
            .pointerInput(tool, select, brush) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    tool?.begin(down.position - vp.offset, vp.scale, this)
                    select?.begin((down.position - vp.offset) / vp.scale, committed.value, CLOSE_REACH.toPx() / vp.scale)
                    val painting = brush?.takeUnless { select?.moves == true } // the Selection brush doesn't paint a touch that moves the selection
                    painting?.begin(down.position, vp.offset, vp.scale, density.density)
                    var multi = false // after a second finger, single-finger tool input is ignored until the gesture ends
                    var moving = false // finger passed touchSlop: otherwise a pinch start would move the image
                    try {
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                if (!multi) { select?.cancel(); painting?.cancel() } // the discarded gesture vanishes as the pinch starts, not after it
                                multi = true
                                vp.pinch(e.calculateZoom(), e.calculateCentroid(), e.calculatePan())
                                e.changes.forEach { it.consume() }
                            } else {
                                vp.zoomPct = 0
                                if ((tool != null || select != null || painting != null) && !multi) {
                                    val c = e.changes.firstOrNull { it.id == down.id }
                                    if (c != null && c.pressed) {
                                        if (!moving && (c.position - down.position).getDistance() > slop) moving = true
                                        if (moving) {
                                            if (painting != null) {
                                                // Points between events too: a fast stroke keeps its curves
                                                val past = c.historical
                                                for (h in past.indices) painting.drag(past[h].position)
                                                painting.drag(c.position)
                                            }
                                            tool?.drag(c.position - vp.offset); select?.drag((c.position - vp.offset) / vp.scale); c.consume()
                                        }
                                    }
                                }
                            }
                        } while (e.changes.any { it.pressed })
                        if (!multi) { select?.finish(tap = !moving); painting?.finish() } // a pinch discards the gesture
                    } finally { vp.zoomPct = 0; tool?.finish(); select?.cancel(); painting?.cancel() } // also on cancellation: the badge won't stick; the tool's history step is recorded once per gesture
                }
            }
            .clipToBounds()
            // scale/offset/tool.t are read only in the draw phase → gestures don't recompose
            .drawBehind { with(picture) { drawBoard(vp.offset, vp.scale) } }
    ) {
        // Each group its own layer: a stroke, a move or an opacity preview redraws the hot layer alone, the groups below and above it stay textures (Offscreen).
        // Not while zooming — all are redrawn each frame then, a texture would only add a pass — nor empty
        for (part in Part.entries) Spacer(Modifier.fillMaxSize()
            .graphicsLayer { if (part != Part.Hot) compositingStrategy = if (vp.zoomPct == 0 && !picture.range(part, hot).isEmpty()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
            .drawBehind { with(picture) { drawLayers(vp.offset, vp.scale, range(part, hot), move, if (vp.zoomPct > 0) ZOOM_DETAIL else REST_DETAIL, fill) } })
        // Offscreen: the cut-out stays in it, not through the picture; redrawn only for a new preview or view
        if (preview != null) Spacer(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
            committed.value?.let { with(preview) { drawPreview(it, vp.offset, vp.scale, cw, ch) } }
        })
        // Own layer: a march step or a tool's drag re-records only the overlays, not the picture. Drawn during zoom too: the path is fixed, only the matrix changes
        Spacer(Modifier.fillMaxSize().graphicsLayer {}.drawBehind {
            val s = vp.scale
            val phase = ants.intValue
            val shift = select?.shift?.toOffset() ?: Offset.Zero
            committed.value?.takeUnless { select?.replacing == true || brush?.replacing == true }?.let { drawAnts(preview?.outline(it) ?: it.outline, vp.offset + shift * s, s, phase) }
            tool?.run { translate(vp.offset.x, vp.offset.y) { drawOverlay(vp.scale) } } // over the outline: the handles stay whole
            select?.shape?.let { drawAnts(it.outline, vp.offset, s, phase) }
            select?.trace?.let { drawAnts(it, vp.offset, s, phase) }
            select?.poly?.let { drawAnts(it, vp.offset, s, phase) }
            // The first vertex: a hollow ring, white with a dark rim — visible on any color
            select?.firstVertex?.let { v ->
                val c = vp.offset + v * s
                val ring = FIRST_R.toPx()
                drawCircle(Color.Black, ring, c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, ring, c, style = Stroke(1.5.dp.toPx()))
            }
            brush?.run { drawRing(vp.offset, s, cw, ch) }
        })
        ZoomBadge({ vp.zoomPct }, Modifier.align(Alignment.TopCenter).padding(top = BAR_H))
    }
}

/** The value is read only here (via lambda) → a zoom step recomposes only the badge, not the whole canvas */
@Composable
private fun ZoomBadge(pct: () -> Int, modifier: Modifier) {
    val p = pct()
    if (p > 0) Text("$p%", modifier.background(BarBg.copy(alpha = 0.6f), SmallShape).padding(4.dp), color = Color.White, fontSize = 14.sp, lineHeight = 16.sp)
}
