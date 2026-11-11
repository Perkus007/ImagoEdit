package com.perkus.imagoedit.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.Project
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.movedIndex
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.Bar
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.MenuBtn
import com.perkus.imagoedit.ui.common.SideBar
import com.perkus.imagoedit.ui.common.drawChecker
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.common.rememberCheckerBrush
import com.perkus.imagoedit.ui.theme.Grey
import com.perkus.imagoedit.ui.theme.SideBarBg
import kotlin.math.roundToInt

private val LAYER_H = 64.dp
private val THUMB = 48.dp
private val STROKE = 2.dp // обводка миниатюры и кнопки видимости
private val STROKE_RADIUS = 4.dp // по средней линии обводки скруглена и миниатюра
private val VIS_BTN_W = 16.dp // с обводкой
private val VIS_BTN_H = 51.dp // с обводкой
private val VIS_BTN_START = 3.dp
private val THUMB_INSET_START = VIS_BTN_START + VIS_BTN_W // миниатюра центрируется в остатке

/** Index 0 is the bottom layer. The dragged layer follows the finger, neighbors shift a cell (draw phase); the list reorders once, on release */
private class LayerDrag(private val cell: Float, private val count: Int) {
    var from by mutableIntStateOf(-1)
        private set
    private var dy by mutableFloatStateOf(0f) // px, down = +

    private val target get() = (from - (dy / cell).roundToInt()).coerceIn(0, count - 1)

    fun start(i: Int) { from = i; dy = 0f }

    fun update(delta: Float) { dy = (dy + delta).coerceIn(-(count - 1 - from) * cell, from * cell) }

    fun cancel() { from = -1; dy = 0f }

    fun finish(onMove: (from: Int, to: Int) -> Unit) {
        val f = from
        val t = target
        cancel()
        if (f >= 0 && f != t) onMove(f, t)
    }

    fun offset(i: Int): Float = when {
        from < 0 -> 0f
        i == from -> dy
        else -> (i - movedIndex(i, from, target)) * cell
    }
}

@Composable
fun LayersPanel(project: Project, bitmaps: List<ImageBitmap?>, masks: (Layer) -> LayerMask?, patches: (Layer) -> Tiles?, levels: State<Levels>, selected: Int, listState: LazyListState, menuOpen: Boolean, onSelect: (Int) -> Unit, onToggleVisible: (Int) -> Unit, onMove: (from: Int, to: Int) -> Unit, onAdd: () -> Unit, onMenu: (anchor: Rect) -> Unit, modifier: Modifier = Modifier) = SideBar(modifier) {
    val checker = rememberCheckerBrush(4.dp)
    val aspect = project.width.toFloat() / project.height
    val cell = with(LocalDensity.current) { LAYER_H.toPx() }
    val drag = remember(cell, project.layers.size) { LayerDrag(cell, project.layers.size) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true) {
        itemsIndexed(project.layers) { i, layer ->
            LayerItem(i, layer, bitmaps.getOrNull(i), masks(layer), patches(layer), levels, aspect, project.width, project.height, i == selected, checker, drag, { onSelect(i) },
                { onToggleVisible(i) }, onMove)
        }
    }
    Bar(arrangement = Arrangement.Center) {
        MenuBtn(AppIcons.Layers, menuOpen, onMenu)
        IconBtn(AppIcons.Add, onClick = onAdd)
    }
}

@Composable
private fun LayerItem(i: Int, layer: Layer, bitmap: ImageBitmap?, mask: LayerMask?, patch: Tiles?, levels: State<Levels>, aspect: Float, canvasW: Int, canvasH: Int, selected: Boolean, checker: Brush, drag: LayerDrag, onClick: () -> Unit, onToggleVisible: () -> Unit, onMove: (from: Int, to: Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val move by rememberUpdatedState(onMove)
    Box(
        Modifier.fillMaxWidth().height(LAYER_H)
            .zIndex(if (drag.from == i) 1f else 0f)
            .graphicsLayer { translationY = drag.offset(i) }
            .then(if (selected) Modifier.background(Color.White) else Modifier)
            .plainClickable(onClick)
            // After clickable: the gesture consumes first, no tap after a drag. A long press: a swipe still scrolls
            .pointerInput(drag) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); drag.start(i) }, onDrag = { change, delta -> change.consume(); drag.update(delta.y) },
                    onDragEnd = { drag.finish(move) }, onDragCancel = drag::cancel
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.padding(start = THUMB_INSET_START).sizeIn(maxWidth = THUMB, maxHeight = THUMB).aspectRatio(aspect)
                .drawWithContent { drawContent()
                    drawRoundRect(Grey, cornerRadius = CornerRadius(STROKE_RADIUS.toPx()), style = Stroke(STROKE.toPx())) }
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(STROKE_RADIUS)).drawBehind {
                // By the long side: the box's rounding moves the canvas edges under half a px
                val s = maxOf(size.width, size.height) / maxOf(canvasW, canvasH)
                drawChecker(checker, canvasW * s, canvasH * s)
                // A thumbnail-sized copy: the original would be tens of MB of texture per frame
                val copies = bitmap?.let { levels.value[it.asAndroidBitmap()] ?: return@drawBehind }
                drawPlaced(bitmap, layer.fileW, layer.fileH, layer.inPlace, s, canvasW, canvasH, copies, orient = layer.orient, mask = mask, patch = patch)
            })
        }
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = VIS_BTN_START).size(VIS_BTN_W, VIS_BTN_H)
                .plainClickable(onToggleVisible)
                .drawBehind {
                    val w = STROKE.toPx()
                    val r = STROKE_RADIUS.toPx()
                    drawRoundRect(if (layer.visible) Color.White else SideBarBg, cornerRadius = CornerRadius(r))
                    drawRoundRect(Grey, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r - w / 2), Stroke(w))
                }
        )
    }
}
