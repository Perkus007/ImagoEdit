package com.perkus.imagoedit.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.perkus.imagoedit.R
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.MenuBtn

enum class EditAction(override val icon: ImageVector, override val title: Int) : ToolStyle {
    Cut(AppIcons.Scissors, R.string.cut), Copy(AppIcons.Copy, R.string.copy), CopyMerged(AppIcons.CopyFromAllLayers, R.string.copy_merged),
    Paste(AppIcons.Insert, R.string.paste), Clear(AppIcons.Clear, R.string.clear), Extract(AppIcons.Extract, R.string.extract)
}

enum class SelectAction(override val icon: ImageVector, override val title: Int) : ToolStyle {
    All(AppIcons.RectangleDashed, R.string.select_all), Deselect(AppIcons.SquareDashedXCorner, R.string.deselect), Pixels(AppIcons.FreeformDashed, R.string.select_pixels),
    Invert(AppIcons.InvertSelection, R.string.invert_selection), Transform(AppIcons.Resize, R.string.transform_selection)
}

enum class Tools3Action(override val icon: ImageVector, override val title: Int) : ToolStyle {
    Frame(AppIcons.FrameCrop, R.string.frame), SizeCanvas(AppIcons.Scaling, R.string.size_canvas), Rotation(AppIcons.RefreshCcw, R.string.rotate_canvas),
    FillStroke(AppIcons.PaintBucket, R.string.fill_stroke), Text(AppIcons.Text, R.string.text), Transition(AppIcons.Opacity, R.string.transition)
}

/** Top bar menu of one-shot actions, inactive unless [enabled]. The open menu keeps the lambdas it was opened with → they must read state, not values of the opener's composition */
@Composable
fun <A : ToolStyle> ActionMenuBtn(kind: ToolPopup, icon: ImageVector, actions: List<A>, open: ToolPopup?, enabled: (A) -> Boolean = { false }, onAction: (A) -> Unit = {}, onOpen: (OpenPopup) -> Unit) =
    MenuBtn(icon, open == kind) { onOpen(OpenPopup(kind, it) { actions.forEach { a -> MenuItem(a, enabled = enabled(a), indication = null) { onAction(a) } } }) }