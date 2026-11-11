package com.perkus.imagoedit.ui.editor

import android.graphics.BlendMode
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
import androidx.compose.runtime.remember
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
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.StrokeSide
import com.perkus.imagoedit.data.asPath
import com.perkus.imagoedit.ui.common.AppIcons
import kotlin.math.roundToInt

private const val MAX_WIDTH = 512 // px холста
private const val DEFAULT_WIDTH = 10 // px холста
private val ROW_W = rowWidth(8) // вровень с рядом 1: ширина не меняется со сменой режима
private val BTN = 32.dp // = ToolBtn

/** name is persisted */
enum class FillMode(val icon: ImageVector) { Stroke(AppIcons.Square), Fill(AppIcons.PaintBucket) }

private val StrokeSide.icon
    get() = when (this) {
        StrokeSide.Outside -> AppIcons.OutlineExternal
        StrokeSide.Center -> AppIcons.OutlineCenter
        StrokeSide.Inside -> AppIcons.OutlineInternal
    }

/** Persisted at once. A centered stroke is only even wide: an odd one would half-cover px around a whole-px outline */
@Stable
class FillOptions(settings: AppSettings) {
    val color = ColorOptions(settings, "fill.color")
    val opacity = IntOption(settings, "fill.opacity", 0..MAX_OPACITY, MAX_OPACITY)
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

/** Fill/Stroke on layer [l]: Fill — what [sel] selects (null — the canvas), Stroke — its outline (null — the layer's [pixels]' outline, none — the canvas's).
Previewed live (drawPreview), ✓ paints it as a brush stroke, the layer grows to hold it (Stroke.room) */
@Stable
class FillTool(l: Layer, val options: FillOptions, cw: Int, ch: Int, private val sel: Selection?, pixels: Selection? = null) {
    val layer = l.id
    private val file = l.file
    private val canvas = Rect(0, 0, cw, ch).asPath()
    private val filled = Figure(sel?.outline ?: canvas) // the preview's fill, a soft edge aside
    private val outline = sel?.let { it.drawn ?: it.outline } ?: pixels?.outline ?: canvas
    // Corners as growing keeps them: a drawn shape's, a one-rect layer's and the canvas's sharp, px-made ones round
    private val sharp = sel?.let { it.drawn != null } ?: (pixels?.region?.isRect != false)
    private val paint = Paint()

    private val fill get() = options.mode.value == FillMode.Fill

    /** null — 0 px wide */
    private val stroked by derivedStateOf { options.width.value.takeIf { it > 0 }?.let { Figure(outline, options.side.value, it.toFloat(), sharp) } }

    /** null — nothing. A fill covers the canvas, the selection limits it */
    fun stroke(): Stroke? = (if (fill) Figure(canvas) else stroked)?.let {
        Stroke(layer, file, options.color.value.toArgb(), opacity = options.opacity.value / MAX_OPACITY.toFloat(), area = sel.takeIf { fill }, figure = it)
    }

    /** Over its layer as the layer blends, within the canvas; options read in the draw phase */
    fun DrawScope.drawPreview(alpha: Float, blend: BlendMode, scale: Float, cw: Int, ch: Int) {
        val f = sel?.feather?.takeIf { fill }
        val s = if (fill) filled else stroked ?: return
        val c = drawContext.canvas.nativeCanvas
        c.save()
        c.scale(scale, scale)
        c.clipRect(0, 0, cw, ch)
        paint.color = options.color.value.toArgb()
        paint.alpha = (alpha * options.opacity.value / MAX_OPACITY * 255).roundToInt()
        paint.blendMode = blend
        // A fill's edges are whole px, shown unsmoothed as layers and checker are: AA would let them through along the canvas edge
        paint.isAntiAlias = !fill
        if (f != null) f.draw(c, paint) else s.draw(c, paint)
        c.restore()
    }
}

/** Row 1: color, modes, sides (off while filling), Cancel, ✓; Stroke unfolds row 2: width. Away while the color panel is open */
@Composable
fun FillOverlay(s: FillTool, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    val away = open == ToolPopup.Color
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(away) { shown.targetState = !away }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        val o = s.options
        val stroke = o.mode.value == FillMode.Stroke
        BottomPanel(stroke, null, Modifier, buttons = {
            ColorBtn(o.color, ColorOpacity(o.opacity), picture, onPopup, Modifier, BTN)
            FillMode.entries.forEach { m -> ToggleBtn(m.icon, m == o.mode.value) { o.mode.value = m } }
            // While filling none shows chosen; the side stays saved
            StrokeSide.entries.forEach { d -> ToggleBtn(d.icon, stroke && d == o.side.value, enabled = stroke) { o.setSide(d) } }
            ConfirmButtons(onCancel, onConfirm)
        }) {
            PanelSlider({ FieldText(stringResource(R.string.width)) }, o.width.value, o.width.range, Modifier.width(ROW_W), onChange = o::setWidth, onApply = o::setWidth)
        }
    }
}
