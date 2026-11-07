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

const val MAX_SIDE = 16000
const val MIN_SIDE = 4
const val MAX_PIXELS = 100_000_000 // слой целиком в RAM как ARGB_8888; выше SAFE_PIXELS — на возможности устройства
const val SAFE_PIXELS = 25_000_000 // предел отрисовки bitmap на любом устройстве: max(ro.hwui.max_texture_allocation_size, 100 МБ); от него — бюджеты Undo
const val MAX_OPACITY = 100 // %

/** [file] — in layers/ (the import as is or lossless PNG), decodes exactly to the pixels kept whole in RAM; null — none. It lies at [x], [y]; [w]×[h] — AFTER [orient]
(EXIF-like: 0..3 — clockwise ·90°, 4..7 — the same after a horizontal flip). [mask] — erase mask id (LayerMask); [patch] — brush strokes' tiles id: where it has a tile,
that tile is shown in place of pixels × mask; without [file] the patch is all there is. [id] — kept through edits: tools and bakes find the layer by it.
A new property goes into JSON too (ProjectStore.kt, optX with a default) */
data class Layer(val file: String? = null, val visible: Boolean = true, val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0, val opacity: Int = MAX_OPACITY, val orient: Int = 0, val mask: String? = null,
    val patch: String? = null, val id: Long = newLayerId()) {
    val inPlace = ContentTransform.initial(ContentBox(), x, y, w, h)
}

/** Random, never 0 */
fun newLayerId() = Random.nextLong(1, Long.MAX_VALUE)

val Layer.hasImage get() = file != null || patch != null

/** Where strokes reach it: an empty one covers the whole canvas */
fun Layer.paintable(cw: Int, ch: Int) = if (hasImage) this else copy(x = 0, y = 0, w = cw, h = ch, orient = 0)

val Layer.alpha255 get() = (opacity * 255f / MAX_OPACITY).roundToInt()

fun Float.toOpacity() = abs(this).roundToInt().coerceIn(0, MAX_OPACITY)

/** 90°/270° swap sides; symmetric */
fun orientW(orient: Int, w: Int, h: Int) = if ((orient and 1) == 0) w else h

fun orientH(orient: Int, w: Int, h: Int) = if ((orient and 1) == 0) h else w

/** File px → oriented layer px at (0, 0): coefficients 0/±1, pixels only rearranged */
fun orientMatrix(orient: Int, fw: Int, fh: Int) = Matrix().apply {
    if (orient >= 4) setScale(-1f, 1f)
    postRotate(90f * (orient and 3)) // Skia zeroes near-zero sin/cos: exact for multiples of 90°
    val r = RectF(0f, 0f, fw.toFloat(), fh.toFloat()).also { mapRect(it) }
    postTranslate(-r.left, -r.top)
}

val Layer.fileW get() = orientW(orient, w, h)
val Layer.fileH get() = orientH(orient, w, h)

/** [srcW]×[srcH] — the layer's bitmap or a smaller copy with the same proportions */
fun Layer.fileToCanvas(srcW: Int = fileW, srcH: Int = fileH, kx: Float = 1f, ky: Float = kx) = orientMatrix(orient, srcW, srcH).apply {
    postScale(w * kx / orientW(orient, srcW, srcH), h * ky / orientH(orient, srcW, srcH))
    postTranslate(x * kx, y * ky)
}

fun Layer.canvasToFile() = Matrix().also { fileToCanvas().invert(it) }

/** A new Region each call: Region is mutable */
fun Layer.region() = Region(x, y, x + w, y + h)

/** The single layer compositing rule (merge, thumbnail, export), the canvas scaled [kx]×[ky]; [bmp] may be a smaller copy */
fun Canvas.drawLayer(l: Layer, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, p: MaskPaints, filter: Boolean = false, kx: Float = 1f, ky: Float = kx) {
    save()
    concat(l.fileToCanvas(kx = kx, ky = ky))
    drawMasked(bmp, l.fileW, l.fileH, mask, patch, maxOf(kx, ky), l.alpha255, filter, p)
    restore()
}

fun Layer.toFile(r: Rect) = RectF(r).apply { canvasToFile().mapRect(this) }

fun Layer.toCanvas(r: Rect) = RectF(r).apply { fileToCanvas().mapRect(this) }

/** Selected canvas px, may reach past the canvas; immutable, equal by px and soft edge. [outline] — built once (off the main thread for a complex region).
[feather] — its soft edge (Feather.kt); [drawn] — the shape it was drawn as: growing keeps its corners (Selection.grown); null — made of px */
class Selection(val region: Region, val outline: Path = region.boundaryPath, val feather: Feather? = null, val drawn: Path? = null) {
    /** Where it selects anything */
    val reach get() = feather?.reach ?: region

    /** Where it selects fully */
    val core get() = feather?.core ?: region

    override fun equals(other: Any?) = other is Selection && other.region == region && other.feather?.alpha === feather?.alpha
    override fun hashCode() = region.bounds.hashCode()
}

/** Empty → null */
fun Region.toSelection(drawn: Path? = null) = takeUnless { it.isEmpty }?.let { Selection(it, drawn = drawn) }

fun Rect.asPath() = Path().apply { addRect(RectF(this@asPath), Path.Direction.CW) }

fun allSelected(w: Int, h: Int) = Rect(0, 0, w, h).let { Region(it).toSelection(it.asPath()) }

/** [selection] and [active] are session-only: not saved, not a change */
data class Project(val id: Long, val name: String, val width: Int, val height: Int, val updated: Long = id, val layers: List<Layer> = emptyList(), val selection: Selection? = null, val active: Int = layers.lastIndex)

/** Without the session-only state: compared for changes */
val Project.persisted get() = copy(selection = null, active = layers.lastIndex)

/** Where index [k] goes when the item at [from] moves to [to] */
fun movedIndex(k: Int, from: Int, to: Int) = when {
    k == from -> to
    from < to && k in from + 1..to -> k - 1
    to < from && k in to until from -> k + 1
    else -> k
}

/** Distinct: a duplicate shares them */
fun List<Layer>.files() = flatMap { listOfNotNull(it.file, it.mask, it.patch) }.distinct()
