package com.perkus.imagoedit.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSlider
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.DISABLED_ALPHA
import com.perkus.imagoedit.ui.common.NumField
import com.perkus.imagoedit.ui.common.SliderRow
import com.perkus.imagoedit.ui.common.consumeTaps
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.Red
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SmallShape
import kotlin.math.roundToInt

const val PANEL_ANIM_DURATION_MS = 300 // шеврон, ряды, уход и выезд панелей
val PANEL_ANIM = tween<Float>(PANEL_ANIM_DURATION_MS)
private val PANEL_RESIZE = tween<IntSize>(PANEL_ANIM_DURATION_MS)

/** Slides by its own height and fades */
val PANEL_IN = slideInVertically(tween(PANEL_ANIM_DURATION_MS)) { it } + fadeIn(PANEL_ANIM)
val PANEL_OUT = slideOutVertically(tween(PANEL_ANIM_DURATION_MS)) { it } + fadeOut(PANEL_ANIM)

/** As wide as [buttons] ToolBtns 16dp apart: rows kept flush */
fun rowWidth(buttons: Int) = 32.dp * buttons + 16.dp * (buttons - 1)

/** A tool's bottom panel: [buttons]; [more] — a row always under them; [expanded] unfolds [unfolded], then [fields] [fieldsGap] apart. With [onExpand] a chevron toggles it,
leading [buttons] if [chevronTop], else [more]. Each slot its own recomposition scope */
@Composable
fun BottomPanel(expanded: Boolean, onExpand: (() -> Unit)?, modifier: Modifier, buttons: @Composable RowScope.() -> Unit, more: (@Composable RowScope.() -> Unit)? = null,
    unfolded: (@Composable RowScope.() -> Unit)? = null, chevronTop: Boolean = more == null, fieldsGap: Dp = 8.dp, fields: @Composable RowScope.() -> Unit) {
    val rot = animateFloatAsState(targetValue = if (expanded) 180f else 0f, animationSpec = PANEL_ANIM, label = "chevron")
    val chevron = @Composable { if (onExpand != null) ToolBtn(AppIcons.ChevronUp, rotation = { rot.value }, onClick = onExpand) }

    Column(
        modifier = modifier
            .clip(SmallShape)
            .background(BarBg)
            .padding(horizontal = 8.dp)
            .consumeTaps(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.height(BAR_H), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
            if (chevronTop) chevron()
            buttons()
        }
        more?.let {
            Row(Modifier.height(BAR_H), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
                if (!chevronTop) chevron()
                it()
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(PANEL_RESIZE) + fadeIn(PANEL_ANIM),
            exit = shrinkVertically(PANEL_RESIZE) + fadeOut(PANEL_ANIM)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                unfolded?.let { Row(Modifier.height(BAR_H), Arrangement.spacedBy(16.dp), Alignment.CenterVertically, content = it) }
                Row(Modifier.height(BAR_H), Arrangement.spacedBy(fieldsGap), Alignment.CenterVertically, content = fields)
            }
        }
    }
}

/** A frame tool's panel: chevron, [extra], ratio lock, snapping, Undo/Redo, Cancel, ✓; [more] — a row under them; the chevron unfolds [unfolded], then X, Y, W, H, [extraFields].
The frame is read ONLY in the fields: gestures recompose only that row */
@Composable
fun FramePanel(s: FrameState<*>, modifier: Modifier, extra: @Composable RowScope.() -> Unit = {}, extraFields: @Composable RowScope.() -> Unit = {}, fieldsGap: Dp = 8.dp,
    more: (@Composable RowScope.() -> Unit)? = null, unfolded: (@Composable RowScope.() -> Unit)? = null, onCancel: () -> Unit, onConfirm: () -> Unit) =
    BottomPanel(s.expanded, { s.expanded = !s.expanded }, modifier, buttons = {
        extra()
        FrameButtons(s)
        ConfirmButtons(onCancel, onConfirm)
    }, more = more, unfolded = unfolded, chevronTop = true, fieldsGap = fieldsGap) {
        FrameFields(s)
        extraFields()
    }

@Composable
fun FrameButtons(s: FrameState<*>) {
    ToolBtn(if (s.free) AppIcons.UnlinkVertical else AppIcons.LinkVertical) { s.free = !s.free }
    SnapHistoryButtons(s)
}

@Composable
fun SnapHistoryButtons(s: CanvasTool<*>) {
    ToolBtn(if (s.snapOff) AppIcons.MagnetOff else AppIcons.MagnetOn) { s.snapOff = !s.snapOff }
    ToolBtn(AppIcons.StepBack, enabled = s.canUndo) { Feedback.tap(); s.undo() }
    ToolBtn(AppIcons.StepForward, enabled = s.canRedo) { Feedback.tap(); s.redo() }
}

@Composable
fun ConfirmButtons(onCancel: () -> Unit, onConfirm: () -> Unit) {
    CancelBtn(onCancel)
    ConfirmBtn(onConfirm)
}

@Composable
fun CancelBtn(onClick: () -> Unit) = ToolBtn(AppIcons.Cancel, bg = Red, onClick = onClick)

@Composable
fun ConfirmBtn(onClick: () -> Unit) = ToolBtn(AppIcons.Check, bg = BtnGreen, onClick = onClick)

/** X, Y — the bounds' top-left; W, H — the frame's sides */
@Composable
fun FrameFields(s: FrameState<*>) {
    val t = s.t
    val b = t.bounds()
    PanelField(b.left, { FieldText("X") }) { s.setField(FrameField.X, it) }
    PanelField(b.top, { FieldText("Y") }) { s.setField(FrameField.Y, it) }
    PanelField(t.w, { FieldText(stringResource(R.string.size_w)) }) { s.setField(FrameField.W, it) }
    PanelField(t.h, { FieldText(stringResource(R.string.size_h)) }) { s.setField(FrameField.H, it) }
}

/** A weight/fill [modifier] stretches it (size comes after and yields). Disabled — translucent as a whole */
@Composable
fun ToolBtn(icon: ImageVector, tint: Color = Color.White, bg: Color = Color.Transparent, enabled: Boolean = true, rotation: () -> Float = { 0f }, modifier: Modifier = Modifier,
    indication: Indication? = LocalIndication.current, onClick: () -> Unit
) = Box(modifier.size(32.dp).alpha(if (enabled) 1f else DISABLED_ALPHA).clip(SmallShape).background(bg).clickable(interactionSource = null, indication = indication, enabled = enabled, onClick = onClick),
    Alignment.Center) {
    // Read in the draw phase: the chevron animation doesn't recompose
    Icon(icon, null, Modifier.size(24.dp).graphicsLayer { rotationZ = rotation() }, tint)
}

/** [on]: a black icon on white */
@Composable
fun ToggleBtn(icon: ImageVector, on: Boolean, modifier: Modifier = Modifier, indication: Indication? = LocalIndication.current, enabled: Boolean = true,
    rotation: () -> Float = { 0f }, onClick: () -> Unit) =
    ToolBtn(icon, if (on) Color.Black else Color.White, if (on) Color.White else Color.Transparent, enabled, rotation, modifier, indication, onClick)

private fun fmt(v: Float): String {
    val r = (v * 100).roundToInt() / 100f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

/** NumPad ✓ → [onApply] */
@Composable
fun PanelField(value: Float, label: @Composable () -> Unit, suffix: String = "", onApply: (Float) -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        label()
        NumField(
            fmt(value), Modifier.width(48.dp).background(ScreenBg, SmallShape), PaddingValues(vertical = 2.dp),
            suffix = suffix, color = Color.White, fontSize = 14.sp, onApply = onApply
        )
    }

/** [label], field (NumPad ✓ → [onApply]) and slider ([onChange], released → [onFinished]) */
@Composable
fun PanelSlider(label: @Composable () -> Unit, value: Int, range: IntRange, modifier: Modifier, suffix: String = "px", onFinished: (() -> Unit)? = null, onChange: (Int) -> Unit,
    onApply: (Int) -> Unit) =
    Row(modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        label()
        PanelField(value.toFloat(), {}, suffix) { onApply(it.roundToInt()) }
        AppSlider(value, onChange, range, Modifier.weight(1f).height(32.dp), onFinished = onFinished)
    }

/** On a popover card over a panel: an equal share of the row, the label hugs its text */
@Composable
fun RowScope.PopoverSlider(label: AnnotatedString, value: Int, range: IntRange, suffix: String, onFinished: () -> Unit, onChange: (Int) -> Unit, onApply: (Int) -> Unit) =
    Row(Modifier.weight(1f), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, maxLines = 1)
        SliderRow(value, range, suffix, onFinished = onFinished, onChange = onChange) { onApply(it.roundToInt()) }
    }

@Composable
fun FieldText(text: String, color: Color = Color.White) = Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)

@Composable
fun FieldIcon(icon: ImageVector, modifier: Modifier = Modifier) = Icon(icon, null, modifier.size(20.dp), Color.White)
