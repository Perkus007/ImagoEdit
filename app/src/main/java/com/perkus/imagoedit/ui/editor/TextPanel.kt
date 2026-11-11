package com.perkus.imagoedit.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppFont
import com.perkus.imagoedit.data.Fonts
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.LocalFrame
import com.perkus.imagoedit.ui.common.PopoverCard
import com.perkus.imagoedit.ui.common.TextInputDialog
import com.perkus.imagoedit.ui.common.anchored
import com.perkus.imagoedit.ui.common.consumeTaps
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.common.rememberBounds
import com.perkus.imagoedit.ui.common.scrollbar
import com.perkus.imagoedit.ui.common.trackBounds
import com.perkus.imagoedit.ui.theme.Grey
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SmallShape
import com.perkus.imagoedit.ui.theme.TranslucentGray
import com.perkus.imagoedit.ui.theme.Yellow

private const val ROWS = 2 // пока третий свёрнут
private val FIELD_H = 32.dp // = ToolBtn
private val ROW_W = rowWidth(11) // вровень с рядом 2: 11 кнопок
private val PANEL_W = ROW_W + 16.dp // = BottomPanel
private val SPACING_LABEL = AnnotatedString("A → B") // без перевода
private val WEIGHT_LABEL = buildAnnotatedString { // без перевода
    for (w in listOf(FontWeight.Thin, FontWeight.Black)) withStyle(SpanStyle(fontWeight = w)) { append('A') }
}
private val FIELDS_GAP = 16.dp
private val GAP = 4.dp
private val PICKER_MAX = 500.dp
private val PAD = 8.dp
private val TAB_PAD = 4.dp
private val ITEM_PAD = 4.dp
private val TAB_SP = 14.sp
private val NAME_SP = 16.sp
private val INFO_ICON = 20.dp
private val HINT_W = 280.dp
private val HINT_GAP = 16.dp // от низа иконки ⓘ
private val PICKER_SHAPE = RoundedCornerShape(10.dp)
private val FONT_ROW_H = 40.dp // одной высоты: на этом стоит полоса прокрутки

private fun TextState.panelH() = BAR_H * (if (expanded) ROWS + 1 else ROWS)

/** [onPopup] null closes the popover. Slides away while the text dialog, color panel or font list is open */
@Composable
fun TextOverlay(s: TextState, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) = Box(Modifier.fillMaxSize()) {
    var typing by remember { mutableStateOf(false) }
    val away = typing || open == ToolPopup.Color || open == ToolPopup.Font
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(away) { shown.targetState = !away }
    if (s.precise && !away) Nudges(s, if (s.expanded) ROWS + 1 else ROWS)
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
        TextPanel(s, picture, open, onPopup, { typing = true }, onCancel, onConfirm)
    }
    if (typing) TextInputDialog(s.content.text, { typing = false }, newLines = true) { s.setText(it); typing = false }
}

@Composable
private fun TextPanel(s: TextState, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onType: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) =
    BottomPanel(s.expanded, { s.expanded = !s.expanded }, Modifier, buttons = {
        // 16dp apart as row 2's: each over one of them
        Row(Modifier.width(ROW_W), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
            Row(Modifier.weight(1f), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                ColorBtn(s.options.color, ColorOpacity({ s.opacity }, s::finish, s::slideOpacity), picture, onPopup, Modifier, FIELD_H)
                TextField(s, onType, Modifier.weight(1f))
                FontField(s, onPopup, Modifier.weight(1f))
            }
            TypeBtn(s, open == ToolPopup.Type, onPopup)
            ConfirmButtons(onCancel, onConfirm)
        }
    }, more = {
        AlignButtons(s)
        PlaceButtons(s)
        FrameButtons(s)
    }, fieldsGap = FIELDS_GAP) {
        FrameFields(s)
        TurnFields(s)
    }

@Composable
private fun AlignButtons(s: TextState) = TextAlignment.entries.forEach { a -> ToggleBtn(a.icon, a == s.content.align) { s.setAlign(a) } }

@Composable
private fun TextField(s: TextState, onClick: () -> Unit, modifier: Modifier) =
    Box(modifier.height(FIELD_H).clip(SmallShape).background(ScreenBg).plainClickable(onClick).padding(horizontal = 8.dp), Alignment.CenterStart) {
        Text(if (s.empty) stringResource(R.string.text) else s.content.text, color = if (s.empty) Grey else Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun FontField(s: TextState, onPopup: (OpenPopup?) -> Unit, modifier: Modifier) {
    val bounds = rememberBounds()
    Row(
        modifier.height(FIELD_H).trackBounds(bounds).clip(SmallShape).background(ScreenBg).plainClickable {
            val shown = MutableTransitionState(false).apply { targetState = true }
            onPopup(OpenPopup(ToolPopup.Font, bounds.rect, shown) { FontPicker(s, shown) { onPopup(null) } })
        }.padding(start = 8.dp, end = 4.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically
    ) {
        Text(s.content.font.name, Modifier.weight(1f), Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(AppIcons.ChevronUp, null, Modifier.size(24.dp), Color.White)
    }
}

@Composable
private fun TypeBtn(s: TextState, open: Boolean, onPopup: (OpenPopup?) -> Unit) {
    val bounds = rememberBounds()
    ToggleBtn(AppIcons.Settings, open, Modifier.trackBounds(bounds)) {
        onPopup(OpenPopup(ToolPopup.Type, bounds.rect) { TypePopover(s) })
    }
}

/** Letter spacing, then a variable font's weight */
@Composable
private fun TypePopover(s: TextState) =
    PopoverCard(Modifier.fillMaxSize().padding(bottom = s.panelH() + GAP).wrapContentSize(Alignment.BottomCenter).consumeTaps().width(PANEL_W)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
            PopoverSlider(SPACING_LABEL, s.content.spacing, s.options.spacing.range, "%", s::finish, s::slideSpacing, s::setSpacing)
            s.content.font.weights?.let { r -> PopoverSlider(WEIGHT_LABEL, s.weight, r, "", s::finish, s::slideWeight, s::setWeight) }
        }
    }

/** The whole screen's height; slides by [shown] */
@Composable
private fun FontPicker(s: TextState, shown: MutableTransitionState<Boolean>, onDismiss: () -> Unit) {
    val o = s.options
    val tab = o.tab.value
    var info by remember { mutableStateOf<Rect?>(null) } // Info's bounds while its hint shows
    val onInfo = { r: Rect -> info = if (info == null) r else null }
    val list = when (tab) {
        FontTab.System -> s.fonts.system
        FontTab.User -> s.fonts.user
        FontTab.Favorites -> o.favorites.mapNotNull(s.fonts::byKey)
    }
    AnimatedVisibility(shown, Modifier.fillMaxSize().padding(GAP).wrapContentSize(Alignment.Center), PANEL_IN, PANEL_OUT) {
        Column(Modifier.width(rememberPickerWidth(s.fonts)).clip(PICKER_SHAPE).background(Color.White).consumeTaps().padding(vertical = PAD), Arrangement.spacedBy(PAD)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = PAD), Arrangement.spacedBy(PAD)) {
                FontTab.entries.forEach { t ->
                    FontTabBtn(t, t == tab, Modifier.weight(1f), onInfo.takeIf { t == FontTab.User }) { o.tab.value = t; info = null }
                }
            }
            val scroll = o.scroll[tab.ordinal]
            LazyColumn(Modifier.weight(1f, fill = false).scrollbar(scroll), scroll, PaddingValues(horizontal = PAD)) {
                items(list, { it.key }) { f -> FontItem(f, f.key == s.content.font.key, tab, o) { s.setFont(f); onDismiss() } }
            }
        }
    }
    // A sibling in the screen's Box: the card would clip it
    info?.takeIf { shown.targetState }?.let { a ->
        Text(
            stringResource(R.string.fonts_user_hint),
            Modifier.anchored(a, LocalFrame.current) { r, _, _ -> Offset(0f, r.bottom + HINT_GAP.toPx()) }.fillMaxWidth().wrapContentWidth().widthIn(max = HINT_W)
                .background(Color.White, SmallShape).border(1.dp, Grey, SmallShape).plainClickable { info = null }.padding(8.dp),
            Color.Black, fontSize = 14.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FontTabBtn(t: FontTab, on: Boolean, modifier: Modifier, onInfo: ((Rect) -> Unit)?, onClick: () -> Unit) = Row(
    modifier.fillMaxHeight().clip(SmallShape).background(if (on) TranslucentGray else Color.Transparent).border(1.dp, Grey, SmallShape).plainClickable(onClick).padding(TAB_PAD),
    Arrangement.spacedBy(TAB_PAD, Alignment.CenterHorizontally), Alignment.CenterVertically
) {
    Text(stringResource(t.title), color = Color.Black, fontSize = TAB_SP, lineHeight = 16.sp, textAlign = TextAlign.Center, softWrap = false)
    if (onInfo != null) {
        val bounds = rememberBounds()
        Icon(AppIcons.Info, null, Modifier.trackBounds(bounds).size(INFO_ICON).plainClickable { onInfo(bounds.rect) }, Color.Black)
    }
}

@Composable
private fun FontItem(f: AppFont, chosen: Boolean, tab: FontTab, o: TextOptions, onPick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(FONT_ROW_H).clip(SmallShape).then(if (chosen) Modifier.border(2.dp, TranslucentGray, SmallShape) else Modifier).plainClickable(onPick).padding(horizontal = ITEM_PAD),
    Arrangement.spacedBy(PAD), Alignment.CenterVertically
) {
    if (tab == FontTab.Favorites) ToolBtn(AppIcons.Delete, Color.Black, indication = null) { o.toggleFavorite(f.key) }
    else ToolBtn(AppIcons.StarFilled, if (f.key in o.favorites) Yellow else Grey, indication = null) { o.toggleFavorite(f.key) }
    Text(f.name, Modifier.weight(1f), Color.Black, fontSize = NAME_SP, fontFamily = remember(f) { f.family() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun AppFont.family() = FontFamily(Typeface(typeface))

/** A lazy list can't report its width: measured with the Texts' style. All fonts: no jump on another tab */
@Composable
private fun rememberPickerWidth(fonts: Fonts): Dp {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    val titles = FontTab.entries.map { stringResource(it.title) }
    return remember(fonts, titles, style, density) {
        with(density) {
            fun width(text: String, s: TextStyle) = measurer.measure(text, s, softWrap = false).size.width.toDp()
            val tab = FontTab.entries.maxOf { t -> width(titles[t.ordinal], style.copy(fontSize = TAB_SP)) + if (t == FontTab.User) TAB_PAD + INFO_ICON else 0.dp } + TAB_PAD * 2
            val name = (fonts.system + fonts.user).maxOf { f -> width(f.name, style.copy(fontSize = NAME_SP, fontFamily = f.family())) }
            // +1dp: the parts round to px one by one
            (maxOf(tab * 3 + PAD * 4, name + ITEM_PAD * 2 + FIELD_H + PAD * 3) + 1.dp).coerceAtMost(PICKER_MAX)
        }
    }
}
