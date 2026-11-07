package com.perkus.imagoedit.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.perkus.imagoedit.data.Project
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
import com.perkus.imagoedit.ui.theme.TranslucentGray
import java.io.File

/** Non-modal: the grid stays interactive */
@Composable
fun ExportPanel(s: ExportSettings, busy: Boolean, project: Project?, last: File?, onChange: (ExportSettings) -> Unit, onCancel: () -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier) {
    var info by remember { mutableStateOf(false) }
    val canExport = project != null
    // The border sets the card apart from the bar it may cover (both BarBg)
    DialogCard(stringResource(R.string.export_image), modifier, titleEnd = { IconBtn(AppIcons.Info) { info = true } }, borderColor = ScreenBg) {
        ButtonRow { ExportFormat.entries.forEach { f -> DialogButton(f.label, if (f == s.format) BtnGreen else BtnGray, Modifier.weight(1f)) { if (!busy) onChange(s.copy(format = f)) } } }
        if (s.format != ExportFormat.PNG) ParamRow(stringResource(R.string.quality), s.quality, EXPORT_MIN_QUALITY..100, !busy, "${s.quality}%") { onChange(s.copy(quality = it)) }
        if (!busy) OkCancel(onCancel, if (canExport) BtnGreen else BtnGray, okEnabled = canExport, onOk = onExport)
        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = BtnGreen, trackColor = BtnGray)
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            last?.let { f ->
                Text(stringResource(R.string.last_export), Modifier.align(Alignment.Start), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    FileField(f.nameWithoutExtension, TranslucentGray, Color.White, Modifier.weight(1f))
                    FileField(f.extension, Color.White, Color.Black)
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

@Composable
private fun FileField(text: String, color: Color, textColor: Color, modifier: Modifier = Modifier) =
    Box(modifier.background(color, SmallShape).padding(horizontal = 8.dp, vertical = 4.dp), Alignment.Center) {
        Text(text, color = textColor, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }