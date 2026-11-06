package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

const val MAX_SIDE = 16000 // максимальная сторона холста
const val MIN_SIDE = 4 // минимальная сторона холста, px
const val MAX_PIXELS = 100_000_000 // максимум пикселей холста, а с ним и любого слоя: слой целиком в памяти как ARGB_8888 (4 Б на пиксель). Выше SAFE_PIXELS — сколько потянет устройство, мощные не ограничиваем
const val SAFE_PIXELS = 25_000_000 // столько тянет любое устройство: платформа бросает RuntimeException при отрисовке bitmap тяжелее max(ro.hwui.max_texture_allocation_size, 100 МБ). По нему — запасы памяти Undo, чтобы не росли с MAX_PIXELS
const val MAX_OPACITY = 100 // непрозрачность слоя, %: 0 — слой не виден, MAX_OPACITY — полностью непрозрачен

/** [file] — file name in layers/ (imported original as is, or lossless PNG: an import reduced or 16-bit, baking, merging), null — no pixels (not written to JSON).
It decodes exactly to the layer's pixels, 8 bits per channel, which the editor keeps whole in memory.
The file stores only the image: on the canvas it's a rectangle with top-left [x], [y]; the rest of the canvas is transparent and not stored.
[w]×[h] — size on the canvas AFTER orientation (a file rotated by 90°/270° has swapped sides, see fileW/fileH).
[orient] — how the file lies on the canvas (EXIF-like): 0..3 — clockwise rotation by orient·90°, 4..7 — same after a horizontal flip; pixels unchanged.
[mask] — id of its erase mask (LayerMask, also the mask's file name in layers/), null — none: an erase never touches the pixels, compositing multiplies them by the mask.
[patch] — id of its patch (Tiles of the pixels' kind, also the file name in layers/), null — none: brush strokes; where it has a tile, that tile is shown in place of
the pixels × mask (see Stroke.bake). Without [file] the patch is all there is, the layer covers the whole canvas (a cropped one may reach past it); without both the layer is empty.
[id] — the layer's own through its edits: the tools and a stroke baked off the main thread find it by it; a new layer, a duplicate and a paste get a new one.
Order in Project.layers = stacking order, 0 — bottom. A new layer property goes here and into JSON (ProjectStore.kt) via optX with a default */
data class Layer(val file: String? = null, val visible: Boolean = true, val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0, val opacity: Int = MAX_OPACITY, val orient: Int = 0, val mask: String? = null,
    val patch: String? = null, val id: Long = newLayerId()) {
    // Computed once → frames draw without allocations
    val inPlace = ContentTransform.initial(ContentBox(), x, y, w, h)
}

/** Random, never 0: ids of layers saved by earlier sessions don't collide with new ones */
fun newLayerId() = Random.nextLong(1, Long.MAX_VALUE)

val Layer.hasImage get() = file != null || patch != null

/** Where strokes reach it: its own rect; an empty one — the whole canvas [cw]×[ch], as its first stroke makes it */
fun Layer.paintable(cw: Int, ch: Int) = if (hasImage) this else copy(x = 0, y = 0, w = cw, h = ch, orient = 0)

val Layer.alpha255 get() = (opacity * 255f / MAX_OPACITY).roundToInt()

fun Float.toOpacity() = abs(this).roundToInt().coerceIn(0, MAX_OPACITY)

/** 90° and 270° swap sides. Symmetric: the same call maps layer size back to file size */
fun orientW(orient: Int, w: Int, h: Int) = if ((orient and 1) == 0) w else h

fun orientH(orient: Int, w: Int, h: Int) = if ((orient and 1) == 0) h else w

/** "File pixels → oriented layer pixels" matrix, layer at (0, 0). Coefficients 0/±1, integer offsets → pixels are only rearranged */
fun orientMatrix(orient: Int, fw: Int, fh: Int) = Matrix().apply {
    if (orient >= 4) setScale(-1f, 1f)
    postRotate(90f * (orient and 3)) // Skia zeroes near-zero sin/cos → exact coefficients for multiples of 90°
    val r = RectF(0f, 0f, fw.toFloat(), fh.toFloat()).also { mapRect(it) }
    postTranslate(-r.left, -r.top)
}

val Layer.fileW get() = orientW(orient, w, h)
val Layer.fileH get() = orientH(orient, w, h)

/** [srcW]×[srcH] — bitmap size: the layer's own or a smaller copy with the same proportions (thumbnail). For the layer's own at scale 1 pixels are only rearranged */
fun Layer.fileToCanvas(srcW: Int = fileW, srcH: Int = fileH, kx: Float = 1f, ky: Float = kx) = orientMatrix(orient, srcW, srcH).apply {
    postScale(w * kx / orientW(orient, srcW, srcH), h * ky / orientH(orient, srcW, srcH))
    postTranslate(x * kx, y * ky)
}

fun Layer.canvasToFile() = Matrix().also { fileToCanvas().invert(it) }

/** Its rect on the canvas; a new Region each call: Region is mutable */
fun Layer.region() = Region(x, y, x + w, y + h)

/** Single layer compositing rule (merge, thumbnail, export): its pixels [bmp] (or a smaller copy in the same proportions; null — none) × [mask], [patch] in their place,
at its place, the canvas scaled [kx]×[ky] */
fun Canvas.drawLayer(l: Layer, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, p: MaskPaints, filter: Boolean = false, kx: Float = 1f, ky: Float = kx) {
    save()
    concat(l.fileToCanvas(kx = kx, ky = ky))
    drawMasked(bmp, l.fileW, l.fileH, mask, patch, maxOf(kx, ky), l.alpha255, filter, p)
    restore()
}

/** Canvas rect [r] in the file's px */
fun Layer.toFile(r: Rect) = RectF(r).apply { canvasToFile().mapRect(this) }

/** File px rect [r] on the canvas */
fun Layer.toCanvas(r: Rect) = RectF(r).apply { fileToCanvas().mapRect(this) }

/** Selected canvas pixels: a region on the pixel grid (canvas px), may reach past the canvas. Immutable: an edit makes a new one. Equal by pixels and soft edge.
[outline] is drawn on every march step → built once; for a complex region that takes a while → create off the main thread where the region is built.
[feather] — its soft edge (see Feather.kt) around the outline: the region needn't be where it selects half (Selection transform's blur keeps the outline unless it smooths it).
[drawn] — the shape it was drawn as (canvas px), its px those whose centers it holds: growing moves that along its normals, corners kept (Selection.grown);
null — made of px (lasso, wand, brush, layer pixels, smoothing): growing rounds the corners */
class Selection(val region: Region, val outline: Path = region.boundaryPath, val feather: Feather? = null, val drawn: Path? = null) {
    /** Where it selects anything */
    val reach get() = feather?.reach ?: region

    /** Where it selects fully */
    val core get() = feather?.core ?: region

    override fun equals(other: Any?) = other is Selection && other.region == region && other.feather?.alpha === feather?.alpha
    override fun hashCode() = region.bounds.hashCode()
}

/** An empty region = no selection; [drawn] — as Selection's */
fun Region.toSelection(drawn: Path? = null) = takeUnless { it.isEmpty }?.let { Selection(it, drawn = drawn) }

fun Rect.asPath() = Path().apply { addRect(RectF(this@asPath), Path.Direction.CW) }

/** The whole [w]×[h] canvas, drawn as a rect */
fun allSelected(w: Int, h: Int) = Rect(0, 0, w, h).let { Region(it).toSelection(it.asPath()) }

/** [selection] and [active] (index of the layer tools act on) live only in the session (history, Undo): not written to JSON and not a reason to save */
data class Project(val id: Long, val name: String, val width: Int, val height: Int, val updated: Long = id, val layers: List<Layer> = emptyList(), val selection: Selection? = null, val active: Int = layers.lastIndex)

/** Without the session-only state: as saved and loaded, compared for changes */
val Project.persisted get() = copy(selection = null, active = layers.lastIndex)

/** Where index [k] goes when the item at [from] moves to [to] */
fun movedIndex(k: Int, from: Int, to: Int) = when {
    k == from -> to
    from < to && k in from + 1..to -> k - 1
    to < from && k in to until from -> k + 1
    else -> k
}

/** Image files, masks and patches. Distinct: a duplicate layer shares them with its original */
fun List<Layer>.files() = flatMap { listOfNotNull(it.file, it.mask, it.patch) }.distinct()
