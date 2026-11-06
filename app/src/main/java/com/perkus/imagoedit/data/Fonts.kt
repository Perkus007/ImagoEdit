package com.perkus.imagoedit.data

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import androidx.core.content.res.use
import com.perkus.imagoedit.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

const val DEFAULT_FONT = "default"
private val FONT_TYPES = setOf("ttf", "otf", "ttc")
private val SYSTEM_WEIGHTS = 100..900
private const val TTCF = 0x74746366 // 'ttcf'
private const val NAME = 0x6E616D65 // 'name'
private const val FVAR = 0x66766172 // 'fvar'
private const val WGHT = 0x77676874 // 'wght'

/** [key] is persisted (the choice, favorites): DEFAULT_FONT, "res:" + resource name, "file:" + file name.
[weights] — a variable font's wght axis, null — one weight; [weight] — [typeface]'s own; [weighted] — it at another */
class AppFont(val key: String, val name: String, val typeface: Typeface, val weights: IntRange? = null, private val weight: Int = FontStyle.FONT_WEIGHT_NORMAL,
    private val weighted: (Int) -> Typeface = { typeface }) {
    private var last: Pair<Int, Typeface>? = null // main thread only

    fun at(w: Int): Typeface {
        val r = weights ?: return typeface
        val k = w.coerceIn(r)
        if (k == weight) return typeface
        last?.takeIf { it.first == k }?.let { return it.second }
        return weighted(k).also { last = k to it }
    }
}

/** [system] — the default first */
class Fonts(val system: List<AppFont>, val user: List<AppFont>) {
    fun byKey(key: String) = system.firstOrNull { it.key == key } ?: user.firstOrNull { it.key == key }

    fun find(key: String) = byKey(key) ?: system.first()
}

/** Made by AppViewModel.refreshAccess */
val fontsDir: File get() = File(appDir, ".fonts")

/** Bundled: res/font files listed in R.array.app_fonts. Not a font → skipped. Off the main thread */
fun loadFonts(context: Context): Fonts {
    val res = context.resources
    val bundled = res.obtainTypedArray(R.array.app_fonts).use { a -> List(a.length()) { a.getResourceId(it, 0) } }.mapNotNull { id ->
        val entry = runCatching { res.getResourceEntryName(id) }.getOrNull() ?: return@mapNotNull null
        appFont("res:$entry", entry) { Font.Builder(res, id) }
    }
    val user = fontsDir.listFiles { f -> f.isFile && f.extension.lowercase() in FONT_TYPES }.orEmpty()
        .mapNotNull { f -> appFont("file:${f.name}", f.nameWithoutExtension) { Font.Builder(f) } }.sortedBy { it.name.lowercase() }
    // Variable Roboto from Android 12; before it the nearest static weight
    val default = AppFont(DEFAULT_FONT, context.getString(R.string.font_default), Typeface.DEFAULT, SYSTEM_WEIGHTS) { Typeface.create(Typeface.DEFAULT, it, false) }
    return Fonts(listOf(default) + bundled, user)
}

/** null — not a font */
private fun appFont(key: String, fallback: String, source: () -> Font.Builder) = runCatching {
    val font = source().build()
    val b = font.buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
    val axis = b.weightAxis()
    AppFont(key, b.fontName() ?: fallback, typeface(font), axis?.first, axis?.second ?: FontStyle.FONT_WEIGHT_NORMAL) { w ->
        typeface(Font.Builder(font.buffer).setFontVariationSettings("'wght' $w").setWeight(w.coerceIn(FontStyle.FONT_WEIGHT_MIN, FontStyle.FONT_WEIGHT_MAX)).build())
    }
}.getOrNull()

private fun typeface(font: Font) = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).setStyle(font.style).build()

private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xFFFF

/** A collection's first font; throws if there's none */
private fun ByteBuffer.table(tag: Int): Int {
    val font = if (getInt(0) == TTCF) getInt(12) else 0
    return getInt((0 until u16(font + 4)).map { font + 12 + it * 16 }.first { getInt(it) == tag } + 8)
}

/** Name ID 4, else 1; Windows US English first */
private fun ByteBuffer.fontName(): String? {
    fun score(r: Int): Int {
        val platform = u16(r)
        val lang = when {
            platform == 3 && u16(r + 4) == 0x409 -> 3
            platform == 3 || platform == 0 -> 2
            platform == 1 && u16(r + 2) == 0 -> 1
            else -> return -1
        }
        return when (u16(r + 6)) { 4 -> 4 + lang; 1 -> lang; else -> -1 }
    }
    return runCatching {
        val t = table(NAME)
        val r = (0 until u16(t + 2)).map { t + 6 + it * 12 }.filter { score(it) >= 0 }.maxByOrNull(::score)
        r?.let {
            val start = t + u16(t + 4) + u16(it + 10)
            String(ByteArray(u16(it + 8)) { i -> get(start + i) }, if (u16(it) == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE).trim().ifEmpty { null }
        }
    }.getOrNull()
}

/** The weights and the default; null — no varying wght axis */
private fun ByteBuffer.weightAxis(): Pair<IntRange, Int>? = runCatching {
    val t = table(FVAR)
    fun fixed(at: Int) = (getInt(at) / 65536f).roundToInt() // 16.16
    (0 until u16(t + 8)).map { t + u16(t + 4) + it * u16(t + 10) }.firstOrNull { getInt(it) == WGHT }?.let { fixed(it + 4)..fixed(it + 12) to fixed(it + 8) }
}.getOrNull()?.takeIf { it.first.first < it.first.last }
