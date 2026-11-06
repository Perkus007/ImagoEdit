package com.perkus.imagoedit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import com.radzivon.bartoshyk.avif.coder.AvSpeed
import com.radzivon.bartoshyk.avif.coder.AvifChromaSubsampling
import com.radzivon.bartoshyk.avif.coder.AvifEncodingOptions
import com.radzivon.bartoshyk.avif.coder.Coder
import com.radzivon.bartoshyk.avif.coder.PreciseMode
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

const val EXPORT_MIN_QUALITY = 10 // нижняя граница ползунка качества, %
private const val EXPORT_DIR = "ImagoEdit" // подпапка в Pictures
val EXPORT_PATH = "${Environment.DIRECTORY_PICTURES}/$EXPORT_DIR" // путь для подписи в UI
private val BAD_NAME_CHARS = Regex("[\\\\/:*?\"<>|]") // недопустимы в имени файла

/** Labels are proper names, not localized */
enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"), PNG("PNG", "png", "image/png"), WEBP("WebP", "webp", "image/webp"), AVIF("AVIF", "avif", "image/avif")
}

/** AVIF is always lossy: avif-coder lossless mode writes a YCgCo matrix that Samsung's system decoder can't open.
[fullChroma] — 4:4:4 for JPEG and AVIF; below 100% 4:2:0: a smaller file */
data class ExportSettings(val format: ExportFormat = ExportFormat.PNG, val quality: Int = 100) {
    val lossless get() = format == ExportFormat.PNG || (format == ExportFormat.WEBP && quality == 100)
    val fullChroma get() = quality == 100
}

class ExportError(cause: Throwable) : RuntimeException(cause)

@Suppress("DEPRECATION") // with MANAGE_EXTERNAL_STORAGE this is the standard way to get the shared Pictures folder
private fun exportDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), EXPORT_DIR)

/** [first] — bottom visible layer: its color space goes into the file */
private fun Project.canvasBitmap(fmt: ExportFormat, first: Bitmap?) =
    createBitmap(width, height, Bitmap.Config.ARGB_8888, fmt != ExportFormat.JPEG, first?.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)).apply { if (fmt == ExportFormat.JPEG) eraseColor(Color.WHITE) }

private fun Bitmap.isFullyOpaque(): Boolean {
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (v in row) if ((v ushr 24) != 0xFF) return false
    }
    return true
}

private fun ProjectStore.composite(p: Project, fmt: ExportFormat): Bitmap {
    val paints = MaskPaints() // integer offsets: layer pixels are copied 1:1, filtering and anti-aliasing change nothing
    var out: Bitmap? = null
    try {
        p.layers.filter { it.visible && it.hasImage }.forEach { l ->
            // One layer at a time from its files (the project isn't open): peak memory = canvas + one layer with its mask and patch + a band of it
            val bmp = l.file?.let { decodeLayer(p, l) }
            var mask: LayerMask? = null
            var patch: Tiles? = null
            try {
                mask = readMask(p, l)
                patch = readPatch(p, l, bmp)
                Canvas(out ?: p.canvasBitmap(fmt, bmp).also { out = it }).drawLayer(l, bmp, mask, patch, paints)
            } finally { bmp?.recycle(); mask?.recycle(); patch?.recycle() }
        }
        return (out ?: p.canvasBitmap(fmt, null)).apply {
            // No transparent pixels → no alpha in the file (PNG is written as RGB: smaller and faster). Checked by pixels: hasAlpha is always true for baked layers
            if (fmt != ExportFormat.JPEG && isFullyOpaque()) setHasAlpha(false)
        }
    } catch (e: Throwable) {
        out?.recycle()
        throw e
    }
}

/** libjpeg-turbo and libwebp built from source (app/src/main/cpp): the platform encoders take only quality — JPEG is always 4:2:0, WebP runs at medium effort.
Both write [path] directly with [icc] embedded; webp recycles [bitmap] once its pixels are copied, so the encoder gets that RAM */
@Keep
private object Encoders {
    init { System.loadLibrary("imagoedit") }
    external fun jpeg(bitmap: Bitmap, quality: Int, fullChroma: Boolean, icc: ByteArray, path: String)
    external fun webp(bitmap: Bitmap, quality: Int, lossless: Boolean, icc: ByteArray, path: String)
}

/** Matrix/TRC ICC v4 profile of the color space, built like Skia's for the platform encoders. The canvas space is RGB with a parametric curve: createBitmap requires it */
private fun Bitmap.icc(): ByteArray {
    val cs = colorSpace as ColorSpace.Rgb
    fun ByteBuffer.fixed(vararg v: Double) = apply { v.forEach { putInt((it * 65536).roundToInt()) } } // s15Fixed16
    fun tag(type: String, size: Int, body: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(size).put(type.toByteArray()).putInt(0).apply(body).array()
    fun text(s: String) = tag("mluc", 28 + 2 * s.length) { putInt(1).putInt(12).put("enUS".toByteArray()).putInt(2 * s.length).putInt(28).put(s.toByteArray(Charsets.UTF_16BE)) }
    fun xyz(vararg v: Double) = tag("XYZ ", 20) { fixed(*v) }
    val d50 = doubleArrayOf(0.9642, 1.0, 0.8249)
    val m = (ColorSpace.adapt(cs, ColorSpace.ILLUMINANT_D50) as ColorSpace.Rgb).transform.map { it.toDouble() } // RGB → XYZ D50, by columns
    val t = cs.transferParameters!!
    val trc = tag("para", 40) { putShort(4).putShort(0).fixed(t.g, t.a, t.b, t.c, t.d, t.e, t.f) }
    val tags = listOf("desc" to text(cs.name), "cprt" to text("CC0"), "wtpt" to xyz(*d50),
        "rXYZ" to xyz(m[0], m[1], m[2]), "gXYZ" to xyz(m[3], m[4], m[5]), "bXYZ" to xyz(m[6], m[7], m[8]), "rTRC" to trc, "gTRC" to trc, "bTRC" to trc)
    val data = tags.map { it.second }.distinct() // the three curves share one copy
    val offsets = data.runningFold(128 + 4 + 12 * tags.size) { o, d -> o + ((d.size + 3) and 3.inv()) } // tags 4-byte aligned
    return ByteBuffer.allocate(offsets.last()).apply {
        putInt(offsets.last()).putInt(0).putInt(0x04300000).put("mntrRGB XYZ ".toByteArray()).putShort(2026).putShort(1).putShort(1)
        position(36); put("acsp".toByteArray())
        position(68); fixed(*d50)
        position(128); putInt(tags.size)
        tags.forEach { (sig, d) -> put(sig.toByteArray()).putInt(offsets[data.indexOf(d)]).putInt(d.size) }
        data.forEachIndexed { i, d -> position(offsets[i]); put(d) }
    }.array()
}

private fun Bitmap.encode(s: ExportSettings, dst: File) = when (s.format) {
    // AUTO in avif-coder is always 4:2:0 → full-res chroma at 100%
    ExportFormat.AVIF -> dst.writeBytes(Coder().encodeAvif(this, options = AvifEncodingOptions(quality = s.quality, preciseMode = PreciseMode.LOSSY, speed = AvSpeed.SLOW,
        chromaSubsampling = if (s.fullChroma) AvifChromaSubsampling.YUV444 else AvifChromaSubsampling.AUTO)))
    ExportFormat.JPEG -> Encoders.jpeg(this, s.quality, s.fullChroma, icc(), dst.path)
    ExportFormat.WEBP -> Encoders.webp(this, s.quality, s.lossless, icc(), dst.path)
    ExportFormat.PNG -> writePng(dst)
}

/** Off the main thread */
fun ProjectStore.exportImage(ctx: Context, p: Project, s: ExportSettings): File = try {
    val dir = exportDir().apply { if (!mkdirs() && !isDirectory) throw IOException("Can't create $path") }
    val bmp = composite(p, s.format)
    val base = p.name.replace(BAD_NAME_CHARS, "_").trim().trimStart('.').ifEmpty { "export" }
    val dst = generateSequence(0) { it + 1 }
        .map { File(dir, if (it == 0) "$base.${s.format.ext}" else "$base ($it).${s.format.ext}") }
        .first { !it.exists() }
    val tmp = File(dir, ".${dst.name}.tmp") // temp file + rename: an interrupted export leaves no broken image in the gallery
    tmp.deleteOnFailure {
        try { bmp.encode(s, tmp) } finally { bmp.recycle() }
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    MediaScannerConnection.scanFile(ctx, arrayOf(dst.path), arrayOf(s.format.mime), null)
    dst
} catch (e: Throwable) { throw ExportError(e) }