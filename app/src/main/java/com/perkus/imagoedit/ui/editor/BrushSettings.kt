package com.perkus.imagoedit.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.tip
import com.perkus.imagoedit.ui.common.AppIcons

private val PREVIEW_H = 100.dp // в неё вписан круг наибольшего размера
private const val PREVIEW_STOPS = 65 // стопы профиля кисти, как у даба
private const val EDGE_WALL_MAX = 192 // наибольший «Предел» «С учётом краёв»; «Допуск» — не больше него
private val previewPaint = android.graphics.Paint().apply { isAntiAlias = false } // Compose рисует только со сглаживанием

/** name is part of the prefs key */
enum class BrushParam(val label: Int, val range: IntRange, val suffix: String, val default: Int) {
    Size(R.string.brush_size, 1..100, "", 20),
    Hardness(R.string.brush_hardness, 0..100, "%", 100),
    Paint(R.string.brush_paint, 0..100, "%", 100),
    Opacity(R.string.opacity, 0..100, "%", 100),
    Intensity(R.string.intensity, 0..100, "%", 50)
}

/** [params] — the tool's: only they get rows; the rest stay at their defaults. [edges] — «С учётом краёв» (BrushTool), off where its panel has no EdgesCell;
[edgeFull] ≤ [edgeWall] — its channel differences: px fully like the sample up to the first, not at all past the second; [hardEdge] — no Limit, the edge is
a step past Tolerance */
@Stable
class BrushOptions(settings: AppSettings, key: String, val params: List<BrushParam>, val hardEdge: Boolean = false) {
    private val values = BrushParam.entries.map { IntOption(settings, "$key.${it.name}", if (it in params) it.range else it.default..it.default, it.default) }
    val edges = BoolOption(settings, "$key.edges", false)
    val edgeFull = IntOption(settings, "$key.edgeFull", 0..EDGE_WALL_MAX, 48)
    val edgeWall = IntOption(settings, "$key.edgeWall", 0..EDGE_WALL_MAX, 64)
    val edgeTolerance get() = edgeFull.value..if (hardEdge) edgeFull.value else maxOf(edgeWall.value, edgeFull.value)
    operator fun get(p: BrushParam) = values[p.ordinal]
}

@Composable
fun BrushSettings(b: BrushOptions, params: List<BrushParam> = b.params) = Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
    // Read in the draw phase: sliders don't recompose
    Box(Modifier.fillMaxWidth().height(PREVIEW_H).drawBehind {
        val r = size.height / 2 * b[BrushParam.Size].value / BrushParam.Size.range.last
        val hard = b[BrushParam.Hardness].value / 100f
        val a = b[BrushParam.Paint].value / 100f * b[BrushParam.Opacity].value / 100f
        previewPaint.shader = RadialGradient(center.x, center.y, r, IntArray(PREVIEW_STOPS) { Color.Black.copy(alpha = a * tip(it / (PREVIEW_STOPS - 1f), hard)).toArgb() }, null,
            Shader.TileMode.CLAMP)
        drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, r, previewPaint)
    })
    params.forEach { ParamRow(stringResource(it.label), b[it], it.suffix) }
}

@Composable
fun BrushBtn(b: BrushOptions, open: ToolPopup?, onOpen: (OpenPopup) -> Unit, params: List<BrushParam> = b.params) = PopupBtn(ToolPopup.Brush, AppIcons.Slider, open, onOpen) { BrushSettings(b, params) }

/** «С учётом краёв» switch; on — below it the button of its Tolerance and Limit (one pushes the other past it), a hard edge's — Tolerance alone */
@Composable
fun EdgesCell(b: BrushOptions, open: ToolPopup?, onOpen: (OpenPopup) -> Unit) {
    SwitchCell(b.edges, stringResource(R.string.brush_edges))
    if (b.edges.value) PopupBtn(ToolPopup.Tolerance, AppIcons.ChartIncreasing, open, onOpen) {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
            if (b.hardEdge) ParamRow(stringResource(R.string.tolerance), b.edgeFull)
            else {
                ParamRow(stringResource(R.string.tolerance), b.edgeFull) { b.edgeFull.value = it; if (b.edgeWall.value < b.edgeFull.value) b.edgeWall.value = b.edgeFull.value }
                ParamRow(stringResource(R.string.edge_limit), b.edgeWall) { b.edgeWall.value = it; if (b.edgeFull.value > b.edgeWall.value) b.edgeFull.value = b.edgeWall.value }
            }
        }
    }
}
