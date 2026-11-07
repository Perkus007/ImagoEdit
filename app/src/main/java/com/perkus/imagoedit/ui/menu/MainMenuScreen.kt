package com.perkus.imagoedit.ui.menu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.perkus.imagoedit.AppViewModel
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.ExportSettings
import com.perkus.imagoedit.data.Project
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.AppTitle
import com.perkus.imagoedit.ui.common.BAR_H
import com.perkus.imagoedit.ui.common.Bar
import com.perkus.imagoedit.ui.common.BusyOverlay
import com.perkus.imagoedit.ui.common.DIALOG_W
import com.perkus.imagoedit.ui.common.DialogButton
import com.perkus.imagoedit.ui.common.DialogCard
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.OkCancel
import com.perkus.imagoedit.ui.common.TextInputDialog
import com.perkus.imagoedit.ui.common.rememberCheckerBrush
import com.perkus.imagoedit.ui.common.rememberImagePicker
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.BtnRed
import com.perkus.imagoedit.ui.theme.SmallShape
import java.io.File

private const val EXPORT_COLS = 2 // колонок под панелью экспорта

@Composable
fun MainMenuScreen(vm: AppViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var selected by remember { mutableStateOf<Set<Long>?>(null) } // null — normal, Set — delete mode
    var exporting by remember { mutableStateOf(false) }
    var exportId by remember { mutableStateOf<Long?>(null) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    var lastExport by remember { mutableStateOf<File?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) } // survives recreation (language change)
    val appContext = LocalContext.current.applicationContext // export may outlive the Activity
    val checker = rememberCheckerBrush(4.dp)
    val pickImage = rememberImagePicker(vm::importImage)
    val ready = selected == null && !exporting && !vm.busy
    val idle = vm.hasAccess && ready

    // The bar overlays the grid: the export panel may cover it and takes the whole height
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // In px: tiles don't shift when the panel opens (GridCells.Fixed would spread the remainder); the panel takes the last EXPORT_COLS columns
        val density = LocalDensity.current
        val w = constraints.maxWidth
        val gap = with(density) { 16.dp.roundToPx() }
        val cols = ((w - gap) / (with(density) { 140.dp.roundToPx() } + gap)).coerceAtLeast(1)
        val step = (w - gap) / cols
        val gridCols = if (exporting && cols > EXPORT_COLS) cols - EXPORT_COLS else cols
        Bar {
            IconBtn(AppIcons.Settings, ready) { showSettings = true }
            AppTitle(Modifier.weight(1f))
            IconBtn(AppIcons.Add, idle) { showNew = true }
            IconBtn(AppIcons.AddImage, idle, pickImage)
            IconBtn(AppIcons.FileUp, idle) { exportId = null; exporting = true }
            IconBtn(AppIcons.Delete, idle) { selected = emptySet() }
        }

        Box(Modifier.fillMaxSize().padding(top = BAR_H)) {
            if (!vm.hasAccess) {
                AccessWarning(Modifier.align(Alignment.Center))
                return@Box
            }

            LazyVerticalGrid(
                GridCells.Fixed(gridCols), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 16.dp, with(density) { (w - gridCols * step).toDp() }, if (selected == null) 16.dp else 140.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(vm.projects, key = { it.id }) { p ->
                    val frame = when {
                        selected?.contains(p.id) == true -> BtnRed
                        exporting && exportId == p.id -> BtnGreen
                        else -> null
                    }
                    ProjectTile(p, checker, vm, frame, onClick = {
                        val s = selected
                        when {
                            exporting -> if (!vm.busy) exportId = p.id
                            s == null -> vm.open(p)
                            else -> selected = if (p.id in s) s - p.id else s + p.id
                        }
                    },
                        onRename = { if (selected == null) renaming = p }
                    )
                }
            }

            if (vm.busy && !exporting) BusyOverlay()

            selected?.let { sel ->
                DialogCard(stringResource(R.string.delete_projects), Modifier.align(Alignment.BottomCenter).padding(16.dp).width(DIALOG_W)) {
                    OkCancel(onCancel = { selected = null }, okColor = BtnRed) { vm.delete(sel); selected = null }
                }
            }
        }

        if (exporting && vm.hasAccess) {
            val project = vm.projects.firstOrNull { it.id == exportId }
            ExportPanel(
                exportSettings, busy = vm.busy, project = project, last = lastExport,
                onChange = { exportSettings = it },
                onCancel = { exporting = false },
                onExport = { project?.let { vm.export(appContext, it, exportSettings) { f -> lastExport = f } } },
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(top = 16.dp, end = with(density) { (w - cols * step).toDp() }, bottom = 16.dp)
                    .width(with(density) { (minOf(EXPORT_COLS, cols) * step - gap).toDp() })
            )
        }
    }
    if (showSettings) SettingsScreen { showSettings = false }
    if (showNew) CanvasSizeDialog(R.string.new_project, 1024, 1024, onDismiss = { showNew = false }) { w, h -> showNew = false; vm.create(w, h) }
    renaming?.let { p -> TextInputDialog(p.name, onDismiss = { renaming = null }) { vm.rename(p.id, it); renaming = null } }
}

@Composable
private fun ProjectTile(p: Project, checker: Brush, vm: AppViewModel, frame: Color?, onClick: () -> Unit, onRename: () -> Unit) = Column {
    val bitmap by produceState(vm.cachedThumb(p), p.id, p.updated) { value = vm.thumb(p) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).then(if (frame != null) Modifier.centeredBorder(3.dp, frame) else Modifier).clip(SmallShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        // Half the border: its outer half stays inside the tile's clip
        Box(Modifier.padding(1.dp).aspectRatio(p.width.toFloat() / p.height).centeredBorder(2.dp, BarBg).clip(SmallShape).background(checker)) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
    }
    Text(p.name, Modifier.clickable(onClick = onRename).padding(vertical = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun Modifier.centeredBorder(width: Dp, color: Color) = drawWithCache {
    val outline = SmallShape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(width.toPx())
    onDrawWithContent { drawContent(); drawOutline(outline, color, style = stroke) }
}

@Composable
private fun AccessWarning(modifier: Modifier) {
    val context = LocalContext.current
    DialogCard(stringResource(R.string.no_access_title), modifier.width(DIALOG_W)) {
        Text(stringResource(R.string.no_access_text), fontSize = 14.sp, textAlign = TextAlign.Center)
        DialogButton(stringResource(R.string.grant_access), BtnGreen, Modifier.fillMaxWidth()) { context.openAccessSettings() }
    }
}

/** This app's access page; missing on some firmwares → the app list */
private fun Context.openAccessSettings() {
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
    } catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}