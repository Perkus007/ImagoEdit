package com.perkus.imagoedit.ui.common

import android.graphics.ImageDecoder
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.BadImageSize
import com.perkus.imagoedit.data.ExportError
import com.perkus.imagoedit.data.MAX_PIXELS
import com.perkus.imagoedit.data.MAX_SIDE
import com.perkus.imagoedit.data.MIN_SIDE
import com.perkus.imagoedit.data.TooLarge
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.BtnRed
import com.perkus.imagoedit.ui.theme.CardShape
import com.perkus.imagoedit.ui.theme.SmallShape
import kotlin.math.roundToInt

val DIALOG_W = 360.dp // по умолчанию
private val POPOVER_SHAPE = RoundedCornerShape(10.dp)

@Composable
fun DialogCard(title: String?, modifier: Modifier = Modifier, titleEnd: (@Composable () -> Unit)? = null, borderColor: Color? = null, content: @Composable ColumnScope.() -> Unit) =
    Surface(modifier, shape = CardShape, border = borderColor?.let { BorderStroke(1.5.dp, it) }) {
        Column(Modifier.padding(8.dp), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                titleEnd?.invoke()
            }
            content()
        }
    }

/** [pos] gets the anchor in the parent's coordinates (offset by [frame]); clamped to the parent */
fun Modifier.anchored(anchor: Rect, frame: WindowInsets, pos: Density.(a: Rect, w: Int, h: Int) -> Offset) = layout { measurable, c ->
    val p = measurable.measure(c)
    val o = pos(anchor.translate(-frame.getLeft(this, layoutDirection).toFloat(), -frame.getTop(this).toFloat()), p.width, p.height)
    layout(c.maxWidth, c.maxHeight) {
        p.place(o.x.roundToInt().coerceIn(0, maxOf(0, c.maxWidth - p.width)), o.y.roundToInt().coerceIn(0, maxOf(0, c.maxHeight - p.height)))
    }
}

/** Over the opener at [anchor]: a press only dismisses (passed through, its click would reopen the card); elsewhere outside — the host's dismissOnPress layer */
@Composable
fun OpenerGuard(anchor: Rect, onDismiss: () -> Unit) =
    Box(Modifier.anchored(anchor, LocalFrame.current) { a, _, _ -> a.topLeft }.size(with(LocalDensity.current) { anchor.size.toDpSize() }).tapToDismiss(onDismiss))

/** An overlay, not a window: opens instantly, system bars untouched; emitted above the caller's dismissOnPress layer. [modifier] — the card's size */
@Composable
fun Popover(anchor: Rect, position: Modifier, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OpenerGuard(anchor, onDismiss)
    // consumeTaps after position: only the card takes taps
    PopoverCard(position.consumeTaps().then(modifier), content)
}

/** The card alone for a self-placing popover: [modifier] places and sizes it */
@Composable
fun PopoverCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Surface(modifier, POPOVER_SHAPE, Color.White, Color.Black) { Column(Modifier.padding(8.dp), Arrangement.spacedBy(4.dp), content = content) }

/** Fullscreen dialog centered and lifted above the keyboard by Compose: the cutout and IME don't shift it. [busy] — indicator over the card */
@Composable
fun CenteredDialog(onDismiss: () -> Unit, busy: Boolean = false, content: @Composable () -> Unit) =
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                // windowAnimations -1, not 0: 0 = unset, the theme's is used
                it.attributes = it.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    windowAnimations = -1
                }
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.hideSystemBars()
            }
            onDispose {}
        }
        val frame = LocalFrame.current
        val ime = WindowInsets.ime // read in layout only: no recomposition
        Box(
            Modifier.fillMaxSize()
                .tapToDismiss(onDismiss)
                // The keyboard isn't padding: the card's buttons would flatten
                .windowInsetsPadding(frame),
            contentAlignment = Alignment.Center
        ) {
            // Own NumPad host: the dialog is a separate window
            NumPadHost {
                Box(
                    Modifier
                        .layout { measurable, c ->
                            val p = measurable.measure(c)
                            // Lift as if centered above the keyboard, up to the screen's top
                            val lift = minOf(ime.getBottom(this) / 2, (c.maxHeight - p.height) / 2 - 4.dp.roundToPx()).coerceAtLeast(0)
                            layout(p.width, p.height) { p.place(0, -lift) }
                        }
                        .consumeTaps()
                ) { content() }
            }
            if (busy) BusyOverlay()
        }
    }

/** [width] null — wraps content; fillMaxWidth buttons stretch to the widest element */
@Composable
fun AppDialog(title: String?, onDismiss: () -> Unit, width: Dp? = DIALOG_W, borderColor: Color? = null, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    CenteredDialog(onDismiss, busy) { DialogCard(title, if (width != null) Modifier.width(width) else Modifier.width(IntrinsicSize.Max), borderColor = borderColor, content = content) }

/** Up to 5 lines, then scrolls. [newLines] — Enter types a new line, only OK finishes */
@Composable
fun TextInputDialog(text: String, onDismiss: () -> Unit, newLines: Boolean = false, onResult: (String) -> Unit) {
    val state = rememberTextFieldState(text, TextRange(0, text.length))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    CenteredDialog(onDismiss) {
        DialogCard(null, Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    state, Modifier.weight(1f).focusRequester(focus),
                    textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
                    // Default in a multi-line field: Enter, not an action key
                    keyboardOptions = KeyboardOptions(imeAction = if (newLines) ImeAction.Default else ImeAction.Done),
                    onKeyboardAction = { onResult(state.text.toString()) },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                // matchParentSize: OK takes the row height without Material's minimum
                Box(Modifier.width(56.dp).fillMaxHeight()) { DialogButton(stringResource(R.string.ok), BtnGreen, Modifier.matchParentSize()) { onResult(state.text.toString()) } }
            }
        }
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), content = content)

@Composable
fun DialogButton(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) = Button(
    onClick, modifier, enabled, shape = SmallShape,
    colors = ButtonDefaults.buttonColors(color, Color.White, color.copy(alpha = 0.5f), Color.White.copy(alpha = 0.5f)), contentPadding = PaddingValues(horizontal = 8.dp)
) { Text(text, fontSize = 14.sp, lineHeight = 16.sp, textAlign = TextAlign.Center) }

@Composable
fun OkCancel(onCancel: () -> Unit, okColor: Color = BtnGreen, cancelColor: Color = BtnGray, okEnabled: Boolean = true, okText: String = stringResource(R.string.ok), onOk: () -> Unit) = ButtonRow {
    DialogButton(stringResource(R.string.cancel), cancelColor, Modifier.weight(1f), onClick = onCancel)
    DialogButton(okText, okColor, Modifier.weight(1f), okEnabled, onOk)
}

/** Back and outside taps don't dismiss: discarding work takes an explicit answer */
@Composable
fun RecoveryDialog(onAnswer: (restore: Boolean) -> Unit) = AppDialog(stringResource(R.string.recovery_title), onDismiss = {}) {
    Text(stringResource(R.string.recovery_text), fontSize = 14.sp, textAlign = TextAlign.Center)
    OkCancel({ onAnswer(false) }, cancelColor = BtnRed) { onAnswer(true) }
}

/** Back and a tap outside answer no */
@Composable
fun TooLargeDialog(e: TooLarge, onAnswer: (reduce: Boolean) -> Unit) = AppDialog(stringResource(R.string.too_large_title), onDismiss = { onAnswer(false) }) {
    val s = e.size
    Text(stringResource(R.string.too_large_text, s.width, s.height, s.width.toLong() * s.height / 1e6f, MAX_PIXELS / 1_000_000, MAX_SIDE, e.fit.width, e.fit.height), fontSize = 14.sp, textAlign = TextAlign.Center)
    OkCancel({ onAnswer(false) }) { onAnswer(true) }
}

@Composable
fun ErrorDialog(error: Throwable, onDismiss: () -> Unit) {
    AppDialog(stringResource(R.string.error), onDismiss) {
        Text(
            when {
                error is OutOfMemoryError || error.cause is OutOfMemoryError -> stringResource(R.string.out_of_memory)
                error is BadImageSize -> stringResource(R.string.bad_size, error.width, error.height, MIN_SIDE, MAX_SIDE)
                error is ExportError -> error.cause?.message ?: error.cause?.javaClass?.simpleName.orEmpty()
                error is ImageDecoder.DecodeException -> stringResource(R.string.open_failed)
                else -> stringResource(R.string.operation_failed, error.message ?: error.javaClass.simpleName)
            },
            fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 6
        )
    }
}
