package com.perkus.imagoedit.ui.editor

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.Figure
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.StrokeSide
import com.perkus.imagoedit.data.asPath
import com.perkus.imagoedit.ui.common.AppIcons
import kotlin.math.roundToInt

private const val MAX_WIDTH = 512 // наибольшая ширина обводки, px холста
private const val DEFAULT_WIDTH = 10 // ширина обводки при первом открытии, px холста
private val ROW_W = rowWidth(8) // ряд 2 вровень с рядом 1 (8 кнопок): панель не меняет ширину при смене режима
private val BTN = 32.dp // = ToolBtn

/** name is persisted */
enum class FillMode(val icon: ImageVector) { Stroke(AppIcons.Square), Fill(AppIcons.PaintBucket) }

private val StrokeSide.icon
    get() = when (this) {
        StrokeSide.Outside -> AppIcons.OutlineExternal
        StrokeSide.Center -> AppIcons.OutlineCenter
        StrokeSide.Inside -> AppIcons.OutlineInternal
    }

/** Persisted, each change at once. A centered stroke is only even wide: an odd one would half-cover the px either side of a whole-px outline — odd widths go up by 1 */
@Stable
class FillOptions(settings: AppSettings) {
    val color = ColorOptions(settings, "fill.color")
    val mode = EnumOption(settings, "fill.mode", FillMode.entries, FillMode.Fill)
    val width = IntOption(settings, "fill.width", 0..MAX_WIDTH, DEFAULT_WIDTH)
    val side = EnumOption(settings, "fill.side", StrokeSide.entries, StrokeSide.Inside)

    init { setWidth(width.value) }

    fun setWidth(v: Int) { width.value = if (side.value == StrokeSide.Center) v + v % 2 else v }

    fun setSide(s: StrokeSide) {
        side.value = s
        setWidth(width.value)
    }
}

/** The Fill/Stroke tool on layer [l] of the canvas [cw]×[ch] in its [options]' color: Fill — what the selection [sel] selects (null — everything), Stroke — along its outline
(null — the canvas's). The canvas shows it over the layer live (drawPreview), ✓ paints it in as a brush stroke (stroke). [applying] — ✓ is being baked */
@Stable
class FillTool(l: Layer, val options: FillOptions, cw: Int, ch: Int, private val sel: Selection?) {
    val layer = l.id
    private val file = l.file
    private val canvas = Rect(0, 0, cw, ch).asPath()
    private val filled = Figure(sel?.outline ?: canvas) // the preview's fill, a soft edge aside
    private val outline = sel?.let { it.drawn ?: it.outline } ?: canvas
    private val sharp = sel == null || sel.drawn != null // corners as growing keeps them (Selection.grown): a drawn shape's sharp, px-made ones round
    private val paint = Paint().apply { isAntiAlias = true }
    var applying by mutableStateOf(false)

    private val fill get() = options.mode.value == FillMode.Fill

    /** The stroke along the outline; null — none, 0 px wide */
    private val stroked by derivedStateOf { options.width.value.takeIf { it > 0 }?.let { Figure(outline, options.side.value, it.toFloat(), sharp) } }

    /** What ✓ paints into the layer; null — nothing. A fill covers the canvas, the selection limits it as it limits a brush */
    fun stroke(): Stroke? = (if (fill) Figure(canvas) else stroked)?.let { Stroke(layer, file, options.color.value.toArgb(), area = sel.takeIf { fill }, figure = it) }

    /** Over its layer [l] (as Layer.paintable) drawn with [alpha], [scale] screen px per canvas px. Options are read in the draw phase: a change redraws without recomposition */
    fun DrawScope.drawPreview(l: Layer, alpha: Float, scale: Float, cw: Int, ch: Int) {
        val f = sel?.feather?.takeIf { fill }
        val s = if (fill) filled else stroked ?: return
        val c = drawContext.canvas.nativeCanvas
        c.save()
        c.scale(scale, scale)
        c.clipRect(maxOf(l.x, 0), maxOf(l.y, 0), minOf(l.x + l.w, cw), minOf(l.y + l.h, ch))
        paint.color = options.color.value.toArgb()
        paint.alpha = (alpha * 255).roundToInt()
        if (f != null) f.draw(c, paint) else s.draw(c, paint)
        c.restore()
    }
}

/** Row 1: color, the modes, the stroke's sides (off while filling), Cancel, ✓; Stroke unfolds row 2, as wide: its width. Slides in; away while the color panel is open */
@Composable
fun FillOverlay(s: FillTool, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    val away = open == ToolPopup.Color
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(away) { shown.targetState = !away }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        val o = s.options
        val stroke = o.mode.value == FillMode.Stroke
        BottomPanel(stroke, null, Modifier, buttons = {
            // The fill is read in the draw phase: follows the color panel live
            RoundBtn(null, false, { o.color.value }, Modifier, BTN) { onPopup(colorPopup(o.color, picture, it)) }
            FillMode.entries.forEach { m -> ToggleBtn(m.icon, m == o.mode.value) { o.mode.value = m } }
            // While filling none shows chosen; the side stays saved for the stroke
            StrokeSide.entries.forEach { d -> ToggleBtn(d.icon, stroke && d == o.side.value, enabled = stroke) { o.setSide(d) } }
            ConfirmButtons(onCancel, onConfirm)
        }) {
            PxSlider({ FieldText(stringResource(R.string.width)) }, o.width.value, o.width.range, Modifier.width(ROW_W), onChange = o::setWidth, onApply = o::setWidth)
        }
    }
}
