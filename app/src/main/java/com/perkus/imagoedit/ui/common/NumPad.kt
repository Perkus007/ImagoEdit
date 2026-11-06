package com.perkus.imagoedit.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.CardShape
import com.perkus.imagoedit.ui.theme.SmallShape

private val KEY = 36.dp
private val GAP = 3.dp
private val PAD = 6.dp // внутренний отступ карточки
private val KEYS = listOf(listOf("1", "2", "3", "✓"), listOf("4", "5", "6", "←"), listOf("7", "8", "9", "DEL"), listOf("0", ".", "-"))

private fun edit(s: String, key: String): String = when (key) {
    "←" -> s.dropLast(1)
    "DEL" -> ""
    "-" -> if (s.startsWith("-")) s.drop(1) else "-$s"
    "." -> if ('.' in s) s else if (s.isEmpty() || s == "-") s + "0." else "$s."
    else -> if (s.trimStart('-') == "0") s.dropLast(1) + key else s + key
}.take(12)

/** One per window: fields sit deep in cards, the overlay must lie at the root beside what's below */
private class NumPadSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var cancel = {}
}
private val LocalNumPad = staticCompositionLocalOf<NumPadSlot> { error("NumPadHost missing") }

/** Call inside the window's root Box: the open NumPad is emitted as a sibling above [content] */
@Composable
fun NumPadHost(content: @Composable () -> Unit) {
    val slot = remember { NumPadSlot() }
    CompositionLocalProvider(LocalNumPad provides slot, content)
    Box(Modifier.fillMaxSize().dismissOnPress { slot.cancel() }) // permanent (see dismissOnPress); no-op while closed
    slot.content?.let { key(it) { it() } } // key: another field's NumPad doesn't inherit `fresh`
}

private fun Modifier.nextTo(anchor: Rect, frame: WindowInsets, gap: Dp = 4.dp) = anchored(anchor, frame) { a, w, h ->
    val g = gap.roundToPx()
    val above = a.top - g - h
    Offset(a.center.x - w / 2f, if (above >= 0f) above else a.bottom + g)
}

/** onClose(false): a press on the field or Back; elsewhere outside — the host's layer (slot.cancel).
The first digit, "." or "-" replaces the field's initial value; "←" and DEL edit it as usual */
@Composable
private fun NumPad(text: String, anchor: Rect, onChange: (String) -> Unit, onClose: (apply: Boolean) -> Unit) {
    var fresh by remember { mutableStateOf(true) }
    BackHandler { onClose(false) }
    OpenerGuard(anchor) { onClose(false) }
    Surface(Modifier.nextTo(anchor, LocalFrame.current).consumeTaps().width(KEY * 4 + GAP * 3 + PAD * 2), CardShape, border = BorderStroke(1.dp, BtnGray)) {
        Column(Modifier.padding(PAD), Arrangement.spacedBy(GAP)) {
            KEYS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    row.forEach { key ->
                        NumKey(key) {
                            if (key == "✓") onClose(true)
                            else {
                                onChange(edit(if (fresh && key != "←") "" else text, key))
                                fresh = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumKey(key: String, onClick: () -> Unit) = Button({ Feedback.tap(); onClick() }, Modifier.size(width = if (key == "0") KEY * 2 + GAP else KEY, height = KEY),
    shape = SmallShape, colors = ButtonDefaults.buttonColors(containerColor = if (key == "✓") BtnGreen else BtnGray, contentColor = Color.White),
    contentPadding = PaddingValues(0.dp)) {
    val icon = when (key) { "←" -> AppIcons.Backspace; "DEL" -> AppIcons.Delete; else -> null }
    if (icon != null) Icon(icon, null, Modifier.size(if (key == "DEL") 24.dp else 30.dp))
    else {
        val size = if (key == "✓") 26.sp else 30.sp
        Text(key, fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** [modifier] sets size, background and border: it precedes clickable/padding, so the NumPad anchor and touch area = the whole chip */
@Composable
fun NumField(value: String, modifier: Modifier, padding: PaddingValues, suffix: String = "", color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified, onApply: (Float) -> Unit) {
    var typed by remember { mutableStateOf<String?>(null) }
    val bounds = rememberBounds()
    val slot = LocalNumPad.current
    val currentOnApply by rememberUpdatedState(onApply)
    Text(
        (typed?.ifEmpty { "0" } ?: value) + suffix,
        modifier.trackBounds(bounds).plainClickable { typed = value }.padding(padding),
        color = color, fontSize = fontSize, textAlign = TextAlign.Center, lineHeight = lineHeight, maxLines = 1
    )
    // Composed by the host; reads typed itself. onDispose also covers the field leaving composition while open (its popover closed by the same press)
    if (typed != null) DisposableEffect(slot) {
        slot.cancel = { typed = null }
        slot.content = {
            typed?.let { text ->
                NumPad(text, bounds.rect, onChange = { typed = it }) { apply ->
                    if (apply) text.toFloatOrNull()?.let(currentOnApply)
                    typed = null
                }
            }
        }
        onDispose { slot.content = null; slot.cancel = {} }
    }
}