package com.perkus.imagoedit.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.Region
import android.net.Uri
import android.util.Size
import java.io.File
import kotlin.math.sqrt

const val IO_BUFFER = 1 shl 16 // блок записи на общую память (FUSE)

class BadImageSize(val width: Int, val height: Int) : RuntimeException()

/** An import over the canvas limits: [size] as is, [fit] reduced; imported only once the user agrees */
class TooLarge(val size: Size, val fit: Size) : RuntimeException()

/** A new layer awaiting the Move tool's ✓, then inserted at [index]; its file is its own. [cut] — cut from the layer below (moveSelection). Create off the main thread */
class PendingImage(val layer: Layer, val bitmap: Bitmap, val index: Int, val cut: Cut? = null) {
    val start = ContentTransform.initial(contentBox(bitmap, bitmap.width, bitmap.height).oriented(layer.orient), layer.x, layer.y, layer.w, layer.h)
}

/** [rest] — the source without [area] and its new rasters (RAM only), or an empty layer; [area] — the cut part, canvas px */
class Cut(val rest: Pair<Layer, List<Tiles>>, val area: Region)

/** Software pixels, EXIF orientation applied; [header] may set a target size */
fun File.decodeImage(mutable: Boolean = false, header: (ImageDecoder, Size) -> Unit = { _, _ -> }): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(this)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.isMutableRequired = mutable
        header(d, info.size)
    }

/** Reduced in proportion to MAX_SIDE and MAX_PIXELS; within them — as is */
private fun fitted(s: Size): Size {
    val k = minOf(1.0, MAX_SIDE.toDouble() / maxOf(s.width, s.height), sqrt(MAX_PIXELS.toDouble() / (s.width.toLong() * s.height)))
    return if (k == 1.0) s else Size((s.width * k).toInt(), (s.height * k).toInt())
}

/** A layer at (0, 0) and its pixels: the original copied byte-for-byte, unless reduced or 16-bit — then its 8-bit pixels (a layer's file decodes exactly to them).
Over the limits → TooLarge; with [reduce] reduced */
fun ProjectStore.importImage(resolver: ContentResolver, uri: Uri, reduce: Boolean): Pair<Layer, Bitmap> {
    val src = newStaged(resolver.getType(uri))
    return src.deleteOnFailure {
        resolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it, IO_BUFFER) } }
        var reduced = false
        val img = src.decodeImage { d, s ->
            val fit = fitted(s)
            if (minOf(fit.width, fit.height) < MIN_SIDE) throw BadImageSize(s.width, s.height)
            if (fit != s) {
                if (!reduce) throw TooLarge(s, fit)
                d.setTargetSize(fit.width, fit.height)
                reduced = true
            }
        }
        val bmp = if (img.config != Bitmap.Config.RGBA_F16) img else (img.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError()).also { img.recycle() }
        if (bmp === img && !reduced) Layer(src.name, w = bmp.width, h = bmp.height) to bmp
        else writeLayer(bmp, Rect(0, 0, bmp.width, bmp.height)).also { src.delete() }
    }
}

inline fun <T> File.deleteOnFailure(block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    delete()
    throw e
}
