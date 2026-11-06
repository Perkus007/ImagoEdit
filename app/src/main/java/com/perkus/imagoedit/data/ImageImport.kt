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

const val IO_BUFFER = 1 shl 16 // блок записи на общую память (FUSE): меньше системных вызовов

class BadImageSize(val width: Int, val height: Int) : RuntimeException()

/** An imported image over the canvas limits: [size] as it is, [fit] reduced to them. Imported only once the user agrees to reduce it */
class TooLarge(val size: Size, val fit: Size) : RuntimeException()

/** A new layer awaiting the Move tool's ✓, then inserted at [index]. [layer]'s file is its own (discarded on cancel).
[cut] — it was cut from the layer right below (see AppViewModel.moveSelection). Create off the main thread: [start] scans pixels */
class PendingImage(val layer: Layer, val bitmap: Bitmap, val index: Int, val cut: Cut? = null) {
    val start = ContentTransform.initial(contentBox(bitmap, bitmap.width, bitmap.height).oriented(layer.orient), layer.x, layer.y, layer.w, layer.h)
}

/** [rest] — the source layer without [area] (see cleared) with the new mask or patch it references (RAM only, dropped on cancel), or an empty layer; [area] — the part cut, canvas px */
class Cut(val rest: Pair<Layer, List<Tiles>>, val area: Region)

/** Software pixels (the editor reads and writes them), EXIF orientation applied. [mutable] — for editing in place. [header] sees the size first and may set a target one */
fun File.decodeImage(mutable: Boolean = false, header: (ImageDecoder, Size) -> Unit = { _, _ -> }): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(this)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        d.isMutableRequired = mutable
        header(d, info.size)
    }

/** [s] reduced in proportion to the canvas limits: MAX_SIDE per side, MAX_PIXELS in all; within them — as is */
private fun fitted(s: Size): Size {
    val k = minOf(1.0, MAX_SIDE.toDouble() / maxOf(s.width, s.height), sqrt(MAX_PIXELS.toDouble() / (s.width.toLong() * s.height)))
    return if (k == 1.0) s else Size((s.width * k).toInt(), (s.height * k).toInt())
}

/** An imported image as a layer at (0, 0) and its pixels. The file is the original copied byte-for-byte (format, EXIF and profile kept; the Uri is read once),
unless it's reduced or 16-bit (decoded to F16, 8 B per pixel): then its 8-bit pixels are written instead — a layer's file decodes exactly to its pixels.
Over the canvas limits → TooLarge; with [reduce] reduced to fit */
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
