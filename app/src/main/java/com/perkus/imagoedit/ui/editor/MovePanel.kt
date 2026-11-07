package com.perkus.imagoedit.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.SmallShape

/** Angle and opacity are read ONLY in their fields: gestures recompose only that row */
@Composable
fun MoveOverlay(s: MoveState, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    if (s.precise) Nudges(s, if (s.expanded) 2 else 1)
    FramePanel(s, Modifier.align(Alignment.BottomCenter), extra = { PlaceButtons(s) }, extraFields = { TurnFields(s) }, onCancel = onCancel, onConfirm = onConfirm)
}

@Composable
fun PlaceButtons(s: PlaceState<*>) {
    ToolBtn(AppIcons.Inversion, rotation = { 90f }) { s.flipH() }
    ToolBtn(AppIcons.Inversion) { s.flipV() }
    ToggleBtn(AppIcons.Arrows, s.precise) { s.precise = !s.precise }
}

@Composable
fun TurnFields(s: PlaceState<*>) {
    AngleField(s)
    PanelField(s.opacity.toFloat(), { FieldIcon(AppIcons.Opacity) }, "%") { s.setField(FrameField.OPACITY, it) }
}

@Composable
fun AngleField(s: PlaceState<*>) = PanelField(s.t.angle, { FieldIcon(AppIcons.Angle) }, "°") { s.setField(FrameField.ANGLE, it) }

/** Above the tool's panel, [rows] of BAR_H */
@Composable
fun Nudges(s: PlaceState<*>, rows: Int) = Box(Modifier.fillMaxSize().padding(4.dp)) {
    Nudge(128.dp, 32.dp, 0f, Modifier.align(Alignment.TopCenter)) { s.nudge(0f, -1f) }
    Nudge(128.dp, 32.dp, 180f, Modifier.align(Alignment.BottomCenter).padding(bottom = BAR_H * rows)) { s.nudge(0f, 1f) }
    Nudge(32.dp, 128.dp, -90f, Modifier.align(Alignment.CenterStart)) { s.nudge(-1f, 0f) }
    Nudge(32.dp, 128.dp, 90f, Modifier.align(Alignment.CenterEnd)) { s.nudge(1f, 0f) }
}

@Composable
private fun Nudge(w: Dp, h: Dp, rotation: Float, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.size(w, h).clip(SmallShape).background(BtnGray.copy(alpha = 0.6f)).clickable { Feedback.tap(); onClick() }, Alignment.Center) {
        Icon(AppIcons.ChevronUp, null, Modifier.size(32.dp).rotate(rotation), Color.White)
    }
