package com.perkus.imagoedit.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.FloatOption
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.toOpacity
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSlider
import com.perkus.imagoedit.ui.common.LocalFrame
import com.perkus.imagoedit.ui.common.NumField
import com.perkus.imagoedit.ui.common.consumeTaps
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.common.rememberBounds
import com.perkus.imagoedit.ui.common.rememberCheckerBrush
import com.perkus.imagoedit.ui.common.trackBounds
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SmallShape
import com.perkus.imagoedit.ui.theme.TranslucentGray
import kotlin.math.roundToInt

private const val COLS = 12
private const val ROWS = 3
private const val SAVED = COLS - 2 // строку начинает кнопка сохранения в 2 ячейки
private const val LOUPE_ZOOM = 4f
private val CELL = 32.dp // и высота строки ползунка
private val GAP = 8.dp
private val WIDE = CELL * 2 + GAP // 2 ячейки
private val PANEL_W = GAP + CELL * COLS + GAP * (COLS - 1) + GAP + CELL + GAP // одинакова в обоих режимах
private val PANEL_H = GAP + CELL * ROWS + GAP * (ROWS - 1) + GAP
private val FIELD_W = 48.dp // вмещает «360°»
private val LOUPE_R = 64.dp
private val LOUPE_LIFT = 20.dp
private val CROSS = 16.dp
private val CROSS_GAP = 4.dp // выбираемый пиксель не закрыт
private val LINE = Modifier.height(CELL).wrapContentHeight()

/** Hue spectrum: the color at any point = the hue at that slider value */
private val HUE_TRACK = Brush.horizontalGradient(List(7) { Color.hsv(60f * it % 360f, 1f, 1f) })

/** Palette rows 2–3: rainbow, grays, placeholders */
private val PRESETS = listOf(
    0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00, 0xFF00BFFF, 0xFF0000FF, 0xFF8B00FF, 0xFFFFFFFF, 0xFFC0C0C0, 0xFF6B6B6B, 0xFF2A2A2A, 0xFF000000,
    0xFF005F73, 0xFFFF6F61, 0xFF1B365D, 0xFF009B77, 0xFF800020, 0xFFA78BFA, 0xFFE2725B, 0xFF3EB489, 0xFFE5A93B, 0xFFB76E79, 0xFF00E1FF, 0xFFFFE4C4
).map { Color(it) }.chunked(COLS)

/** The picture under the pipette; points in the host Box's coordinates */
interface PipetteSource {
    fun DrawScope.drawAround(focus: Offset, zoom: Float)
    fun colorAt(p: Offset): Color?
}

/** HSV: hue and saturation survive gray and black, the sliders don't jump. [saved] — newest first */
@Stable
class ColorOptions(private val settings: AppSettings, private val key: String) {
    val hue = FloatOption(settings, "$key.hue", 0f..360f, 0f)
    val sat = FloatOption(settings, "$key.sat", 0f..100f, 100f)
    val bright = FloatOption(settings, "$key.bright", 0f..100f, 100f)
    var saved by mutableStateOf(loadSaved())
        private set

    var value: Color
        get() = Color.hsv(hue.value, sat.value / 100f, bright.value / 100f)
        set(c) {
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
            if (hsv[2] > 0f) {
                if (hsv[1] > 0f) hue.value = hsv[0]
                sat.value = hsv[1] * 100f
            }
            bright.value = hsv[2] * 100f
        }

    fun save() {
        saved = listOf(value) + saved.dropLast(1)
        settings.setString("$key.saved", saved.joinToString(",") { it.toArgb().toString() })
    }

    private fun loadSaved(): List<Color> {
        val stored = settings.string("$key.saved")?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        return List(SAVED) { i -> stored.getOrNull(i)?.let { Color(it) } ?: Color.White }
    }
}

/** The tool's own opacity, % (0..MAX_OPACITY), shown by the panel: [slide] while dragging, then [finish] — one change */
class ColorOpacity(val value: () -> Int, val finish: () -> Unit = {}, val slide: (Int) -> Unit) {
    constructor(o: IntOption) : this({ o.value }, slide = { o.value = it })
}

/** Slides up to the host Box's bottom on opening, down on closing (ToolPopover) */
fun colorPopup(o: ColorOptions, opacity: ColorOpacity, source: PipetteSource, anchor: Rect): OpenPopup {
    val shown = MutableTransitionState(false).apply { targetState = true }
    return OpenPopup(ToolPopup.Color, anchor, shown) { ColorSelectionPanel(o, opacity, source, shown) }
}

/** The mode resets to the palette, the third slider to brightness on each opening. Pipette: press its button and slide onto the picture — the loupe follows, release picks the color */
@Composable
private fun ColorSelectionPanel(o: ColorOptions, opacity: ColorOpacity, source: PipetteSource, shown: MutableTransitionState<Boolean>) {
    var sliders by remember { mutableStateOf(false) }
    val showOpacity = remember { mutableStateOf(false) } // in place of brightness
    var picking by remember { mutableStateOf(false) }
    val at = remember { mutableStateOf(Offset.Zero) } // read only in layout and draw
    val bounds = rememberBounds()
    val density = LocalDensity.current
    val frame = LocalFrame.current
    val host = Offset(frame.getLeft(density, LocalLayoutDirection.current).toFloat(), frame.getTop(density).toFloat())
    AnimatedVisibility(shown, Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        // Hidden, not removed while picking: leaving composition would cancel the gesture
        Row(Modifier.alpha(if (picking) 0f else 1f).size(PANEL_W, PANEL_H).clip(SmallShape).background(BarBg).consumeTaps().padding(GAP), Arrangement.spacedBy(GAP)) {
            Box(Modifier.weight(1f)) { if (sliders) Sliders(o, opacity, showOpacity) else Palette(o) }
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                // Not clickable: its tap detector would consume the down first
                Box(Modifier.size(CELL).trackBounds(bounds).pointerInput(o, source, host) {
                    awaitEachGesture {
                        val down = awaitFirstDown().also { it.consume() }
                        val origin = bounds.rect.topLeft - host
                        at.value = origin + down.position
                        picking = true
                        try {
                            if (drag(down.id) { at.value = origin + it.position; it.consume() }) source.colorAt(at.value)?.let { o.value = it }
                        } finally { picking = false }
                    }
                }, Alignment.Center) { Icon(AppIcons.Pipette, null, Modifier.size(24.dp), Color.White) }
                ModeBtn(AppIcons.Slider, sliders) { sliders = true }
                ModeBtn(AppIcons.Grip, !sliders) { sliders = false }
            }
        }
    }
    if (picking) Loupe(at, source)
}

@Composable
private fun ModeBtn(icon: ImageVector, on: Boolean, onClick: () -> Unit) =
    ToggleBtn(icon, on, Modifier.size(CELL), null, onClick = onClick)

private fun Modifier.colorFill(c: Color) = background(c, SmallShape).border(1.dp, TranslucentGray, SmallShape)

@Composable
private fun Palette(o: ColorOptions) = Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        val c = o.value
        val tint = if (c.luminance() > 0.5f) Color.Black else Color.White
        Row(Modifier.size(WIDE, CELL).colorFill(c).plainClickable(o::save).padding(horizontal = GAP), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Icon(AppIcons.SavePlus, null, Modifier.size(24.dp), tint)
            Icon(AppIcons.Back, null, Modifier.size(24.dp).rotate(180f), tint)
        }
        o.saved.forEach { s -> Swatch(s) { o.value = s } }
    }
    PRESETS.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(GAP)) { row.forEach { s -> Swatch(s) { o.value = s } } } }
}

@Composable
private fun Swatch(c: Color, onClick: () -> Unit) = Box(Modifier.size(CELL).colorFill(c).plainClickable(onClick))

/** [set] while dragging or typed, then [finish] */
private class ColorLine(val value: Int, val max: Int, val suffix: String, val gradient: Brush, val under: Brush? = null, val finish: () -> Unit = {}, val set: (Float) -> Unit) {
    constructor(o: FloatOption, suffix: String, gradient: Brush) : this(o.value.roundToInt(), o.range.endInclusive.roundToInt(), suffix, gradient, set = { o.value = it })
}

@Composable
private fun Label(text: Int, color: Color = Color.White) = Text(stringResource(text), LINE, color, fontSize = 14.sp, maxLines = 1)

/** Columns: the sliders start at one x whatever the label lengths. [showOpacity] — the third row is the tool's opacity in place of brightness */
@Composable
private fun Sliders(o: ColorOptions, opacity: ColorOpacity, showOpacity: MutableState<Boolean>) = Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
    var a by showOpacity
    val c = o.value
    Column(Modifier.fillMaxHeight(), Arrangement.SpaceBetween) {
        Box(Modifier.size(WIDE, CELL).colorFill(c))
        Row(Modifier.size(WIDE, CELL).background(Color.White, SmallShape).plainClickable { a = !a }.padding(horizontal = GAP), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Icon(if (a) AppIcons.Sun else AppIcons.Opacity, null, Modifier.size(24.dp), Color.Black)
            Icon(AppIcons.ArrowRightLeft, null, Modifier.size(24.dp), Color.Black)
        }
    }
    val pure = Color.hsv(o.hue.value, 1f, 1f)
    val checker = rememberCheckerBrush()
    val lines = listOf(
        ColorLine(o.hue, "°", HUE_TRACK),
        ColorLine(o.sat, "%", Brush.horizontalGradient(listOf(Color.White, pure))),
        if (a) ColorLine(opacity.value(), MAX_OPACITY, "%", Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c)), checker, opacity.finish) { opacity.slide(it.toOpacity()) }
        else ColorLine(o.bright, "%", Brush.horizontalGradient(listOf(Color.Black, pure)))
    )
    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        Label(R.string.color_hue)
        Label(R.string.color_saturation)
        // Both measured: the sliders don't shift on the swap
        Box {
            Label(R.string.color_brightness, if (a) Color.Transparent else Color.White)
            Label(R.string.opacity, if (a) Color.White else Color.Transparent)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        lines.forEach { l -> NumField(l.value.toString(), LINE.width(FIELD_W).background(ScreenBg, SmallShape), PaddingValues(vertical = 4.dp), l.suffix, Color.White, 14.sp) { l.set(it); l.finish() } }
    }
    Column(Modifier.weight(1f), Arrangement.spacedBy(GAP)) {
        lines.forEach { l -> AppSlider(l.value, { l.set(it.toFloat()) }, 0..l.max, Modifier.fillMaxWidth().height(CELL), onFinished = l.finish, gradient = l.gradient, under = l.under) }
    }
}

/** [LOUPE_LIFT] above the finger; the crosshair marks the picked pixel */
@Composable
private fun Loupe(at: State<Offset>, source: PipetteSource) = Box(
    Modifier
        .offset { (at.value - Offset(LOUPE_R.toPx(), (LOUPE_LIFT + LOUPE_R * 2).toPx())).round() }
        .size(LOUPE_R * 2)
        .clip(CircleShape)
        .background(ScreenBg)
        .drawBehind {
            val f = at.value
            translate(center.x - f.x, center.y - f.y) { with(source) { drawAround(f, LOUPE_ZOOM) } }
            val inner = CROSS_GAP.toPx()
            val outer = CROSS.toPx()
            fun cross(c: Color, width: Float) = repeat(4) { rotate(90f * it) { drawLine(c, center + Offset(inner, 0f), center + Offset(outer, 0f), width, StrokeCap.Round) } }
            cross(Color.Black, 3.5.dp.toPx()) // dark outline: visible on any color
            cross(Color.White, 1.5.dp.toPx())
        }
        .border(1.dp, TranslucentGray, CircleShape)
)
