package com.perkus.imagoedit.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.EXPORT_MIN_QUALITY
import com.perkus.imagoedit.data.EXPORT_PATH
import com.perkus.imagoedit.data.ExportFormat
import com.perkus.imagoedit.data.ExportSettings
import com.perkus.imagoedit.ui.common.AppDialog
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppSlider
import com.perkus.imagoedit.ui.common.ButtonRow
import com.perkus.imagoedit.ui.common.DialogButton
import com.perkus.imagoedit.ui.common.DialogCard
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.OkCancel
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SideBarBg
import com.perkus.imagoedit.ui.theme.SmallShape
import java.io.File

private const val VISIBLE_FILES = 5 // строк списка файлов без прокрутки
private val FILE_ROW = 20.sp // высота строки списка: в sp, растёт с размером шрифта

/** Non-modal: the grid stays interactive. [files] — exported, newest first */
@Composable
fun ExportPanel(s: ExportSettings, busy: Boolean, count: Int, files: List<File>, onChange: (ExportSettings) -> Unit, onCancel: () -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier) {
    var info by remember { mutableStateOf(false) }
    val canExport = count > 0
    val list = rememberLazyListState()
    LaunchedEffect(files.size) { if (files.isNotEmpty()) list.scrollToItem(0) }
    // The border sets the card apart from the bar it may cover (both BarBg)
    DialogCard(stringResource(R.string.export_image), modifier, titleEnd = { IconBtn(AppIcons.Info) { info = true } }, borderColor = ScreenBg) {
        ButtonRow { ExportFormat.entries.forEach { f -> DialogButton(f.label, if (f == s.format) BtnGreen else BtnGray, Modifier.weight(1f)) { if (!busy) onChange(s.copy(format = f)) } } }
        if (s.format != ExportFormat.PNG) ParamRow(stringResource(R.string.quality), s.quality, EXPORT_MIN_QUALITY..100, !busy, "${s.quality}%") { onChange(s.copy(quality = it)) }
        if (!busy) OkCancel(onCancel, if (canExport) BtnGreen else BtnGray, okEnabled = canExport, okText = stringResource(R.string.ok_count, count), onOk = onExport)
        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = BtnGreen, trackColor = BtnGray)
        // Short screen: the list shrinks first, the path stays
        Column(Modifier.weight(1f, fill = false).fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            if (files.isNotEmpty()) {
                Text(stringResource(R.string.last_export), Modifier.align(Alignment.Start), fontSize = 12.sp)
                val rowH = with(LocalDensity.current) { FILE_ROW.toDp() }
                val pad = 4.dp
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = rowH * VISIBLE_FILES + pad * 2).clip(SmallShape).background(ScreenBg),
                    list, PaddingValues(8.dp, pad), overscrollEffect = null) {
                    items(files) { f ->
                        // Extension apart: a long name is cut, the extension stays
                        Row(Modifier.height(rowH), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.nameWithoutExtension, Modifier.weight(1f, fill = false), fontSize = 14.sp, lineHeight = FILE_ROW, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(".${f.extension}", color = BtnGreen, fontSize = 14.sp, lineHeight = FILE_ROW, maxLines = 1)
                        }
                    }
                }
            }
            Text(stringResource(R.string.export_path, EXPORT_PATH), fontSize = 12.sp)
        }
    }
    if (info) AppDialog(stringResource(R.string.info_title), { info = false }, width = null, borderColor = SideBarBg) {
        listOf(R.string.min_sdk_avif_decode, R.string.export_warn_avif, R.string.export_warn_memory).forEach { Text(stringResource(it), fontSize = 14.sp, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun ParamRow(label: String, value: Int, range: IntRange, enabled: Boolean, text: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), fontSize = 14.sp)
        AppSlider(value, onChange, range, Modifier.weight(1f), enabled)
        Text(text, Modifier.width(44.dp), fontSize = 14.sp, textAlign = TextAlign.End)
    }
}
