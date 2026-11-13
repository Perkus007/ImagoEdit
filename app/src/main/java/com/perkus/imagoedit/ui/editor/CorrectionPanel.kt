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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.Base
import com.perkus.imagoedit.data.BoolOption
import com.perkus.imagoedit.data.CarouselItem
import com.perkus.imagoedit.data.Correction
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.Figure
import com.perkus.imagoedit.data.Filter
import com.perkus.imagoedit.data.Guide
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.Joined
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerOverlay
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.MAX_PCT
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.Recolor
import com.perkus.imagoedit.data.PERCENT
import com.perkus.imagoedit.data.Relight
import com.perkus.imagoedit.data.SIGNED
import com.perkus.imagoedit.data.SAMPLES
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Sharpen
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.Tiles
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
import com.perkus.imagoedit.data.levelCount
import com.perkus.imagoedit.data.levelFor
import com.perkus.imagoedit.data.mark
import com.perkus.imagoedit.data.parallel
import com.perkus.imagoedit.data.patchTile
import com.perkus.imagoedit.data.replaceSelected
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
private val LABEL_H = CAROUSEL_H - GAP * 2 - IMAGE_H // подпись в одну строку: картинка видна целиком
private val LABEL_PAD = 4.dp
private val BTN = 32.dp // = ToolBtn
private val PAGE_ANIM = tween<IntOffset>(PANEL_ANIM_DURATION_MS)
private val REPLACE = listOf(R.string.intensity, R.string.tolerance)
private const val SHADE_COLOR = 0xFFFF9900.toInt()

/** Replace color's settings and Shade's [shade] color, persisted at once; [contiguous] — for the session: it needs the pipette's points. [samples] count only
where [used] (null — always: the first; the rest off by default) */
@Stable
class CorrectionOptions(settings: AppSettings) {
    val shade = ColorOptions(settings, "shade.color", SHADE_COLOR)
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
internal data class Spot(val rect: Rect, val n: Int)

/** A computed preview of the layer's px [at]: [px] — a raster at at's level from file px ([x0], [y0]) (the layer's: only tiles about at's rect); its smaller copies or none */
internal class Grid(val at: Spot, val px: Tiles, private val x0: Float = 0f, private val y0: Float = 0f) {
    /** In file px, from the copy for [k] px per file px, as the layer's; coarser than the layer's own px — always filtered */
    fun draw(c: Canvas, p: Paint, filter: Boolean, r: RectF, k: Float) {
        val j = (levelFor(k) - at.n).coerceIn(0, px.levels.lastIndex)
        p.isFilterBitmap = filter || at.n + j > 0
        c.drawTiles(px.levels[j], px.cols(j), (1 shl (at.n + j)).toFloat(), p, r, x0 = x0, y0 = y0)
    }

    fun recycle() = px.recycle()
}

/** A computed preview shown: [coarse] — of the whole area, with smaller copies; [fine] — of the part in view, as sharp as the layer is drawn */
private class Previews {
    var coarse by mutableStateOf<Grid?>(null)
    var fine by mutableStateOf<Grid?>(null)
}

/** These TILE² px (RGBA bytes) averaged 2ⁿ×2ⁿ, as the layer's smaller copies, into [out] (as large) from its px ([ox], [oy]) */
internal fun ByteArray.shrinkInto(n: Int, out: ByteArray, ox: Int, oy: Int) {
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
Sharpness on the layer's px, then Shadows/Highlights, the color-matrix ones in Correction's order, Shade last; [o] — Shade's color. [replaces] — the tool of
Replace color by o instead, alone on the layer as it is. Replace color, Sharpness, Shadows/Highlights preview off the main thread from the layer's px [src],
[srcMask], [srcPatch] */
@Stable
class CorrectionTool(l: Layer, private val sel: Selection?, private val src: Bitmap?, private val srcMask: LayerMask?, private val srcPatch: Tiles?,
    o: CorrectionOptions, replaces: Boolean = false) : LayerOverlay {
    override val layer = l.id
    private val replace = o.takeIf { replaces }
    val shade = o.shade
    private val file = l.file
    private val bakes = l.hasImage
    private val fw = l.fileW
    private val fh = l.fileH
    private val rect = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    private val toFile = l.canvasToFile()
    private val fromFile = l.fileToCanvas()

    /** The panel shown; null — the carousel */
    var open by mutableStateOf<Correction?>(null)

    /** Each correction's sliders' by Correction.ordinal */
    val values = Correction.entries.map { List(it.sliders.size) { 0 }.toMutableStateList() }
    private val tones = values[Correction.ShadowsHighlights.ordinal]

    private val area = sel.fileRect(l)
    private val all = Spot(area, maxOf(0, ceil(log2(area.width().toFloat() * area.height() / PREVIEW_PX) / 2).toInt())) // coarse's

    private val computed = Previews()
    private val view = MutableStateFlow<Spot?>(null) // what fine is to cover: set in the draw phase

    /** A worker's scratch in preview's work (see parallel) */
    private inner class Reader {
        private val c = Canvas()
        private val mp = MaskPaints()
        val px = patchTile(src)
        val raw = ByteArray(4 * TILE * TILE)
        val out = ByteArray(4 * TILE * TILE)
        val only by lazy(LazyThreadSafetyMode.NONE) { ByteArray(TILE * TILE) }

        /** The layer's px from file px ([x], [y]) into [into] at full size, as the bake renders them */
        fun render(into: Bitmap, x: Int, y: Int) = c.composite(into, x, y, src, fw, fh, srcMask, srcPatch, mp)

        /** The layer's px from file px ([x], [y]) at full size through [f] (null — as they are), as the bake reads them */
        fun read(x: Int, y: Int, f: Filter? = null) = px.also { if (f == null) render(it, x, y) else f.into(it, x, y, ::render) }
    }

    private val readers = arrayOfNulls<Reader>(WORKERS)

    private fun reader(wk: Int) = readers[wk] ?: Reader().also { readers[wk] = it }

    private val tone by derivedStateOf { toneOf(values, shade.value.toArgb()) }

    private fun relight() = tones.map { it / MAX_PCT.toFloat() }.let { (h, s, l, d) -> Relight(h, s, l, d) }

    /** null — none */
    private fun sharpen() = values[Correction.Sharpness.ordinal][0].takeIf { it > 0 }?.let { Sharpen(it / MAX_PCT.toFloat()) }

    override val shows get() = bakes && (computed.coarse != null || tone != null)

    /** Replace color's or the carousel's; null — nothing to change */
    fun stroke(): Stroke? {
        val r = replace?.recolor()?.takeUnless { it.idle }
        val h = relight().takeUnless { it.idle }
        val f = sharpen()
        val t = tone
        return if (bakes && (r != null || h != null || f != null || t != null))
            Stroke(layer, file, null, area = sel, figure = Figure(rect.asPath()), tone = t, recolor = r, relight = h, filter = f?.on)
        else null
    }

    /** While running, for the latest settings — of the whole area at ≤ PREVIEW_PX with smaller copies and, when the layer is drawn sharper, of the part in view:
    Replace color's, or Sharpness and Shadows/Highlights while they change anything (Shadows/Highlights' surroundings — while its panel is open or it changes
    anything); one ends before the next starts, the last one shown till then */
    suspend fun preview() {
        if (!bakes || area.isEmpty) return
        try {
            val o = replace
            if (o != null) {
                var made: Recolor? = null // the previews' settings
                var joinedFor: Recolor? = null // the settings Contiguous's px are for: only the samples, threshold and seeds count
                var joined: Joined? = null
                // Smaller px recolored would lose small replaced spots: recolored at full size, as the bake
                shown({ o.recolor() }, Recolor::idle) { r, v, c0, f0 ->
                    val key = r.copy(to = 0, opacity = 0f, amount = 0f)
                    if (key != joinedFor) { joined = r.joined(area, toFile) { wk, x, y -> reader(wk).read(x, y) }; joinedFor = key }
                    val j = joined
                    fun swapped(at: Spot, copies: Boolean) =
                        grid(at, sampled(at, copies) { wk, px, x, y -> r.swap(px, only = j?.let { m -> reader(wk).only.also { m.at(x, y, it) } }) }, copies)
                    val redo = r != made || c0 == null
                    ((if (redo) swapped(all, true) else c0) to (if (!redo && f0?.at == v) f0 else v?.let { swapped(it, false) })).also { made = r }
                }
            } else snapshotFlow { open == Correction.ShadowsHighlights || tones.any { it != 0 } }.collectLatest { lit ->
                var base: Base? = null
                var whole: Grid? = null // the area's px before relighting, as coarse's: as they are or sharpened by wholeFor
                var part: Grid? = null // the part in view's, as fine's
                try {
                    // At once, before a slider moves: the layer's px and their surroundings take the longest. The surroundings — of the full-size px, as the
                    // bake's; smooth in px, so relit at the preview's own px: a slider moved doesn't read the layer again
                    if (lit) base = withContext(Dispatchers.Default) {
                        val g = Guide(area)
                        whole = grid(all, sampled(all, true) { wk, px, x, y -> g.add(wk, px, x, y) }, false)
                        g.base()
                    }
                    var wholeFor: Sharpen? = null
                    var partFor: Sharpen? = null
                    var made: Pair<Sharpen?, Relight>? = null // the previews' settings
                    shown({ sharpen() to relight() }, { (s, r) -> s == null && r.idle }) { (s, r), v, c0, f0 ->
                        val redo = (s to r) != made || c0 == null
                        (if (r.idle) {
                            (if (redo) grid(all, sampled(all, true, s), true) else c0) to (if (!redo && f0?.at == v) f0 else v?.let { grid(it, sampled(it, false, s), false) })
                        } else {
                            if (wholeFor != s) { whole?.recycle(); whole = grid(all, sampled(all, true, s), false); wholeFor = s }
                            if (part?.at != v || partFor != s) { part?.recycle(); part = v?.let { grid(it, sampled(it, false, s), false) }; partFor = s }
                            (if (redo) whole?.let { relit(it, r, base, true) } else c0) to (if (!redo && f0?.at == v) f0 else part?.let { relit(it, r, base, false) })
                        }).also { made = s to r }
                    }
                } finally {
                    whole?.recycle()
                    part?.recycle()
                }
            }
        } finally {
            val shown = listOfNotNull(computed.coarse, computed.fine)
            computed.coarse = null
            computed.fine = null
            shown.forEach { it.recycle() }
            readers.forEach { it?.px?.recycle() }
            readers.fill(null)
        }
    }

    /** Shows the previews [make] gives, off the main thread, for the latest settings [of] (none while [idle]) and part in view, from the previews shown */
    private suspend fun <T> shown(of: () -> T, idle: (T) -> Boolean, make: (settings: T, view: Spot?, coarse: Grid?, fine: Grid?) -> Pair<Grid?, Grid?>) {
        combine(snapshotFlow(of), view, ::Pair).conflate().collect { (r, v) ->
            val c0 = computed.coarse
            val f0 = computed.fine
            val (c, f) = if (idle(r)) null to null else withContext(Dispatchers.Default) { make(r, v, c0, f0) }
            computed.coarse = c
            computed.fine = f
            if (c0 !== c) c0?.recycle()
            if (f0 !== f) f0?.recycle()
        }
    }

    /** The layer's px about [v] at full size through [f] (null — as they are: smaller px would lose its detail), each tile then through [full] (its px from file
    px x, y; [worker]'s), then averaged as the layer's smaller copies, into new tiles at v's level: the old may still be on screen. Past the layer its edge px
    repeat, as a clamped texture's. [around] — tiles so far around that smaller copies of them, drawn filtered, average only real px about v's rect. Off the main
    thread */
    private fun sampled(v: Spot, around: Boolean, f: Filter? = null, full: (worker: Int, px: Bitmap, x: Int, y: Int) -> Unit = { _, _, _, _ -> }): Array<Bitmap?> {
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
                full(wk, d.read(sx, sy, f), sx, sy)
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
    private val plain = tilePaint()
    private val over = tilePaint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val cell = RectF()

    /** In its layer's offscreen, as the bake: the selected part replaced by it corrected — the computed preview's px or the layer's. Draw phase */
    override fun draw(c: Canvas, l: Layer, filter: Boolean, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, k: Float) {
        val g = computed.coarse
        val fn = computed.fine
        if (g != null) aim(c, k, g, fn)
        val t = tone
        if (g == null && t == null) return
        c.replaceSelected(sel, toFile, fromFile, t) { u ->
            if (g == null) u.drawMasked(bmp, l.fileW, l.fileH, mask, patch, k, 255, filter, paints)
            else {
                u.save()
                u.clipRect(0, 0, fw, fh) // past it the tiles repeat the edge px
                g.draw(u, plain, filter, cell, k)
                fn?.draw(u, over, filter, cell, k)
                u.restore()
            }
        }
    }
}

/** Of [tiles] (corrections, effects): full-width, slides in from the bottom; [onPick] — a tile tapped. Keep it composed: images are decoded off the main thread at
the first opening and kept, so opening and scrolling don't stutter; the scroll position is kept too */
@Composable
fun <T : CarouselItem> TileCarousel(shown: Boolean, tiles: List<T>, modifier: Modifier, onPick: (T) -> Unit) {
    val res = LocalResources.current
    var images by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    val scroll = rememberLazyListState()
    if (shown && images == null) LaunchedEffect(Unit) { images = withContext(Dispatchers.IO) { tiles.map { ImageBitmap.imageResource(res, it.image) } } }
    AnimatedVisibility(shown, modifier, PANEL_IN, PANEL_OUT) {
        // Its scrolling takes the presses: they don't reach the press-outside layer under it
        LazyRow(Modifier.toolPanel().fillMaxWidth().height(CAROUSEL_H).background(BarBg), scroll, PaddingValues(GAP), horizontalArrangement = Arrangement.spacedBy(GAP),
            overscrollEffect = null) {
            itemsIndexed(tiles) { i, t -> CarouselTile(t.title, images?.get(i)) { onPick(t) } }
        }
    }
}

/** A two-line title takes the image's bottom: the image keeps its scale, cut below */
@Composable
private fun CarouselTile(title: Int, image: ImageBitmap?, onClick: () -> Unit) =
    Column(Modifier.width(TILE_W).fillMaxHeight().clip(SmallShape).border(1.dp, TranslucentGray, SmallShape).plainClickable(onClick)) {
        val area = Modifier.fillMaxWidth().weight(1f)
        if (image != null) Image(image, null, area, alignment = Alignment.TopCenter, contentScale = ContentScale.Crop) else Spacer(area)
        TileTitle(stringResource(title), Modifier.fillMaxWidth().heightIn(min = LABEL_H).padding(LABEL_PAD))
    }

/** Up to two lines, each centered, 1 dp apart, broken only at spaces: the first takes the most words that fit; a line too wide ends in an ellipsis */
@Composable
private fun TileTitle(text: String, modifier: Modifier) {
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 13.sp, lineHeight = 16.sp))
    val measurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) { (TILE_W - LABEL_PAD * 2).roundToPx() }
    val lines = remember(text, style, width) {
        val words = text.split(' ')
        var k = words.size
        while (k > 1 && measurer.measure(words.take(k).joinToString(" "), style).size.width > width) k--
        listOf(words.take(k), words.drop(k)).filter { it.isNotEmpty() }.map { it.joinToString(" ") }
    }
    Column(modifier, Arrangement.spacedBy(1.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        lines.forEach { Text(it, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style) }
    }
}

/** Runs the carousel's corrections' preview; the open one's panel, away while the color panel is open */
@Composable
fun CorrectionOverlay(s: CorrectionTool, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit) {
    LaunchedEffect(s) { s.preview() }
    s.open?.let { k ->
        val v = s.values[k.ordinal]
        SlidingPanel(open == ToolPopup.Color) { w ->
            when {
                k == Correction.Shade -> ShadePanel(v, s.shade, picture, onPopup, w)
                k.sliders.size > 3 -> PagedPanel(k, v, w)
                else -> SlidersPanel(k, v, w)
            }
        }
    }
}

/** Runs Replace color's preview — [s] made with [o]; its panel, away while the color panel is open */
@Composable
fun ReplaceOverlay(s: CorrectionTool, o: CorrectionOptions, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    LaunchedEffect(s) { s.preview() }
    SlidingPanel(open == ToolPopup.Color) { w -> ReplacePanel(o, picture, onPopup, w, onCancel, onConfirm) }
}

/** Slides in from the bottom, away while [away]; [content] gets its width: the widest row's, weighted items fill the rest */
@Composable
private fun SlidingPanel(away: Boolean, content: @Composable (Modifier) -> Unit) = Box(Modifier.fillMaxSize()) {
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(away) { shown.targetState = !away }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) { content(Modifier.width(IntrinsicSize.Max)) }
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
        AlignedSlider(REPLACE[0], REPLACE, o.amount.value, PERCENT) { o.amount.value = it }
        ConfirmBtn(onConfirm)
    }, fieldsGap = 16.dp) {
        AlignedSlider(REPLACE[1], REPLACE, o.threshold.value, PERCENT) { o.threshold.value = it }
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

/** [k]'s sliders [v], one a row: up to three */
@Composable
private fun SlidersPanel(k: Correction, v: MutableList<Int>, modifier: Modifier) {
    val more: (@Composable RowScope.() -> Unit)? = if (v.size > 1) { { CorrectionSlider(k, v, 1) } } else null
    BottomPanel(v.size > 2, null, modifier, buttons = { CorrectionSlider(k, v, 0) }, more = more) { if (v.size > 2) CorrectionSlider(k, v, 2) }
}

/** [k]'s sliders [v], two a page: four */
@Composable
private fun PagedPanel(k: Correction, v: MutableList<Int>, modifier: Modifier) {
    val page = remember { mutableIntStateOf(0) }
    BottomPanel(false, null, modifier, buttons = {
        PagedRow(page.intValue) { CorrectionSlider(k, v, 2 * it) }
        PageBtn(page, up = true)
    }, more = {
        PagedRow(page.intValue) { CorrectionSlider(k, v, 2 * it + 1) }
        PageBtn(page, up = false)
    }) {}
}

/** Row 1: glow, [color]; row 2: intensity */
@Composable
private fun ShadePanel(v: MutableList<Int>, color: ColorOptions, picture: PipetteSource, onPopup: (OpenPopup?) -> Unit, modifier: Modifier) =
    BottomPanel(false, null, modifier, buttons = {
        CorrectionSlider(Correction.Shade, v, 0)
        ColorBtn(color, null, picture, onPopup, Modifier, BTN)
    }, more = { CorrectionSlider(Correction.Shade, v, 1) }) {}

@Composable
private fun RowScope.CorrectionSlider(k: Correction, v: MutableList<Int>, i: Int) =
    AlignedSlider(k.sliders[i], k.sliders, v[i], k.range, resettable = k.range == SIGNED) { v[i] = it }

/** A row of a two-page panel: the next page comes from below, the previous from above */
@Composable
fun RowScope.PagedRow(page: Int, content: @Composable RowScope.(page: Int) -> Unit) =
    AnimatedContent(page, Modifier.weight(1f).clipToBounds(), {
        val next = targetState > initialState
        slideInVertically(PAGE_ANIM) { if (next) it else -it } togetherWith slideOutVertically(PAGE_ANIM) { if (next) -it else it }
    }, label = "page") { p -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { content(p) } }

/** Turns [page] to the first ([up]) or the second; off on it */
@Composable
fun PageBtn(page: MutableIntState, up: Boolean) =
    ToolBtn(AppIcons.Back, enabled = (page.intValue > 0) == up, rotation = { if (up) 90f else -90f }) { page.intValue = if (up) 0 else 1 }

/** [label] as wide as the widest of [labels]: a panel's sliders line up. [set] gets a value within [range]; [resettable] — as PanelSlider's */
@Composable
fun RowScope.AlignedSlider(label: Int, labels: List<Int>, value: Int, range: IntRange, suffix: String = "%", resettable: Boolean = true, set: (Int) -> Unit) {
    val within = { v: Int -> set(v.coerceIn(range)) }
    PanelSlider({ AlignedLabel(label, labels) }, value, range, Modifier.weight(1f), suffix, resettable, onChange = within, onApply = within)
}

/** [label] as wide as the widest of [labels], at its end: next to the field */
@Composable
fun AlignedLabel(label: Int, labels: List<Int>) = Box(contentAlignment = Alignment.CenterEnd) { labels.forEach { FieldText(stringResource(it), if (it == label) Color.White else Color.Transparent) } }
