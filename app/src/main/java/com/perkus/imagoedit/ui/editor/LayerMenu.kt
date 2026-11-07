package com.perkus.imagoedit.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.toOpacity
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.LocalFrame
import com.perkus.imagoedit.ui.common.Popover
import com.perkus.imagoedit.ui.common.SliderRow
import com.perkus.imagoedit.ui.common.anchored

private val MENU_W = 220.dp
private val MENU_GAP = 4.dp

/** Read only in the canvas draw phase: the slider doesn't recompose. [layer] — Layer.id shown, 0 — none */
@Stable
class OpacityPreview {
    var layer by mutableLongStateOf(0L)
    var percent by mutableIntStateOf(MAX_OPACITY)
    fun alpha(id: Long, saved: Int) = (if (id == layer) percent else saved) / MAX_OPACITY.toFloat()
}

private fun Modifier.leftOf(anchor: Rect, frame: WindowInsets) = anchored(anchor, frame) { a, w, h -> Offset(a.left - MENU_GAP.roundToPx() - w, a.bottom - h) }

/** Committed ([onOpacity]) on slider release and NumPad ✓; while sliding — preview only. Stays open after [onDelete], then shows the next active layer */
@Composable
fun LayerMenu(anchor: Rect, opacity: Int, layer: Long, canDelete: Boolean, preview: OpacityPreview, onOpacity: (Int) -> Unit, onDelete: () -> Unit, onMerge: () -> Unit, onDismiss: () -> Unit) {
    val frame = LocalFrame.current
    var value by remember(opacity) { mutableIntStateOf(opacity) }

    DisposableEffect(layer) {
        preview.percent = value
        preview.layer = layer
        onDispose { preview.layer = 0L }
    }

    fun setPercent(v: Int) { value = v; preview.percent = v }

    Popover(anchor, Modifier.leftOf(anchor, frame), onDismiss, Modifier.width(MENU_W)) {
        Text(stringResource(R.string.opacity), color = Color.Black, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
        SliderRow(value, 0..MAX_OPACITY, "%", onFinished = { onOpacity(value) }, onChange = { setPercent(it) }) { val p = it.toOpacity(); setPercent(p); onOpacity(p) }
        Row(Modifier.align(Alignment.End)) {
            IconBtn(AppIcons.FlatteningLayers, onClick = onMerge)
            IconBtn(AppIcons.Delete, canDelete, onDelete)
        }
    }
}