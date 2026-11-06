package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.floor

const val TILE = 256 // сторона тайла, px файла слоя: единица заливки в GPU, записи в файл и шага Undo
private const val HALF = TILE / 2
private const val TILE_BYTES = TILE * TILE * 4 // буфер под тайл любого формата: ARGB_8888 — 4 Б на пиксель, ALPHA_8 — 1

/** Tiles along a side of [len] file px at level [n] (2ⁿ× smaller) */
internal fun tilesAlong(len: Int, n: Int) = (((len + (1 shl n) - 1) shr n) + TILE - 1) / TILE

/** Down to the level that fits one tile */
private fun levelCount(w: Int, h: Int): Int {
    var n = 0
    while (tilesAlong(w, n) > 1 || tilesAlong(h, n) > 1) n++
    return n + 1
}

/** Px of a tile from [start] (level px) that lie on the layer: its side [len] file px, at level [n] */
private fun valid(len: Int, n: Int, start: Int) = minOf(TILE, ((len + (1 shl n) - 1) shr n) - start)

private val Bitmap.bpp get() = if (config == Bitmap.Config.ALPHA_8) 1 else 4

/** A tile's bytes ([bpp] per px) past the layer's edge ([vw]×[vh] on it) repeat the edge: filtered sampling at the edge then sees the edge, as the image's clamped texture does */
private fun ByteArray.clampPad(vw: Int, vh: Int, bpp: Int) {
    val row = TILE * bpp
    val pad = (TILE - vw) * bpp
    if (pad > 0) for (y in 0 until vh) {
        val at = y * row + vw * bpp
        System.arraycopy(this, at - bpp, this, at, bpp)
        var done = bpp // the copied run doubles: a few calls per row
        while (done < pad) {
            val n = minOf(done, pad - done)
            System.arraycopy(this, at, this, at + done, n)
            done += n
        }
    }
    for (y in vh until TILE) System.arraycopy(this, (vh - 1) * row, this, y * row, row)
}

/** clampPad on a drawn tile; [raw] — TILE_BYTES */
private fun Bitmap.clampPad(vw: Int, vh: Int, raw: ByteArray) {
    if (vw == TILE && vh == TILE) return
    copyPixelsToBuffer(ByteBuffer.wrap(raw))
    raw.clampPad(vw, vh, bpp)
    copyPixelsFromBuffer(ByteBuffer.wrap(raw))
}

/** Immutable raster over a layer's [w]×[h] file px of ALPHA_8 or ARGB_8888 tiles: [levels][0] — row by row, each next level 2× smaller; a missing tile — empty.
A tile never changes once in it: an edit makes new ones and shares the rest → a history step holds only the tiles its edit replaced (bytesBeyond).
[id] — also its file name: the file is written only when a saved state needs it */
class Tiles(val id: String, val w: Int, val h: Int, val levels: List<Array<Bitmap?>>) {
    fun cols(n: Int) = tilesAlong(w, n)

    /** Only for one nothing else holds (export) */
    fun recycle() = levels.forEach { l -> l.forEach { it?.recycle() } }
}

/** For drawTiles. Paint() anti-aliases since API 31: a pixel on a shared tile edge would be half covered by each tile — a visible grid */
fun tilePaint() = Paint().apply { isAntiAlias = false }

/** [unit] px per tile px; [paint] — from tilePaint; [blank] — for a missing tile's rect, null — skipped */
fun Canvas.drawTiles(tiles: Array<Bitmap?>, cols: Int, unit: Float, paint: Paint, r: RectF, blank: Paint? = null) {
    val side = TILE * unit
    for (k in tiles.indices) {
        val t = tiles[k]
        if (t == null && blank == null) continue
        val c = k % cols
        val row = k / cols
        // Edges computed alike for neighbors: no gap or overlap
        r.set(c * side, row * side, (c + 1) * side, (row + 1) * side)
        if (quickReject(r.left, r.top, r.right, r.bottom)) continue
        if (t != null) drawBitmap(t, null, r, paint) else if (blank != null) drawRect(r, blank)
    }
}

/** The 2×2 tiles of the level below from ([cx], [cy]) averaged byte by byte (premultiplied channels) into one, [vw]×[vh] of it on the layer; none of them → null (empty).
[sub], [out] — TILE_BYTES each */
private fun halve(below: Array<Bitmap?>, cols: Int, rows: Int, cx: Int, cy: Int, vw: Int, vh: Int, sub: ByteArray, out: ByteArray): Bitmap? {
    var like: Bitmap? = null
    out.fill(0)
    for (dy in 0..1) for (dx in 0..1) {
        if (cx + dx >= cols || cy + dy >= rows) continue
        val t = below[(cy + dy) * cols + cx + dx] ?: continue
        like = t
        val bpp = t.bpp
        val row = TILE * bpp
        t.copyPixelsToBuffer(ByteBuffer.wrap(sub))
        for (y in 0 until HALF) {
            val a = 2 * y * row
            val b = a + row
            val o = (dy * HALF + y) * row + dx * HALF * bpp
            for (x in 0 until HALF) for (c in 0 until bpp) {
                val i = 2 * x * bpp + c
                out[o + x * bpp + c] = (((sub[a + i].toInt() and 0xFF) + (sub[a + i + bpp].toInt() and 0xFF) + (sub[b + i].toInt() and 0xFF) + (sub[b + i + bpp].toInt() and 0xFF) + 2) shr 2).toByte()
            }
        }
    }
    val t = like ?: return null
    out.clampPad(vw, vh, t.bpp)
    return t.blankLike(TILE, TILE).apply { copyPixelsFromBuffer(ByteBuffer.wrap(out)) }
}

/** Coarser levels redone above the [changed] tiles of level 0 */
private fun redoLevels(levels: List<Array<Bitmap?>>, w: Int, h: Int, changed: BooleanArray) {
    val sub = ByteArray(TILE_BYTES)
    val out = ByteArray(TILE_BYTES)
    var below = changed
    for (n in 1 until levels.size) {
        val cols = tilesAlong(w, n)
        val subCols = tilesAlong(w, n - 1)
        val subRows = tilesAlong(h, n - 1)
        val up = BooleanArray(levels[n].size)
        for (k in below.indices) if (below[k]) up[k / subCols / 2 * cols + k % subCols / 2] = true
        for (k in up.indices) if (up[k]) levels[n][k] = halve(levels[n - 1], subCols, subRows, k % cols * 2, k / cols * 2, valid(w, n, k % cols * TILE), valid(h, n, k / cols * TILE), sub, out)
        below = up
    }
}

/** [make] remakes each [touched] level-0 tile from its old one (null — none) at file px ([x], [y]): null — empty there; an immutable one is shared as is (uniform: nothing to pad).
The rest are shared, the levels above redone. Nothing left → null */
internal fun Tiles?.edited(id: String, w: Int, h: Int, touched: BooleanArray, make: (old: Bitmap?, x: Int, y: Int) -> Bitmap?): Tiles? {
    val levels = this?.levels?.map { it.copyOf() } ?: List(levelCount(w, h)) { n -> arrayOfNulls<Bitmap>(tilesAlong(w, n) * tilesAlong(h, n)) }
    val cols = tilesAlong(w, 0)
    val raw = ByteArray(TILE_BYTES)
    for (k in touched.indices) {
        if (!touched[k]) continue
        val x = k % cols * TILE
        val y = k / cols * TILE
        levels[0][k] = make(levels[0][k], x, y)?.also { if (it.isMutable) it.clampPad(valid(w, 0, x), valid(h, 0, y), raw) }
    }
    if (levels[0].all { it == null }) return null
    redoLevels(levels, w, h, touched)
    return Tiles(id, w, h, levels)
}

/** Marks the tiles ([cols]×[rows]) the rect (file px, edges inclusive) reaches */
internal fun BooleanArray.mark(cols: Int, rows: Int, left: Float, top: Float, right: Float, bottom: Float) {
    val c0 = floor(left / TILE).toInt().coerceAtLeast(0)
    val c1 = floor(right / TILE).toInt().coerceAtMost(cols - 1)
    val r0 = floor(top / TILE).toInt().coerceAtLeast(0)
    val r1 = floor(bottom / TILE).toInt().coerceAtMost(rows - 1)
    for (y in r0..r1) for (x in c0..c1) this[y * cols + x] = true
}

/** Marks the tiles [rects] (Region.rects) reach */
internal fun BooleanArray.mark(cols: Int, rows: Int, rects: IntArray) {
    for (k in rects.indices step 4) mark(cols, rows, rects[k].toFloat(), rects[k + 1].toFloat(), rects[k + 2] - 1f, rects[k + 3] - 1f)
}

/** Its rects mapped by [m] (90° turns, flips and whole px shifts only): left, top, right, bottom in turn */
internal fun Region.rects(m: Matrix): IntArray {
    val out = ArrayList<Int>()
    val r = Rect()
    val f = RectF()
    val iter = RegionIterator(this)
    while (iter.next(r)) {
        f.set(r)
        m.mapRect(f)
        f.round(r)
        out += r.left; out += r.top; out += r.right; out += r.bottom
    }
    return out.toIntArray()
}

/** A level-0 tile at file px ([x], [y]) of a [w]×[h] layer: [old] copied, or [blank] when none, with [draw] in file px, clipped to the layer */
internal inline fun tileDrawn(old: Bitmap?, blank: () -> Bitmap, x: Int, y: Int, w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
    val t = old?.let { it.copy(it.config!!, true) ?: throw OutOfMemoryError() } ?: blank() // config is null only for formats tiles never have
    Canvas(t).run {
        translate(-x.toFloat(), -y.toFloat())
        clipRect(0, 0, w, h)
        draw(this)
    }
    return t
}

/** A raster from its level-0 [tiles] (read or rendered); none → null */
private fun built(id: String, w: Int, h: Int, tiles: Array<Bitmap?>): Tiles? {
    val all = BooleanArray(tiles.size) { tiles[it] != null }
    if (all.none { it }) return null
    val levels = List(levelCount(w, h)) { n -> if (n == 0) tiles else arrayOfNulls<Bitmap>(tilesAlong(w, n) * tilesAlong(h, n)) }
    redoLevels(levels, w, h, all)
    return Tiles(id, w, h, levels)
}

private val Tiles.everyTile get() = levels.asSequence().flatMap { it.asSequence() }.filterNotNull()

/** Bytes of these rasters' tiles, every level, that none of [others] has: what they alone keep in RAM (a history snapshot over the state after it).
An immutable tile is one instance shared by all (cutOut): free */
fun List<Tiles>.bytesBeyond(others: List<Tiles>): Long {
    val kept = others.asSequence().flatMap { it.everyTile }.toHashSet()
    return asSequence().flatMap { it.everyTile }.filter { it.isMutable && it !in kept }.toHashSet().sumOf { it.byteCount.toLong() }
}

/** A raster of a [w]×[h] layer from this bitmap lying at its file px ([x0], [y0]), the tiles of its kind: only where some pixel isn't transparent (for ALPHA_8 — erases something) */
internal fun Bitmap.tiledAt(id: String, w: Int, h: Int, x0: Int, y0: Int): Tiles? {
    val cols = tilesAlong(w, 0)
    val tiles = arrayOfNulls<Bitmap>(cols * tilesAlong(h, 0))
    val px = IntArray(TILE * TILE)
    val raw = ByteArray(TILE_BYTES)
    val paint = tilePaint()
    for (k in tiles.indices) {
        val x = k % cols * TILE
        val y = k / cols * TILE
        val left = maxOf(x, x0)
        val top = maxOf(y, y0)
        val right = minOf(x + TILE, x0 + width, w)
        val bottom = minOf(y + TILE, y0 + height, h)
        if (left >= right || top >= bottom) continue
        getPixels(px, 0, right - left, left - x0, top - y0, right - left, bottom - top)
        var any = false
        for (i in 0 until (right - left) * (bottom - top)) if ((px[i] ushr 24) != 0) { any = true; break }
        if (!any) continue
        // Whole-px offset: an exact copy
        tiles[k] = blankLike(TILE, TILE).also { t ->
            Canvas(t).drawBitmap(this, (x0 - x).toFloat(), (y0 - y).toFloat(), paint)
            t.clampPad(valid(w, 0, x), valid(h, 0, y), raw)
        }
    }
    return built(id, w, h, tiles)
}

internal fun Bitmap.tiled(id: String) = tiledAt(id, width, height, 0, 0)

/** The file px its level-0 tiles cover (within the layer); none → empty */
fun Tiles.bounds(): Rect {
    val cols = cols(0)
    val r = Rect()
    levels[0].forEachIndexed { k, t -> if (t != null) r.union(k % cols * TILE, k / cols * TILE, minOf(k % cols * TILE + TILE, w), minOf(k / cols * TILE + TILE, h)) }
    return r
}

/** Its pixels copied over [px] where it has tiles: a line of [len] file px from [x], [y] along X or Y */
fun Tiles.over(px: IntArray, x: Int, y: Int, alongX: Boolean, len: Int) {
    val cols = cols(0)
    var i = 0
    while (i < len) {
        val fx = if (alongX) x + i else x
        val fy = if (alongX) y else y + i
        val run = minOf(len - i, TILE - (if (alongX) fx else fy) % TILE)
        levels[0][fy / TILE * cols + fx / TILE]?.let { t ->
            if (alongX) t.getPixels(px, i, run, fx % TILE, fy % TILE, run, 1) else t.getPixels(px, i, 1, fx % TILE, fy % TILE, 1, run)
        }
        i += run
    }
}

/** A tile's compressed bytes: tiles never change, so writing a new raster compresses only its new tiles. Only a mask's (ALPHA_8, small):
a patch's are about as big as its pixels — RAM over time, they're compressed again on each write */
private val packed = Collections.synchronizedMap(WeakHashMap<Bitmap, ByteArray>())

private val Bitmap.packable get() = config == Bitmap.Config.ALPHA_8

private fun Bitmap.pack(): ByteArray {
    val raw = ByteBuffer.allocate(byteCount).also { copyPixelsToBuffer(it) }.array()
    val d = Deflater(Deflater.BEST_SPEED)
    return try {
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1 shl 14)
        while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
        out.toByteArray()
    } finally { d.end() }
}

/** Level 0's tiles, compressed: the coarser levels are rebuilt on reading */
fun Tiles.write(f: File) = f.deleteOnFailure {
    DataOutputStream(f.outputStream().buffered(IO_BUFFER)).use { out ->
        val tiles = levels[0]
        out.writeInt(tiles.count { it != null })
        tiles.forEachIndexed { k, t ->
            if (t == null) return@forEachIndexed
            val b = if (t.packable) packed.getOrPut(t) { t.pack() } else t.pack()
            out.writeInt(k)
            out.writeInt(b.size)
            out.write(b)
        }
    }
}

/** The tiles file of a [w]×[h] layer as [id]; [blank] — an empty tile of its kind */
fun File.readTiles(id: String, w: Int, h: Int, blank: () -> Bitmap): Tiles? {
    val tiles = arrayOfNulls<Bitmap>(tilesAlong(w, 0) * tilesAlong(h, 0))
    val raw = ByteArray(TILE_BYTES)
    val inf = Inflater()
    try {
        DataInputStream(inputStream().buffered(IO_BUFFER)).use { inp ->
            repeat(inp.readInt()) {
                val k = inp.readInt()
                val b = ByteArray(inp.readInt()).also { inp.readFully(it) }
                if (k !in tiles.indices) throw IOException("Bad tiles $name")
                val t = blank()
                val size = t.byteCount
                inf.reset()
                inf.setInput(b)
                var n = 0
                while (n < size && !inf.finished()) {
                    val got = inf.inflate(raw, n, size - n)
                    if (got == 0 && inf.needsInput()) throw IOException("Bad tiles $name")
                    n += got
                }
                if (n < size) throw IOException("Bad tiles $name")
                tiles[k] = t.apply { copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, size)) }.also { if (it.packable) packed[it] = b }
            }
        }
    } finally { inf.end() }
    return built(id, w, h, tiles)
}
