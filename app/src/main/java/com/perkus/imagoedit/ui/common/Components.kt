package com.perkus.imagoedit.ui.common

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.LightFieldBg
import com.perkus.imagoedit.ui.theme.LightFieldBorder
import com.perkus.imagoedit.ui.theme.SideBarBg
import kotlin.math.roundToInt

val SIDEBAR_W = 80.dp
val BAR_H = 40.dp // верхняя полоса, ряд кнопок, сторона IconBtn
private val FIELD_W = 56.dp // вмещает «100px»
private val FIELD_SHAPE = RoundedCornerShape(4.dp)
private val GRADIENT_TRACK_H = 16.dp
private val LABELED_SLIDER_W = 200.dp // одной длины во всех строках окна
private val BUSY_SHADOW_OFFSET = 1.dp
private val BUSY_SHADOW_BLUR = 2.dp

@Composable
fun Bar(modifier: Modifier = Modifier, arrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp), content: @Composable RowScope.() -> Unit) = Row(modifier.fillMaxWidth().background(BarBg).height(BAR_H), arrangement, Alignment.CenterVertically, content = content)
@Composable
fun AppTitle(modifier: Modifier = Modifier) = Text(stringResource(R.string.app_name), modifier, fontSize = 22.sp, fontStyle = FontStyle.Italic)
@Composable
fun SideBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(modifier.width(SIDEBAR_W).fillMaxHeight().background(SideBarBg), content = content)
@Composable
fun IconBtn(icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) = IconButton(onClick, Modifier.size(BAR_H), enabled = enabled) { Icon(icon, null, Modifier.size(24.dp)) }
/** Display only: the caller's container toggles */
@Composable
fun AppSwitch(checked: Boolean) = Switch(
    checked, null, colors = SwitchDefaults.colors(
        checkedThumbColor = BtnGray, checkedTrackColor = Color.White, checkedBorderColor = Color.White,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = BtnGray, uncheckedBorderColor = BtnGray
    )
)

/** White while [active] */
@Composable
fun MenuBtn(icon: ImageVector, active: Boolean, onClick: (anchor: Rect) -> Unit) {
    val bounds = rememberBounds()
    CompositionLocalProvider(LocalContentColor provides if (active) Color.Black else LocalContentColor.current) {
        Box(Modifier.trackBounds(bounds).then(if (active) Modifier.background(Color.White, CircleShape) else Modifier)) {
            IconBtn(icon) { onClick(bounds.rect) }
        }
    }
}

@Composable
fun BusyOverlay() = Box(Modifier.fillMaxSize().consumeTaps(), Alignment.Center) {
    val shadow = with(LocalDensity.current) { Shadow(Color.Black, Offset(BUSY_SHADOW_OFFSET.toPx(), BUSY_SHADOW_OFFSET.toPx()), BUSY_SHADOW_BLUR.toPx()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White)
        Text(stringResource(R.string.busy), color = Color.White, fontSize = 14.sp, style = TextStyle(shadow = shadow))
    }
}

fun Modifier.consumeTaps() = pointerInput(Unit) { detectTapGestures { } }

/** The element shows its state only by its own colors; no extra objects per element */
fun Modifier.plainClickable(onClick: () -> Unit) = clickable(interactionSource = null, indication = null, onClick = onClick)

/** Downs consumed by the card are skipped. Keyed by the lambda: pointerInput(Unit) would keep the first [onDismiss] */
fun Modifier.tapToDismiss(onDismiss: () -> Unit) = pointerInput(onDismiss) { awaitEachGesture { awaitFirstDown().consume(); onDismiss() } }

/** Press → [onDismiss] without consuming: the touch also reaches the siblings below. Keep it composed for the screen's lifetime, never inside the overlay it dismisses:
removing it mid-gesture cancels their gesture too */
fun Modifier.dismissOnPress(onDismiss: () -> Unit): Modifier = this then DismissOnPressElement(onDismiss)

private data class DismissOnPressElement(val onDismiss: () -> Unit) : ModifierNodeElement<DismissOnPressNode>() {
    override fun create() = DismissOnPressNode(onDismiss)
    override fun update(node: DismissOnPressNode) { node.onDismiss = onDismiss }
}

private class DismissOnPressNode(var onDismiss: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Main && pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) onDismiss()
    }
    override fun onCancelPointerInput() {}
    override fun sharePointerInputWithSiblings() = true
}

/** Not state: read only on click */
class Bounds { var rect = Rect.Zero }

@Composable
fun rememberBounds() = remember { Bounds() }

fun Modifier.trackBounds(b: Bounds) = onGloballyPositioned { b.rect = it.boundsInWindow() }

private object PickImage : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent = Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), input)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
fun rememberImagePicker(onPicked: (ContentResolver, Uri) -> Unit): () -> Unit {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val title = stringResource(R.string.choose_app)
    val launcher = rememberLauncherForActivityResult(PickImage) { uri -> uri?.let { onPicked(resolver, it) } }
    return remember(launcher, title) { { launcher.launch(title) } }
}

/** [onChange] while dragging, [onFinished] on release; NumPad ✓ → [onApply]. With a [label] the slider is fixed-width: rows line up */
@Composable
fun SliderRow(value: Int, range: IntRange, suffix: String, label: String? = null, onFinished: (() -> Unit)? = null, onChange: (Int) -> Unit, onApply: (Float) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.End, maxLines = 1) }
        NumField(value.toString(), Modifier.width(FIELD_W).background(LightFieldBg, FIELD_SHAPE).border(1.dp, LightFieldBorder, FIELD_SHAPE), PaddingValues(4.dp), suffix, fontSize = 16.sp, lineHeight = 16.sp, onApply = onApply)
        AppSlider(value, onChange, range, (if (label != null) Modifier.width(LABELED_SLIDER_W) else Modifier.weight(1f)).height(32.dp), onFinished = onFinished)
    }

/** [gradient] replaces the track; it spans the thumb center's travel: the color under the thumb matches */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(value: Int, onChange: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier, enabled: Boolean = true, onFinished: (() -> Unit)? = null, gradient: Brush? = null) {
    val colors = SliderDefaults.colors(thumbColor = BtnGray, activeTrackColor = BtnGray, inactiveTrackColor = BtnGray)
    Slider(
        value.toFloat(), { onChange(it.roundToInt()) }, modifier, enabled, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = onFinished,
        thumb = { Box(Modifier.size(28.dp).background(if (enabled) colors.thumbColor else colors.disabledThumbColor, CircleShape).border(2.dp, Color.White, CircleShape)) },
        track = {
            if (gradient != null) Box(Modifier.fillMaxWidth().height(GRADIENT_TRACK_H).background(gradient, CircleShape))
            else SliderDefaults.Track(it, enabled = enabled, colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp)
        }
    )
}
