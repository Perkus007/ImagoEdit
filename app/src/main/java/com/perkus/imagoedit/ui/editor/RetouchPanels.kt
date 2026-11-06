package com.perkus.imagoedit.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.ui.common.AppIcons

/** name is persisted */
enum class StampStyle(override val icon: ImageVector, override val title: Int) : ToolStyle {
    Stamp(AppIcons.Stamp, R.string.tool_stamp), Healing(AppIcons.HealingBrush, R.string.style_healing)
}

/** name is persisted */
enum class BlurStyle(override val icon: ImageVector, override val title: Int) : ToolStyle {
    Area(AppIcons.Circle, R.string.style_area), Finger(AppIcons.Pointer, R.string.style_finger)
}

@Stable
class StampOptions(settings: AppSettings) {
    val style = EnumOption(settings, "stamp.style", StampStyle.entries)
    val brush = BrushOptions(settings, "stamp.brush", BrushParam.entries - BrushParam.Intensity)
    var source by mutableStateOf(false) // On — a canvas tap or drag places the point pixels are copied from (BrushTool), then it goes off
}

@Stable
class BlurOptions(settings: AppSettings) {
    val style = EnumOption(settings, "blur.style", BlurStyle.entries)
    val brush = BrushOptions(settings, "blur.brush", listOf(BrushParam.Size, BrushParam.Hardness, BrushParam.Opacity, BrushParam.Intensity)) // the panel shows a subset per style
}

private val AREA_PARAMS = listOf(BrushParam.Size, BrushParam.Hardness, BrushParam.Opacity)
private val FINGER_PARAMS = listOf(BrushParam.Size, BrushParam.Hardness, BrushParam.Intensity)

@Composable
fun StampPanel(o: StampOptions, open: ToolPopup?, onOpen: (OpenPopup) -> Unit) {
    StyleBtn(o.style, open, onOpen)
    BrushBtn(o.brush, open, onOpen)
    RoundBtn(AppIcons.Locate, o.source) { o.source = !o.source }
}

@Composable
fun BlurPanel(o: BlurOptions, open: ToolPopup?, onOpen: (OpenPopup) -> Unit) {
    StyleBtn(o.style, open, onOpen)
    BrushBtn(o.brush, open, onOpen, if (o.style.value == BlurStyle.Area) AREA_PARAMS else FINGER_PARAMS)
}
