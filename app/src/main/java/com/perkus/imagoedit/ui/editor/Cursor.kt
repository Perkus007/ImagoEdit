package com.perkus.imagoedit.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.ui.common.SIDEBAR_W
import com.perkus.imagoedit.ui.theme.TranslucentGray

private val GRAB_D = 32.dp // круг значка перемещения
private val CLICK_D = 40.dp // круг значка нажатия
private const val ICON_K = 0.625f // иконка от диаметра круга
private val RIM = 1.dp
private val GRAB_X = 16.dp // левый верхний угол значка перемещения от крестика
private val GRAB_Y = 32.dp
private val RING_GRAB = 16.dp // ближняя к кругу кисти точка значка от его точки под 45° справа снизу, по обеим осям: зазор не зависит от размера
private const val DIAG = 0.70710677f // cos 45°
private val INSET = 16.dp // значок нажатия от края экрана, левой панели, панели инструмента
val CURSOR_HIT = CLICK_D / 2 // касание значка нажатия — только по его кругу

/** The editor's cursor: tool panels report their heights to it */
val LocalCursor = staticCompositionLocalOf<CanvasCursor?> { null }

/** View px: [spot] — the point pressed, [handle] and [click] — the icons' centers */
class CursorSpots(val spot: Offset, val handle: Offset, val click: Offset)

/** The virtual cursor: a finger on the canvas holds its handle at [at] (view px; Unspecified — no finger, nothing shown), the indicator off it; a second finger on the
click button presses there as a finger would */
@Stable
class CanvasCursor {
    var at by mutableStateOf(Offset.Unspecified)
    internal val panels = mutableStateMapOf<Any, Int>() // высоты панелей инструментов, px

    /** For the handle at [p] in a [view]; [r] — the brush ring's radius, 0 — the cross. The click button keeps off the left panel while the editor's [bars] are shown
    and above the tool panels */
    fun spots(p: Offset, view: Size, bars: Boolean, r: Float, d: Density) = with(d) {
        val k = (r + GRAB_D.toPx() / 2) * DIAG + RING_GRAB.toPx()
        val grab = if (r > 0f) Offset(k, k) else Offset((GRAB_X + GRAB_D / 2).toPx(), (GRAB_Y + GRAB_D / 2).toPx())
        val b = (INSET + CLICK_D / 2).toPx()
        CursorSpots(p - grab, p, Offset((if (bars) SIDEBAR_W.toPx() else 0f) + b, view.height - (panels.values.maxOrNull() ?: 0) - b))
    }
}

/** The cross unless the brush ring marks the spot, the handle and the click button with their icons */
fun DrawScope.drawCursor(s: CursorSpots, cross: Boolean, grab: Painter, click: Painter) {
    if (cross) drawCross(s.spot.x, s.spot.y)
    drawIcon(s.handle, grab, GRAB_D)
    drawIcon(s.click, click, CLICK_D)
}

/** A white disc [d] wide with a gray rim, [icon] in it */
private fun DrawScope.drawIcon(c: Offset, icon: Painter, d: Dp) {
    val r = d.toPx() / 2
    val rim = RIM.toPx()
    drawCircle(Color.White, r, c)
    drawCircle(TranslucentGray, r - rim / 2, c, style = Stroke(rim))
    val s = d.toPx() * ICON_K
    translate(c.x - s / 2, c.y - s / 2) { with(icon) { draw(Size(s, s)) } }
}

/** A tool's panel, flush with the screen's bottom: the click button keeps above it */
fun Modifier.toolPanel() = this then ToolPanelElement

private data object ToolPanelElement : ModifierNodeElement<ToolPanelNode>() {
    override fun create() = ToolPanelNode()
    override fun update(node: ToolPanelNode) = Unit
}

private class ToolPanelNode : Modifier.Node(), LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var cursor: CanvasCursor? = null

    override fun onAttach() { cursor = currentValueOf(LocalCursor) }

    override fun onRemeasured(size: IntSize) { cursor?.panels?.put(this, size.height) }

    override fun onDetach() { cursor?.panels?.remove(this) }
}
