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

/** Reduced in proportion to MAX_SIDE and MAX_PIXELS; within them — as is. Too small then → BadImageSize */
fun fitted(s: Size): Size {
    val k = minOf(1.0, MAX_SIDE.toDouble() / maxOf(s.width, s.height), sqrt(MAX_PIXELS.toDouble() / (s.width.toLong() * s.height)))
    // + 1e-6: the limiting side gets MAX_SIDE exactly despite rounding in k
    val fit = if (k == 1.0) s else Size((s.width * k + 1e-6).toInt(), (s.height * k + 1e-6).toInt())
    if (minOf(fit.width, fit.height) < MIN_SIDE) throw BadImageSize(s.width, s.height)
    return fit
}

private class Header(val size: Size) : RuntimeException()

/** Only the header is read (decoding stops there): the size, EXIF orientation applied */
fun ContentResolver.imageSize(uri: Uri): Size = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(this, uri)) { _, info, _ -> throw Header(info.size) }
    throw IllegalStateException()
} catch (h: Header) {
    h.size
}

/** A layer at (0, 0) and its pixels: the original copied byte-for-byte, unless reduced or 16-bit — then its 8-bit pixels (a layer's file decodes exactly to them).
[size] null: as is, over the limits → TooLarge; else decoded at [size] (the image's proportions, within the limits) */
fun ProjectStore.importImage(resolver: ContentResolver, uri: Uri, size: Size?): Pair<Layer, Bitmap> {
    val src = newStaged(resolver.getType(uri))
    return src.deleteOnFailure {
        resolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it, IO_BUFFER) } }
        var reduced = false
        val img = src.decodeImage { d, s ->
            val fit = size ?: fitted(s).also { if (it != s) throw TooLarge(s, it) }
            if (fit != s) {
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
