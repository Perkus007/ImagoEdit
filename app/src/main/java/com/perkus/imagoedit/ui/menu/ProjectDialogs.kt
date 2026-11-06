package com.perkus.imagoedit.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.MAX_PIXELS
import com.perkus.imagoedit.data.MAX_SIDE
import com.perkus.imagoedit.data.MIN_SIDE
import com.perkus.imagoedit.data.SAFE_PIXELS
import com.perkus.imagoedit.ui.common.AppDialog
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.DialogButton
import com.perkus.imagoedit.ui.common.NumField
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.SmallShape
import kotlin.math.sqrt

/** A new project's canvas or the open one's new size: [width]×[height] at first */
@Composable
fun CanvasSizeDialog(title: Int, width: Int, height: Int, onDismiss: () -> Unit, busy: Boolean = false, onOk: (width: Int, height: Int) -> Unit) {
    val dims = remember { mutableStateListOf(width, height) }
    var link by remember { mutableStateOf<List<Int>?>(listOf(width, height)) } // the sides whose ratio is kept; null — free

    /** Side [i] becomes [v] as far as the limits let it. Free: the other one shrinks to keep the area within MAX_PIXELS.
    Linked: the other one follows by [link]'s ratio, [v] limited so both fit (not coerceIn: a ratio the limits can't hold, e.g. an imported 1×16000, must not throw) */
    fun put(i: Int, v: Int) {
        val l = link
        if (l == null) {
            dims[i] = v.coerceIn(MIN_SIDE, MAX_SIDE)
            dims[1 - i] = minOf(dims[1 - i], MAX_PIXELS / dims[i])
            return
        }
        val a = l[1 - i].toLong() // the other side per b px of this one
        val b = l[i].toLong()
        val hi = minOf(MAX_SIDE.toLong(), MAX_SIDE * b / a, sqrt(MAX_PIXELS * b / a.toDouble()).toLong())
        dims[i] = v.toLong().coerceAtMost(hi).coerceAtLeast(maxOf(MIN_SIDE.toLong(), (MIN_SIDE * b + a - 1) / a)).toInt()
        dims[1 - i] = ((dims[i] * a + b / 2) / b).toInt().coerceIn(MIN_SIDE, minOf(MAX_SIDE, MAX_PIXELS / dims[i]))
    }

    AppDialog(stringResource(title), onDismiss, width = null, busy = busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.width, R.string.height).forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${stringResource(label)}:", Modifier.width(90.dp), fontSize = 18.sp, textAlign = TextAlign.End)
                        NumField("${dims[i]}", Modifier.width(80.dp).background(ScreenBg, SmallShape), PaddingValues(4.dp), fontSize = 18.sp) { put(i, it.toInt()) }
                        // The most this side gets: free — the other one kept, linked — the ratio kept. Same font size and vertical padding as the field → same height at any font scale
                        Text("MAX", Modifier.clip(SmallShape).background(BtnGray).clickable { put(i, if (link == null) MAX_PIXELS / dims[1 - i] else MAX_SIDE) }
                            .padding(horizontal = 8.dp, vertical = 4.dp), Color.White, fontSize = 18.sp)
                    }
                }
            }
            Icon(if (link == null) AppIcons.UnlinkVertical else AppIcons.LinkVertical, null,
                Modifier.clip(SmallShape).background(BtnGray).clickable { link = if (link == null) dims.toList() else null }.padding(4.dp).size(24.dp), Color.White)
        }
        Text(stringResource(R.string.canvas_limit, MAX_PIXELS / 1_000_000, SAFE_PIXELS / 1_000_000), Modifier.fillMaxWidth(), fontSize = 14.sp, textAlign = TextAlign.Center)
        DialogButton(stringResource(R.string.ok), BtnGreen, Modifier.fillMaxWidth()) { onOk(dims[0], dims[1]) }
    }
}
