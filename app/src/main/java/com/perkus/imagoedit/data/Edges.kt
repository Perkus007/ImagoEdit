package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

private const val EDGE_HOLE = 24f // наибольшая «дыра» внутри мазка (крапинка, пунктир), закрашиваемая целиком, px изображения

/** Layer [layer]'s px (as Layer.paintable) edge-aware dabs are cut by: [bmp], [mask], [patch] — as before the run of edge-aware strokes that made it so, if one
did (AppViewModel.edgePx): own paint isn't an edge */
class EdgePx(val layer: Layer, val bmp: Bitmap?, val mask: LayerMask?, val patch: Tiles?) {
    /** Drawn into a raster [k] px per px of the space [onto] maps canvas px into (Layer.renderer) */
    fun renderer(onto: Matrix, k: Float, ox: Int = 0, oy: Int = 0) = layer.renderer(bmp, mask, patch, k, onto, ox, oy)
}

/** «С учётом краёв»: a dab reaches only the px of its circle joined to its center through px like the one under it (kernels.c edges): fully up to the first of
[tolerance] (channel difference, 0..255), not at all from its last (past it, if they're equal). Those px — of the [w]×[h] raster ([s] px per file px) from raster px ([ox], [oy]) [render]
draws (from raster px less those), a tile at a time on first use, of [like]'s kind; past it its edge px repeat. One thread at a time */
class Edges(private val ox: Int, private val oy: Int, w: Int, h: Int, s: Float, val tolerance: IntRange, like: Bitmap?, render: (Bitmap, Int, Int) -> Unit) {
    private val px = Rendered(w, h, like, false, render)
    private val hole = (EDGE_HOLE * s * s).roundToInt()
    private var block = ByteArray(0)
    var mask = ByteArray(0) // the last cut dab's reach: [mw]×[mh] raster px from ([x0], [y0])
        private set
    var x0 = 0
        private set
    var y0 = 0
        private set
    var mw = 0
        private set
    var mh = 0
        private set

    /** [mask] for the dab at ([x], [y]), radius [r], raster px */
    fun cut(x: Float, y: Float, r: Float) {
        x0 = floor(x - r).toInt()
        y0 = floor(y - r).toInt()
        mw = floor(x + r).toInt() - x0 + 1
        mh = floor(y + r).toInt() - y0 + 1
        if (mask.size < mw * mh) {
            mask = ByteArray(mw * mh)
            block = ByteArray(4 * mw * mh)
        }
        px.copy(x0 - ox, y0 - oy, mw, mh, block, false)
        Kernels.edges(block, x0, y0, mw, mh, x, y, r, tolerance.first, tolerance.last, hole, mask)
    }

    fun recycle() = px.recycle()
}

/** Dabs [xy] of radius [r] by [dab]'s profile, each cut to its center's area (Edges, by [tolerance]) of the [w]×[h] image [render] draws at full size, of [like]'s
kind: their coverage as Dab.visits gives it, ALPHA_8 tiles (level 0 only). Each dab's area is found once, for all the tiles it reaches */
internal fun edgeCover(w: Int, h: Int, like: Bitmap?, render: (Bitmap, Int, Int) -> Unit, xy: FloatArray, r: Float, dab: Dab, tolerance: IntRange): Tiles {
    val e = Edges(0, 0, w, h, 1f, tolerance, like, render)
    val cols = tilesAlong(w, 0)
    val rows = tilesAlong(h, 0)
    val acc = arrayOfNulls<ByteArray>(cols * rows)
    val cur = arrayOfNulls<ByteArray>(acc.size)
    try {
        var px = Float.NaN
        var py = Float.NaN
        for (i in 0 until xy.size / 2) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            val c0 = floor((x - r) / TILE).toInt().coerceAtLeast(0)
            val c1 = floor((x + r) / TILE).toInt().coerceAtMost(cols - 1)
            val r0 = floor((y - r) / TILE).toInt().coerceAtLeast(0)
            val r1 = floor((y + r) / TILE).toInt().coerceAtMost(rows - 1)
            if (c0 <= c1 && r0 <= r1) {
                e.cut(x, y, r)
                for (ty in r0..r1) for (tx in c0..c1) {
                    val k = ty * cols + tx
                    dab.visit(x, y, px, py, r, tx * TILE, ty * TILE, TILE, TILE, TILE, acc[k] ?: ByteArray(TILE * TILE).also { acc[k] = it },
                        cur[k] ?: ByteArray(TILE * TILE).also { cur[k] = it }, null, e)
                }
            }
            px = x
            py = y
        }
    } finally { e.recycle() }
    return Tiles("", w, h, listOf(Array(acc.size) { k ->
        acc[k]?.let { a ->
            val c = cur[k]!!
            for (j in a.indices) {
                val u = a[j].toInt() and 0xFF
                val v = c[j].toInt() and 0xFF
                a[j] = (u + v - (u * v + 127) / 255).toByte() // SRC_OVER: the visit going on is done
            }
            blankMaskTile().apply { copyPixelsFromBuffer(ByteBuffer.wrap(a)) }
        }
    }))
}
