package com.perkus.imagoedit.ui.editor

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.LocalFrame
import com.perkus.imagoedit.ui.common.OpenerGuard
import com.perkus.imagoedit.ui.common.Popover
import com.perkus.imagoedit.ui.common.SIDEBAR_W
import com.perkus.imagoedit.ui.common.SliderRow
import com.perkus.imagoedit.ui.common.anchored
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.common.rememberBounds
import com.perkus.imagoedit.ui.common.trackBounds
import com.perkus.imagoedit.ui.theme.SmallShape
import com.perkus.imagoedit.ui.theme.TranslucentGray
import kotlin.math.floor
import kotlin.math.roundToInt

private const val UNFOLD_MS = 150 // разворачивание списка инструментов: короче анимаций панелей, палец уже ведёт по списку
private val PANEL_GAP = 4.dp // зазор между панелью и подсказкой, окном настроек или меню верхней панели
private val TIP_SHAPE = RoundedCornerShape(4.dp)
private val ROUND_BTN = 50.dp // круглая кнопка настройки инструмента
private val SECTION_GAP = 8.dp // промежуток между секциями настроек
private val ITEM_SHAPE = RoundedCornerShape(4.dp) // подложка выбранного стиля в списке
private val MENU_ICON_CENTER = 28.dp // центр иконки пункта меню от левого края карточки: отступ Popover 8 + отступ MenuItem 8 + половина иконки 12

/** name is persisted */
enum class Tool(val icon: ImageVector, val title: Int) {
    Selection(AppIcons.RectangleDashed, R.string.tool_selection), Drawing(AppIcons.Brush, R.string.tool_drawing), Stamp(AppIcons.Stamp, R.string.tool_stamp),
    Eraser(AppIcons.Eraser, R.string.tool_eraser), Blur(AppIcons.Droplet, R.string.tool_blur)
}

@Stable
class ToolOptions(settings: AppSettings) {
    val selection = SelectionOptions(settings)
    val drawing = BrushOptions(settings, "drawing.brush", BrushParam.entries - BrushParam.Intensity)
    val color = ColorOptions(settings, "color")
    val stamp = StampOptions(settings)
    val eraser = BrushOptions(settings, "eraser.brush", BrushParam.entries - BrushParam.Intensity)
    val blur = BlurOptions(settings)
    val text = TextOptions(settings)
    val transform = TransformOptions(settings)
    val fill = FillOptions(settings)
}

interface ToolStyle {
    val icon: ImageVector
    val title: Int
}

/** [inBar]: top bar menu (Edit, Selection, Tools3); [own]: the content places itself, no card around it (the palette, the Text tool's font list and spacing/weight) */
enum class ToolPopup(val inBar: Boolean = false, val own: Boolean = false) {
    Styles, Corners, Tolerance, Brush, Color(own = true), Font(own = true), Type(own = true), Edit(true), Selection(true), Tools3(true)
}

/** [shown] — the content slides itself in and out by it (the color panel, the font list) */
class OpenPopup(val kind: ToolPopup, val anchor: Rect, val shown: MutableTransitionState<Boolean>? = null, val content: @Composable () -> Unit)

/** A drag from the active tool unfolds the others underneath at once, no hold needed; the finger slides over them without lifting, release over a tool selects it.
Only the finger's height counts: drifting sideways doesn't lose the tool. A tap toggles the same list */
@Composable
fun ToolsPanel(tool: EnumOption<Tool>, options: ToolOptions, picture: PipetteSource, select: SelectTool, popup: ToolPopup?, onPopup: (OpenPopup) -> Unit, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth()) {
    val haptic = LocalHapticFeedback.current
    val current = tool.value
    val others = remember(current) { Tool.entries - current }
    var picking by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) } // index in others under the finger, -1 = none
    // Read in the draw phase → no recomposition
    val unfold = animateFloatAsState(if (picking) 1f else 0f, if (picking) tween(UNFOLD_MS) else snap(), label = "tools")

    // plainClickable outside pointerInput: the drag consumes its moves first, so a drag doesn't toggle the list too
    ToolCell(current, true, Modifier.plainClickable { hover = -1; picking = !picking }.pointerInput(tool, others) {
        detectVerticalDragGestures(
            onDragStart = { hover = -1; picking = true },
            onDragEnd = { picking = false; others.getOrNull(hover)?.let { tool.value = it } },
            onDragCancel = { picking = false }
        ) { change, _ ->
            // The list is stacked under this cell, all cells alike: others[i] spans (i + 1)·height .. (i + 2)·height. Above the list floor gives ≤ -1
            val i = (floor(change.position.y / size.height).toInt() - 1).takeIf { it in others.indices } ?: -1
            if (i != hover) { hover = i; if (i >= 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
        }
    })
    if (picking) Column(Modifier.graphicsLayer { scaleY = unfold.value; transformOrigin = TransformOrigin(0.5f, 0f) }) {
        others.forEachIndexed { i, t -> ToolCell(t, i == hover, Modifier.plainClickable { picking = false; tool.value = t }, tip = i == hover) }
    } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), Arrangement.spacedBy(SECTION_GAP), Alignment.CenterHorizontally) { // each child is one section; round buttons are centered here
        when (current) {
            Tool.Selection -> SelectionPanel(options.selection, select, popup, onPopup)
            Tool.Drawing -> {
                BrushBtn(options.drawing, popup, onPopup)
                // The fill is read in the draw phase: follows the panel's sliders live without recomposition
                RoundBtn(null, false, { options.color.value }) { onPopup(colorPopup(options.color, picture, it)) }
            }
            Tool.Stamp -> StampPanel(options.stamp, popup, onPopup)
            Tool.Eraser -> BrushBtn(options.eraser, popup, onPopup)
            Tool.Blur -> BlurPanel(options.blur, popup, onPopup)
        }
    }
}

@Composable
private fun ToolCell(tool: Tool, active: Boolean, modifier: Modifier = Modifier, tip: Boolean = false) =
    Box(modifier.fillMaxWidth().heightIn(min = BAR_H).then(if (active) Modifier.background(Color.White) else Modifier), Alignment.Center) {
        val color = if (active) Color.Black else LocalContentColor.current
        Column(Modifier.padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(tool.icon, null, Modifier.size(24.dp), color)
            PanelLabel(stringResource(tool.title), color)
        }
        // The cell spans the side bar → the tip starts past its edge. Measured unbounded: not squeezed to the cell width
        if (tip) Text(
            stringResource(tool.title),
            Modifier.align(Alignment.CenterStart).offset(x = SIDEBAR_W + PANEL_GAP).wrapContentWidth(Alignment.Start, unbounded = true).background(Color.White, TIP_SHAPE).padding(4.dp),
            color = Color.Black, fontSize = 14.sp, maxLines = 1
        )
    }

/** softWrap off + Ellipsis makes Compose force a single line, so a multi-line label needs softWrap on */
@Composable
fun PanelLabel(text: String, color: Color = Color.Unspecified, maxLines: Int = 1) = Text(text, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), color, fontSize = 12.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis)

@Composable
fun <E> StyleBtn(o: EnumOption<E>, open: ToolPopup?, onOpen: (OpenPopup) -> Unit) where E : Enum<E>, E : ToolStyle {
    val bounds = rememberBounds()
    val active = open == ToolPopup.Styles
    CompositionLocalProvider(LocalContentColor provides if (active) Color.Black else Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, end = 2.dp, bottom = 2.dp).trackBounds(bounds).clip(SmallShape).background(if (active) Color.White else Color.Transparent).border(1.dp, TranslucentGray, SmallShape).plainClickable {
                onOpen(OpenPopup(ToolPopup.Styles, bounds.rect) { o.entries.forEach { s -> MenuItem(s, s == o.value) { o.value = s } } })
            }.padding(vertical = 6.dp),
            Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), Alignment.CenterVertically
        ) {
            Icon(o.value.icon, null, Modifier.size(24.dp))
            Text(stringResource(R.string.select_style), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** [indication] = null for one-shot actions: only a choice shows a pressed or selected state */
@Composable
fun MenuItem(s: ToolStyle, selected: Boolean = false, enabled: Boolean = true, indication: Indication? = LocalIndication.current, onClick: () -> Unit) {
    val color = Color.Black.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
    Row(
        Modifier.fillMaxWidth().clip(ITEM_SHAPE).then(if (selected) Modifier.background(TranslucentGray) else Modifier)
            .clickable(interactionSource = null, indication = indication, enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically
    ) {
        Icon(s.icon, null, Modifier.size(24.dp), color)
        Text(stringResource(s.title), color = color, fontSize = 14.sp, maxLines = 1)
    }
}

/** [fill] is read in the draw phase */
@Composable
fun RoundBtn(icon: ImageVector?, active: Boolean, fill: () -> Color = { if (active) Color.White else Color.Transparent }, modifier: Modifier = Modifier.padding(top = 8.dp), size: Dp = ROUND_BTN,
    onClick: (anchor: Rect) -> Unit) {
    val bounds = rememberBounds()
    Box(
        modifier.trackBounds(bounds).size(size).clip(CircleShape).drawBehind { drawRect(fill()) }.border(1.dp, TranslucentGray, CircleShape).plainClickable { onClick(bounds.rect) },
        Alignment.Center
    ) { icon?.let { Icon(it, null, Modifier.size(32.dp), if (active) Color.Black else Color.White) } }
}

@Composable
fun PopupBtn(kind: ToolPopup, icon: ImageVector, open: ToolPopup?, onOpen: (OpenPopup) -> Unit, content: @Composable () -> Unit) =
    RoundBtn(icon, open == kind) { onOpen(OpenPopup(kind, it, content = content)) }

@Composable
fun ParamRow(label: String, o: IntOption, suffix: String = "") = SliderRow(o.value, o.range, suffix, label, onChange = { o.value = it }) { o.value = it.roundToInt() }

/** [p] — the open popup or null. Choices apply at once; closes only by a press outside or Back (NumPad ✓ keeps it open).
Card width = its widest row: no slack after short slider labels, long ones aren't cut.
One with [OpenPopup.shown] (always own) stays composed once closed, without its guard, until it has slid away */
@Composable
fun ToolPopover(p: OpenPopup?, onDismiss: () -> Unit) {
    var sliding by remember { mutableStateOf<OpenPopup?>(null) }
    LaunchedEffect(p) {
        if (p?.shown != null) sliding = p
        sliding?.shown?.targetState = sliding === p
    }
    sliding?.takeIf { it.shown?.run { currentState || !isIdle } == true }?.let { s ->
        key(s) { if (s === p) OpenerGuard(s.anchor, onDismiss); s.content() }
    }
    if (p != null && p.shown == null) {
        if (p.kind.own) { OpenerGuard(p.anchor, onDismiss); p.content() }
        else Popover(p.anchor, Modifier.placedFor(p, LocalFrame.current), onDismiss, Modifier.width(IntrinsicSize.Max)) { p.content() }
    }
}

private fun Modifier.placedFor(p: OpenPopup, frame: WindowInsets) = then(if (p.kind.inBar) Modifier.padding(end = SIDEBAR_W + PANEL_GAP) else Modifier).anchored(p.anchor, frame) { a, _, h ->
    if (p.kind.inBar) Offset(a.center.x - MENU_ICON_CENTER.toPx(), a.bottom)
    else Offset((SIDEBAR_W + PANEL_GAP).toPx(), maxOf(a.center.y - h / 2f, (BAR_H + PANEL_GAP).toPx()))
}
