package com.perkus.imagoedit.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.Blend
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.toOpacity
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSlider
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.LocalFrame
import com.perkus.imagoedit.ui.common.MenuItem
import com.perkus.imagoedit.ui.common.Popover
import com.perkus.imagoedit.ui.common.SliderField
import com.perkus.imagoedit.ui.common.anchored
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.common.scrollbar
import com.perkus.imagoedit.ui.theme.SmallShape
import com.perkus.imagoedit.ui.theme.TranslucentGray

private val MENU_GAP = 4.dp
private val LIST_H = 260.dp // ~6.5 пунктов: обрезанный снизу подсказывает прокрутку
private val BAR_ROOM = 8.dp // справа от пунктов, под полосу прокрутки

/** The menu's uncommitted state shown on the canvas: opacity while sliding, the blend mode while its list is open. The canvas reads it only in the draw phase:
the slider and the list don't recompose it. [layer] — Layer.id shown, 0 — none */
@Stable
class LayerPreview {
    var layer by mutableLongStateOf(0L)
    var percent by mutableIntStateOf(MAX_OPACITY)
    var mode by mutableStateOf(Blend.NORMAL)
    fun alpha(id: Long, saved: Int) = (if (id == layer) percent else saved) / MAX_OPACITY.toFloat()
    fun blend(id: Long, saved: Blend) = if (id == layer) mode else saved

    /** The mode picked for [l], not committed yet; null — none */
    fun picked(l: Layer) = mode.takeIf { l.id == layer && it != l.blend }
}

private fun Modifier.leftOf(anchor: Rect, frame: WindowInsets) = anchored(anchor, frame) { a, w, h -> Offset(a.left - MENU_GAP.roundToPx() - w, a.bottom - h) }

private val Blend.title get() = when (this) {
    Blend.NORMAL -> R.string.blend_normal
    Blend.DARKEN -> R.string.blend_darken
    Blend.MULTIPLY -> R.string.blend_multiply
    Blend.LIGHTEN -> R.string.blend_lighten
    Blend.SCREEN -> R.string.blend_screen
    Blend.OVERLAY -> R.string.blend_overlay
    Blend.SOFT_LIGHT -> R.string.blend_soft_light
    Blend.DIFFERENCE -> R.string.blend_difference
    Blend.EXCLUSION -> R.string.blend_exclusion
    Blend.COLOR -> R.string.blend_color
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) = Text(text, modifier, Color.Black, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)

/** [layer]'s opacity is committed ([onOpacity]) on slider release and NumPad ✓; while sliding — preview only. Stays open after [onDelete], then shows the next active layer.
The blend mode button turns it into the list of modes: a pick is only previewed, the host commits it on closing (LayerPreview.picked) — one history step */
@Composable
fun LayerMenu(anchor: Rect, layer: Layer, canDelete: Boolean, preview: LayerPreview, onOpacity: (Int) -> Unit, onDelete: () -> Unit, onMerge: () -> Unit, onDismiss: () -> Unit) {
    val frame = LocalFrame.current
    var value by remember(layer.opacity) { mutableIntStateOf(layer.opacity) }
    var blends by remember { mutableStateOf(false) }

    DisposableEffect(layer.id) {
        preview.percent = value
        preview.mode = layer.blend
        preview.layer = layer.id
        onDispose { preview.layer = 0L }
    }

    fun setPercent(v: Int) { value = v; preview.percent = v }

    // As wide as its widest row: the title with its field or the blend mode button with the icons; long names aren't cut
    Popover(anchor, Modifier.leftOf(anchor, frame), onDismiss, Modifier.width(IntrinsicSize.Max)) {
        if (blends) {
            Title(stringResource(R.string.blend_mode), Modifier.align(Alignment.CenterHorizontally))
            val scroll = rememberScrollState()
            Column(Modifier.heightIn(max = LIST_H).scrollbar(scroll).verticalScroll(scroll, overscrollEffect = null).padding(end = BAR_ROOM), Arrangement.spacedBy(4.dp)) {
                Blend.entries.forEach { b -> MenuItem(stringResource(b.title), b == preview.mode, onClick = { preview.mode = b }) {} }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Title(stringResource(R.string.opacity))
                SliderField(value, "%") { val p = it.toOpacity(); setPercent(p); onOpacity(p) }
            }
            AppSlider(value, { setPercent(it) }, 0..MAX_OPACITY, Modifier.fillMaxWidth().height(32.dp), onFinished = { onOpacity(value) })
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).clip(SmallShape).border(1.dp, TranslucentGray, SmallShape).plainClickable { blends = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                    Arrangement.spacedBy(6.dp), Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Shuffle, null, Modifier.size(20.dp), Color.Black)
                    Text(stringResource(layer.blend.title), color = Color.Black, fontSize = 14.sp, lineHeight = 16.sp, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    IconBtn(AppIcons.FlatteningLayers, onClick = onMerge)
                    IconBtn(AppIcons.Delete, canDelete, onDelete)
                }
            }
        }
    }
}
