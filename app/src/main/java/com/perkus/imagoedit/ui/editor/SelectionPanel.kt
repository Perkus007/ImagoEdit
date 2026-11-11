package com.perkus.imagoedit.ui.editor

import android.graphics.Region
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSwitch
import com.perkus.imagoedit.ui.common.IconBtn

/** name is persisted */
enum class SelectStyle(override val icon: ImageVector, override val title: Int) : ToolStyle {
    Square(AppIcons.SquareDashed, R.string.style_square), Circle(AppIcons.CircleDashed, R.string.style_circle), Lasso(AppIcons.Lasso, R.string.style_lasso), Polygon(AppIcons.VectorPolygon, R.string.style_polygon),
    Wand(AppIcons.Wand, R.string.style_wand), Brush(AppIcons.Brush, R.string.brush)
}

/** name is persisted; [op] joins a new shape to the selection */
enum class SelectMode(val icon: ImageVector?, val op: Region.Op) {
    New(null, Region.Op.REPLACE), Add(AppIcons.SquareDashedPlus, Region.Op.UNION), Subtract(AppIcons.SquareDashedMinus, Region.Op.DIFFERENCE)
}

@Stable
class SelectionOptions(settings: AppSettings) {
    val style = EnumOption(settings, "selection.style", SelectStyle.entries)
    val mode = EnumOption(settings, "selection.mode", SelectMode.entries)
    val corner = IntOption(settings, "selection.corner", 0..1000, 0)
    val tolerance = IntOption(settings, "selection.tolerance", 0..255, 32)
    val contiguous = BoolOption(settings, "selection.contiguous", true) // Off — every matching px, not only the connected region
    val fixedRatio = BoolOption(settings, "selection.fixedRatio", false)
    val brush = BrushOptions(settings, "selection.brush", listOf(BrushParam.Size)) // only Size: a selection is hard
}

@Composable
fun SelectionPanel(o: SelectionOptions, select: SelectTool, open: ToolPopup?, onOpen: (OpenPopup) -> Unit) {
    StyleBtn(o.style, open, onOpen)
    Column {
        Row(Modifier.fillMaxWidth().padding(2.dp), Arrangement.spacedBy(6.dp)) {
            SelectMode.entries.forEach { m ->
                m.icon?.let { icon ->
                    val on = o.mode.value == m
                    ToolBtn(icon, if (on) Color.Black else Color.White, if (on) Color.White else Color.Transparent, modifier = Modifier.weight(1f), indication = null) { o.mode.value = if (on) SelectMode.New else m }
                }
            }
        }
        PanelLabel(stringResource(R.string.select_mode))
    }
    when (o.style.value) {
        SelectStyle.Square -> {
            PopupBtn(ToolPopup.Corners, AppIcons.CornerRadius, open, onOpen) { ParamRow(stringResource(R.string.select_radius), o.corner, "px") }
            SwitchCell(o.fixedRatio, "1:1")
        }
        SelectStyle.Circle -> SwitchCell(o.fixedRatio, "1:1")
        SelectStyle.Wand -> {
            PopupBtn(ToolPopup.Tolerance, AppIcons.Slider, open, onOpen) { ParamRow(stringResource(R.string.tolerance), o.tolerance) }
            SwitchCell(o.contiguous, stringResource(R.string.select_contiguous))
        }
        SelectStyle.Brush -> BrushBtn(o.brush, open, onOpen)
        // The unfinished polygon's own history
        SelectStyle.Polygon -> Row {
            IconBtn(AppIcons.StepBack, select.canUndoVertex) { Feedback.tap(); select.undoVertex() }
            IconBtn(AppIcons.StepForward, select.canRedoVertex) { Feedback.tap(); select.redoVertex() }
        }
        else -> Unit
    }
}

@Composable
private fun SwitchCell(o: BoolOption, label: String) = Column(
    Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(o.value, interactionSource = null, indication = null, role = Role.Switch) { o.value = it },
    horizontalAlignment = Alignment.CenterHorizontally
) {
    AppSwitch(o.value)
    PanelLabel(label, maxLines = 2)
}
