package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Point
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.Base
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.Correction
import com.perkus.imagoedit.data.Feather
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.Figure
import com.perkus.imagoedit.data.Guide
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.Joined
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.MAX_PCT
import com.perkus.imagoedit.data.MaskOverlay
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.Recolor
import com.perkus.imagoedit.data.PERCENT
import com.perkus.imagoedit.data.Relight
import com.perkus.imagoedit.data.SIGNED
import com.perkus.imagoedit.data.SAMPLES
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.Tone
import com.perkus.imagoedit.data.WORKERS
import com.perkus.imagoedit.data.asPath
import com.perkus.imagoedit.data.built
import com.perkus.imagoedit.data.canvasToFile
import com.perkus.imagoedit.data.clampPad
import com.perkus.imagoedit.data.composite
import com.perkus.imagoedit.data.drawMasked
import com.perkus.imagoedit.data.drawTiles
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileRect
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.keepPaint
import com.perkus.imagoedit.data.levelCount
import com.perkus.imagoedit.data.levelFor
import com.perkus.imagoedit.data.mark
import com.perkus.imagoedit.data.parallel
import com.perkus.imagoedit.data.patchTile
import com.perkus.imagoedit.data.saveClipLayer
import com.perkus.imagoedit.data.tilePaint
import com.perkus.imagoedit.data.tilesAlong
import com.perkus.imagoedit.data.toneOf
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.DISABLED_ALPHA
import com.perkus.imagoedit.ui.common.plainClickable
import com.perkus.imagoedit.ui.theme.BarBg
import com.perkus.imagoedit.ui.theme.SmallShape
import com.perkus.imagoedit.ui.theme.TranslucentGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2

private const val PREVIEW_PX = 1 shl 22 // «Заменить цвет»: px превью всей области (≈ экран, мельче — её копии); резче — только видимая часть
private const val VIEW_MARGIN = 8 // запас превью видимой части: 1/8 её стороны с каждого края
private val CAROUSEL_H = 128.dp
private val GAP = 8.dp
private val TILE_W = 136.dp
private val IMAGE_H = 84.dp // картинка плитки: 408×252 px в drawable-xxhdpi
private val BTN = 32.dp // = ToolBtn
private val PANEL_W = 480.dp
private val PAGE_ANIM = tween<IntOffset>(PANEL_ANIM_DURATION_MS)
private val TONES = listOf(R.string.correction_highlights, R.string.correction_shadows, R.string.correction_whites, R.string.correction_blacks) // по 2 на страницу
private val CHANNELS = listOf(R.string.color_red, R.string.color_green, R.string.color_blue)
private val REPLACE = listOf(R.string.intensity, R.string.tolerance)

/** Replace color's settings, persisted at once; [contiguous] — for the session: it needs the pipette's points. [samples] count only where [used] (null — always:
the first; the rest off by default) */
@Stable
class CorrectionOptions(settings: AppSettings) {
    val samples = List(SAMPLES) { ColorOptions(settings, "replace.from$it") }
    val used = List(SAMPLES) { if (it == 0) null else BoolOption(settings, "replace.used$it", false) }
    val to = ColorOptions(settings, "replace.to")
    val toOpacity = IntOption(settings, "replace.toOpacity", 0..MAX_OPACITY, MAX_OPACITY)
    val amount = IntOption(settings, "replace.amount", PERCENT, MAX_PCT)
    val threshold = IntOption(settings, "replace.threshold", PERCENT, MAX_PCT / 2)
    var contiguous by mutableStateOf(false)

    private val on get() = samples.filterIndexed { i, _ -> used[i]?.value != false }

    /** Where the used samples were picked: Contiguous's seeds; none — Contiguous is off */
    val seeds get() = on.mapNotNull { it.picked }

    /** Replace color's; reads each setting's state */
    fun recolor() = Recolor(on.map { it.value.toArgb() }, to.value.toArgb(), toOpacity.value / MAX_OPACITY.toFloat(), threshold.value / MAX_PCT.toFloat(),
        amount.value / MAX_PCT.toFloat(), seeds.takeIf { contiguous && it.isNotEmpty() }?.map { Point(floor(it.x).toInt(), floor(it.y).toInt()) })
}

/** File px [rect] at 2ⁿ× fewer px a side */
private data class Spot(val rect: Rect, val n: Int)

/** A computed preview of the layer's px [at]: [px] — the layer's raster at at's level, only tiles about at's rect; its smaller copies or none */
private class Grid(val at: Spot, val px: Tiles) {
    /** In file px, from the copy for [k] px per file px, as the layer's; coarser than the layer's own px — always filtered */
    fun draw(c: Canvas, p: Paint, filter: Boolean, r: RectF, k: Float) {
        val j = (levelFor(k) - at.n).coerceIn(0, px.levels.lastIndex)
        p.isFilterBitmap = filter || at.n + j > 0
        c.drawTiles(px.levels[j], px.cols(j), (1 shl (at.n + j)).toFloat(), p, r)
    }

    fun recycle() = px.recycle()
}

/** A computed preview shown: [coarse] — of the whole area, with smaller copies; [fine] — of the part in view, as sharp as the layer is drawn */
private class Previews {
    var coarse by mutableStateOf<Grid?>(null)
    var fine by mutableStateOf<Grid?>(null)
}

/** These TILE² px (RGBA bytes) averaged 2ⁿ×2ⁿ, as the layer's smaller copies, into [out] (as large) from its px ([ox], [oy]) */
private fun ByteArray.shrinkInto(n: Int, out: ByteArray, ox: Int, oy: Int) {
    if (n == 0) { copyInto(out); return }
    val m = 1 shl n
    val half = m * m / 2
    val q = TILE shr n
    for (y in 0 until q) for (x in 0 until q) {
        val o = 4 * ((oy + y) * TILE + ox + x)
        for (c in 0 until 4) {
            var sum = half
            for (j in 0 until m) {
                val row = 4 * ((y * m + j) * TILE + x * m) + c
                for (i in 0 until m) sum += this[row + 4 * i].toInt() and 0xFF
            }
            out[o + c] = (sum shr 2 * n).toByte()
        }
    }
}

/** The carousel's corrections of layer [l] within [sel] (null — all of it), previewed together in the layer (MaskOverlay), baked as one brush stroke as it closes:
Shadows/Highlights on the layer's px, then the one-slider ones in Correction's order, Color balance last. Replace color — alone, on the layer as it is: the rest are
hidden while its panel is open. Replace color, Shadows/Highlights preview off the main thread from the layer's px [src], [srcMask], [srcPatch] */
@Stable
class CorrectionTool(l: Layer, private val sel: Selection?, private val src: Bitmap?, private val srcMask: LayerMask?, private val srcPatch: Tiles?) : MaskOverlay {
    val layer = l.id
    private val file = l.file
    private val bakes = l.hasImage
    private val fw = l.fileW
    private val fh = l.fileH
    private val rect = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    private val toFile = l.canvasToFile()
    private val fromFile = l.fileToCanvas()

    /** The panel shown; null — the carousel */
    var open by mutableStateOf<Correction?>(null)

    /** The one-slider ones' by Correction.ordinal; [tones] — Shadows/Highlights' by TONES; [balance] — Color balance's by CHANNELS */
    val values = List(Correction.entries.size) { 0 }.toMutableStateList()
    val tones = List(TONES.size) { 0 }.toMutableStateList()
    val balance = List(CHANNELS.size) { 0 }.toMutableStateList()

    private val replacing get() = open == Correction.ReplaceColor

    private val area = sel.fileRect(l)
    private val all = Spot(area, maxOf(0, ceil(log2(area.width().toFloat() * area.height() / PREVIEW_PX) / 2).toInt())) // coarse's

    private val recoloring = Previews()
    private val relighting = Previews()
    private val view = MutableStateFlow<Spot?>(null) // what fine is to cover: set in the draw phase

    /** A worker's scratch in preview's work (see parallel) */
    private inner class Reader {
        private val c = Canvas()
        private val mp = MaskPaints()
        val px = patchTile(src)
        val raw = ByteArray(4 * TILE * TILE)
        val out = ByteArray(4 * TILE * TILE)
        val only by lazy(LazyThreadSafetyMode.NONE) { ByteArray(TILE * TILE) }

        /** The layer's px from file px ([x], [y]) at full size, as the bake reads them */
        fun read(x: Int, y: Int) = px.also { c.composite(it, x, y, src, fw, fh, srcMask, srcPatch, mp) }
    }

    private val readers = arrayOfNulls<Reader>(WORKERS)

    private fun reader(wk: Int) = readers[wk] ?: Reader().also { readers[wk] = it }

    private val tone by derivedStateOf { toneOf(values, balance) }

    private fun relight() = tones.map { it / MAX_PCT.toFloat() }.let { (h, s, l, d) -> Relight(h, s, l, d) }

    /** Read in the draw phase */
    val shows get() = bakes && (if (replacing) recoloring.coarse != null else relighting.coarse != null || tone != null)

    /** Replace color's while its panel is open, else the rest; null — nothing to change */
    fun stroke(o: CorrectionOptions): Stroke? {
        val r = if (replacing) o.recolor().takeUnless { it.idle } else null
        val h = if (replacing) null else relight().takeUnless { it.idle }
        val t = if (replacing) null else tone
        return if (bakes && (r != null || h != null || t != null)) Stroke(layer, file, null, area = sel, figure = Figure(rect.asPath()), tone = t, recolor = r, relight = h)
        else null
    }

    /** While running: Replace color's preview while its panel is open, else Shadows/Highlights' while its panel is open or it changes anything; one ends before the
    next starts. For the latest settings — of the whole area at ≤ PREVIEW_PX with smaller copies and, when the layer is drawn sharper, of the part in view */
    suspend fun preview(o: CorrectionOptions) {
        if (!bakes || area.isEmpty) return
        snapshotFlow { if (replacing) Correction.ReplaceColor else Correction.ShadowsHighlights.takeIf { open == it || tones.any { t -> t != 0 } } }.collectLatest { k ->
            view.value = null
            try {
                when (k) {
                    Correction.ReplaceColor -> {
                        var made: Recolor? = null // the previews' settings
                        var joinedFor: Recolor? = null // the settings Contiguous's px are for: only the samples, threshold and seeds count
                        var joined: Joined? = null
                        // Smaller px recolored would lose small replaced spots: recolored at full size, as the bake
                        shown(recoloring, { o.recolor() }, Recolor::idle) { r, v, c0, f0 ->
                            val key = r.copy(to = 0, opacity = 0f, amount = 0f)
                            if (key != joinedFor) { joined = r.joined(area, toFile) { wk, x, y -> reader(wk).read(x, y) }; joinedFor = key }
                            val j = joined
                            fun swapped(at: Spot, copies: Boolean) =
                                grid(at, sampled(at, copies) { wk, px, x, y -> r.swap(px, only = j?.let { o -> reader(wk).only.also { o.at(x, y, it) } }) }, copies)
                            val redo = r != made || c0 == null
                            ((if (redo) swapped(all, true) else c0) to (if (!redo && f0?.at == v) f0 else v?.let { swapped(it, false) })).also { made = r }
                        }
                    }
                    Correction.ShadowsHighlights -> {
                        var whole: Grid? = null // the area's px as they are, as coarse's
                        var part: Grid? = null // the part in view's, as fine's
                        try {
                            // At once, before a slider moves: the layer's px and their surroundings take the longest. The surroundings — of the full-size px, as the
                            // bake's; smooth in px, so relit at the preview's own px: a slider moved doesn't read the layer again
                            val base = withContext(Dispatchers.Default) {
                                val g = Guide(area)
                                whole = grid(all, sampled(all, true) { wk, px, x, y -> g.add(wk, px, x, y) }, false)
                                g.base()
                            }
                            var made: Relight? = null // the previews' settings
                            shown(relighting, { relight() }, Relight::idle) { r, v, c0, f0 ->
                                if (part?.at != v) { part?.recycle(); part = v?.let { grid(it, sampled(it, false) { _, _, _, _ -> }, false) } }
                                val redo = r != made || c0 == null
                                ((if (redo) whole?.let { relit(it, r, base, true) } else c0) to (if (!redo && f0?.at == v) f0 else part?.let { relit(it, r, base, false) }))
                                    .also { made = r }
                            }
                        } finally {
                            whole?.recycle()
                            part?.recycle()
                        }
                    }
                    else -> {}
                }
            } finally {
                readers.forEach { it?.px?.recycle() }
                readers.fill(null)
            }
        }
    }

    /** Shows in [p] the previews [make] gives, off the main thread, for the latest settings [of] (none while [idle]) and part in view, from the previews shown */
    private suspend fun <T> shown(p: Previews, of: () -> T, idle: (T) -> Boolean, make: (settings: T, view: Spot?, coarse: Grid?, fine: Grid?) -> Pair<Grid?, Grid?>) {
        try {
            combine(snapshotFlow(of), view, ::Pair).conflate().collect { (r, v) ->
                val c0 = p.coarse
                val f0 = p.fine
                val (c, f) = if (idle(r)) null to null else withContext(Dispatchers.Default) { make(r, v, c0, f0) }
                p.coarse = c
                p.fine = f
                if (c0 !== c) c0?.recycle()
                if (f0 !== f) f0?.recycle()
            }
        } finally {
            val shown = listOfNotNull(p.coarse, p.fine)
            p.coarse = null
            p.fine = null
            shown.forEach { it.recycle() }
        }
    }

    /** The layer's px about [v] at full size, each tile through [full] (its px from file px x, y; [worker]'s), then averaged as the layer's smaller copies, into new
    tiles at v's level: the old may still be on screen. Past the layer its edge px repeat, as a clamped texture's. [around] — tiles so far around that smaller
    copies of them, drawn filtered, average only real px about v's rect. Off the main thread */
    private fun sampled(v: Spot, around: Boolean, full: (worker: Int, px: Bitmap, x: Int, y: Int) -> Unit): Array<Bitmap?> {
        val n = v.n
        val m = 1 shl n
        val lw = (fw + m - 1) shr n
        val lh = (fh + m - 1) shr n
        val cols = tilesAlong(fw, n)
        val rows = tilesAlong(fh, n)
        val reach = Rect(v.rect).apply { if (around) (m shl levelCount(lw, lh)).let { inset(-it, -it) } }
        val touched = BooleanArray(cols * rows)
        touched.mark(cols, rows, (reach.left shr n).toFloat(), (reach.top shr n).toFloat(), ((reach.right - 1) shr n).toFloat(), ((reach.bottom - 1) shr n).toFloat())
        val ks = touched.indices.filter { touched[it] }
        val tiles = arrayOfNulls<Bitmap>(touched.size)
        parallel(ks.size) { wk, i ->
            val d = reader(wk)
            val tx = ks[i] % cols * TILE
            val ty = ks[i] / cols * TILE
            d.out.fill(0)
            for (j in 0 until m * m) {
                val sx = (tx shl n) + j % m * TILE
                val sy = (ty shl n) + j / m * TILE
                if (sx >= fw || sy >= fh) continue
                full(wk, d.read(sx, sy), sx, sy)
                d.px.copyPixelsToBuffer(ByteBuffer.wrap(d.raw))
                d.raw.clampPad(minOf(TILE, fw - sx), minOf(TILE, fh - sy), 4)
                d.raw.shrinkInto(n, d.out, j % m * (TILE shr n), j / m * (TILE shr n))
            }
            d.out.clampPad(minOf(TILE, lw - tx), minOf(TILE, lh - ty), 4)
            tiles[ks[i]] = patchTile(src).apply { copyPixelsFromBuffer(ByteBuffer.wrap(d.out)) }
        }
        return tiles
    }

    /** [tiles] at [v]'s level; [copies] — with smaller copies (then sampled around) */
    private fun grid(v: Spot, tiles: Array<Bitmap?>, copies: Boolean): Grid? {
        val lw = (fw + (1 shl v.n) - 1) shr v.n
        val lh = (fh + (1 shl v.n) - 1) shr v.n
        return (if (copies) built("", lw, lh, tiles) else Tiles("", lw, lh, listOf(tiles)))?.let { Grid(v, it) }
    }

    /** [g]'s px through [r] with the surroundings [base], into new tiles: the old may still be on screen. Off the main thread */
    private fun relit(g: Grid, r: Relight, base: Base?, copies: Boolean): Grid? {
        val t = g.px.levels[0]
        val cols = g.px.cols(0)
        val s = 1 shl g.at.n
        val ks = t.indices.filter { t[it] != null }
        val out = arrayOfNulls<Bitmap>(t.size)
        parallel(ks.size) { _, i ->
            val k = ks[i]
            out[k] = patchTile(src).also { r.adjust(t[k]!!, it, k % cols * TILE * s, k / cols * TILE * s, s, base) }
        }
        return grid(g.at, out, copies)
    }

    private val seen = Rect()

    /** Asks for a sharper preview of the part in view than [fine] when [whole]'s px are coarser than the layer's, drawn at [k] px per file px */
    private fun aim(c: Canvas, k: Float, whole: Grid, fine: Grid?) {
        val n = levelFor(k)
        if (n >= whole.at.n) { view.value = null; return }
        if (!c.getClipBounds(seen)) return
        seen.inset(-1, -1) // rounded to whole px, not out
        if (!seen.intersect(area)) return
        if (fine?.at?.let { it.n <= n && it.rect.contains(seen) } == true) return
        seen.inset(-seen.width() / VIEW_MARGIN, -seen.height() / VIEW_MARGIN)
        seen.intersect(area)
        view.value = Spot(Rect(seen), n)
    }

    private val paints = MaskPaints()
    private val solid = Paint()
    private val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val keep = keepPaint().apply { isFilterBitmap = true }
    private val plain = tilePaint()
    private val over = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val asIs = Tone(emptyList())
    private val cell = RectF()

    /** In its layer's offscreen, as the bake: the selected part erased, then added back corrected — the computed preview's px or the layer's. Draw phase */
    override fun draw(c: Canvas, l: Layer, filter: Boolean, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, k: Float) {
        val p = if (replacing) recoloring else relighting
        val g = p.coarse
        val fn = p.fine
        if (g != null) aim(c, k, g, fn)
        val t = if (replacing) null else tone
        if (g == null && t == null) return
        val f = sel?.feather
        c.save()
        sel?.let { s ->
            c.concat(toFile)
            if (f != null) c.clipRect(f.bounds) else c.clipPath(s.outline)
            c.concat(fromFile)
        }
        if (f == null) c.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        else {
            c.saveClipLayer(cut)
            c.drawPaint(solid)
            edge(c, f)
            c.restore()
        }
        (t ?: asIs).add(c, { u ->
            if (g == null) u.drawMasked(bmp, l.fileW, l.fileH, mask, patch, k, 255, filter, paints)
            else {
                u.save()
                u.clipRect(0, 0, fw, fh) // past it the tiles repeat the edge px
                g.draw(u, plain, filter, cell, k)
                fn?.draw(u, over, filter, cell, k)
                u.restore()
            }
        }) { if (f != null) edge(it, f) }
        c.restore()
    }

    /** Keeps the soft edge's share */
    private fun edge(c: Canvas, f: Feather) {
        c.save()
        c.concat(toFile)
        f.draw(c, keep)
        c.restore()
    }
}

/** Full-width, slides in from the bottom; [onPick] — a tile tapped. Keep it composed: images are decoded off the main thread at the first opening and kept, so opening
and scrolling don't stutter; the scroll position is kept too */
@Composable
fun CorrectionCarousel(shown: Boolean, modifier: Modifier, onPick: (Correction) -> Unit) {
    val res = LocalResources.current
    var images by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    val scroll = rememberLazyListState()
    if (shown && images == null) LaunchedEffect(Unit) { images = withContext(Dispatchers.IO) { Correction.entries.map { ImageBitmap.imageResource(res, it.image) } } }
    AnimatedVisibility(shown, modifier, PANEL_IN, PANEL_OUT) {
        // Its scrolling takes the presses: they don't reach the press-outside layer under it
        LazyRow(Modifier.fillMaxWidth().height(CAROUSEL_H).background(BarBg), scroll, PaddingValues(GAP), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            items(Correction.entries) { c -> CorrectionTile(c, images?.get(c.ordinal)) { onPick(c) } }
        }
    }
}

@Composable
private fun CorrectionTile(c: Correction, image: ImageBitmap?, onClick: () -> Unit) =
    Column(Modifier.width(TILE_W).fillMaxHeight().clip(SmallShape).border(1.dp, TranslucentGray, SmallShape).plainClickable(onClick)) {
        val area = Modifier.size(TILE_W, IMAGE_H)
        if (image != null) Image(image, null, area, contentScale = ContentScale.Crop) else Spacer(area)
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp), Alignment.Center) {
            Text(stringResource(c.title), color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

/** Runs the corrections' preview; the open one's panel, away while the color panel is open. [onCancel], [onConfirm] — Replace color's: the rest have none */
@Composable
fun CorrectionOverlay(s: CorrectionTool, o: CorrectionOptions, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    LaunchedEffect(s) { s.preview(o) }
    s.open?.let { k ->
        Box(Modifier.fillMaxSize()) {
            val away = open == ToolPopup.Color
            val shown = remember { MutableTransitionState(false) }
            LaunchedEffect(away) { shown.targetState = !away }
            AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
                val w = Modifier.width(PANEL_W)
                when (k) {
                    Correction.ReplaceColor -> ReplacePanel(o, picture, onPopup, w, onCancel, onConfirm)
                    Correction.ShadowsHighlights -> TonesPanel(s.tones, w)
                    Correction.ColorBalance -> BalancePanel(s.balance, w)
                    else -> BottomPanel(false, null, w, buttons = { CorrectionSlider(k.title, listOf(k.title), s.values[k.ordinal], k.range) { s.values[k.ordinal] = it } }) {}
                }
            }
        }
    }
}

/** Row 1: the samples, the color in their place, Contiguous (off till a used sample is picked by the pipette); row 2: amount, ✓; row 3: threshold, Cancel */
@Composable
private fun ReplacePanel(o: CorrectionOptions, picture: PipetteSource, onPopup: (OpenPopup?) -> Unit, modifier: Modifier, onCancel: () -> Unit, onConfirm: () -> Unit) =
    BottomPanel(true, null, modifier, buttons = {
        ColorPick(R.string.correction_sample, o.samples, null, picture, onPopup, used = o.used)
        // Takes the free width: no Spacer's extra gap, the row is tight
        ColorPick(R.string.correction_replace_with, listOf(o.to), ColorOpacity(o.toOpacity), picture, onPopup, Modifier.weight(1f))
        ToggleBtn(AppIcons.LinkVertical, o.contiguous, enabled = o.seeds.isNotEmpty(), rotation = { 90f }) { o.contiguous = !o.contiguous }
    }, more = {
        CorrectionSlider(REPLACE[0], REPLACE, o.amount.value, PERCENT) { o.amount.value = it }
        ConfirmBtn(onConfirm)
    }, fieldsGap = 16.dp) {
        CorrectionSlider(REPLACE[1], REPLACE, o.threshold.value, PERCENT) { o.threshold.value = it }
        CancelBtn(onCancel)
    }

/** [label] and swatches opening the color panel; [opacity] — their opacity slider. [used] — each swatch's (null — always used): a long press toggles it,
unused — translucent, crossed eye, a tap does nothing */
@Composable
private fun ColorPick(label: Int, colors: List<ColorOptions>, opacity: ColorOpacity?, picture: PipetteSource, onPopup: (OpenPopup?) -> Unit, modifier: Modifier = Modifier,
    used: List<BoolOption?> = emptyList()) =
    Row(modifier, Arrangement.spacedBy(GAP), Alignment.CenterVertically) {
        FieldText(stringResource(label))
        colors.forEachIndexed { i, c ->
            val u = used.getOrNull(i)
            val off = u?.value == false
            ColorBtn(c, opacity, picture, { if (!off) onPopup(it) }, Modifier.alpha(if (off) DISABLED_ALPHA else 1f), BTN, u?.let { b -> { Feedback.tap(); b.value = !b.value } },
                if (off) AppIcons.EyeOff else null)
        }
    }

/** Two sliders a page; the arrows turn the pages, each off on its end page */
@Composable
private fun TonesPanel(tones: MutableList<Int>, modifier: Modifier) {
    var page by remember { mutableIntStateOf(0) }
    BottomPanel(false, null, modifier, buttons = {
        TonesRow(page, 0, tones)
        ToolBtn(AppIcons.Back, enabled = page > 0, rotation = { 90f }) { page = 0 }
    }, more = {
        TonesRow(page, 1, tones)
        ToolBtn(AppIcons.Back, enabled = page == 0, rotation = { -90f }) { page = 1 }
    }) {}
}

/** The next page comes from below, the previous from above */
@Composable
private fun RowScope.TonesRow(page: Int, row: Int, tones: MutableList<Int>) =
    AnimatedContent(page, Modifier.weight(1f).clipToBounds(), {
        val next = targetState > initialState
        slideInVertically(PAGE_ANIM) { if (next) it else -it } togetherWith slideOutVertically(PAGE_ANIM) { if (next) -it else it }
    }, label = "tones") { p ->
        val k = 2 * p + row
        Row(verticalAlignment = Alignment.CenterVertically) { CorrectionSlider(TONES[k], TONES, tones[k], SIGNED) { tones[k] = it } }
    }

/** A channel a row */
@Composable
private fun BalancePanel(b: MutableList<Int>, modifier: Modifier) = BottomPanel(true, null, modifier, buttons = { Channel(b, 0) }, more = { Channel(b, 1) }) { Channel(b, 2) }

@Composable
private fun RowScope.Channel(b: MutableList<Int>, i: Int) = CorrectionSlider(CHANNELS[i], CHANNELS, b[i], SIGNED) { b[i] = it }

/** In %; [label] as wide as the widest of [labels]: a panel's sliders line up. [set] gets a value within [range] */
@Composable
private fun RowScope.CorrectionSlider(label: Int, labels: List<Int>, value: Int, range: IntRange, set: (Int) -> Unit) {
    val within = { v: Int -> set(v.coerceIn(range)) }
    PanelSlider({ Box { labels.forEach { FieldText(stringResource(it), if (it == label) Color.White else Color.Transparent) } } }, value, range, Modifier.weight(1f), "%",
        onChange = within, onApply = within)
}
