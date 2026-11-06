package com.perkus.imagoedit.ui.editor

import android.graphics.fonts.FontStyle
import android.text.Layout
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppFont
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.DEFAULT_FONT
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.FloatOption
import com.perkus.imagoedit.data.Fonts
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.TEXT_REF
import com.perkus.imagoedit.data.TextLayout
import com.perkus.imagoedit.ui.common.AppIcons
import kotlin.math.roundToInt

private const val DEFAULT_SIZE = 100f // px холста
private const val FAVORITES_SEP = '/' // в именах файлов его не бывает

/** name is persisted */
enum class FontTab(val title: Int) { System(R.string.fonts_system), User(R.string.fonts_user), Favorites(R.string.fonts_favorites) }

/** name is persisted. Start/End follow each paragraph's direction */
enum class TextAlignment(val icon: ImageVector, val layout: Layout.Alignment) {
    Start(AppIcons.TextAlignJustifyStart, Layout.Alignment.ALIGN_NORMAL),
    Center(AppIcons.TextAlignJustifyCenter, Layout.Alignment.ALIGN_CENTER),
    End(AppIcons.TextAlignJustifyEnd, Layout.Alignment.ALIGN_OPPOSITE)
}

/** Persisted: TextState takes them on opening, save gives them back. [scroll] lives while the editor is open */
@Stable
class TextOptions(private val settings: AppSettings) {
    val color = ColorOptions(settings, "text.color")
    val tab = EnumOption(settings, "text.tab", FontTab.entries)
    val align = EnumOption(settings, "text.align", TextAlignment.entries)
    val size = FloatOption(settings, "text.size", 1f..MAX_SIZE, DEFAULT_SIZE)
    val angle = FloatOption(settings, "text.angle", -180f..180f, 0f)
    val opacity = IntOption(settings, "text.opacity", 0..MAX_OPACITY, MAX_OPACITY)
    val weight = IntOption(settings, "text.weight", FontStyle.FONT_WEIGHT_MIN..FontStyle.FONT_WEIGHT_MAX, FontStyle.FONT_WEIGHT_NORMAL)
    val spacing = IntOption(settings, "text.spacing", -50..200, 0) // между буквами, % кегля
    val frame = FrameOptions(settings, "text")
    val scroll = FontTab.entries.map { LazyListState() }
    var font = settings.string("text.font") ?: DEFAULT_FONT // AppFont.key
        set(v) { field = v; settings.setString("text.font", v) }
    var text = settings.string("text.text").orEmpty()
        set(v) { field = v; settings.setString("text.text", v) }
    var favorites by mutableStateOf(settings.string("text.favorites")?.split(FAVORITES_SEP)?.filter { it.isNotEmpty() }.orEmpty()) // AppFont.key
        private set

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        settings.setString("text.favorites", favorites.joinToString(FAVORITES_SEP.toString()))
    }
}

/** [weight] is kept as set, each font clamps it (AppFont.at): another font may take it whole. [spacing] — % of the font size */
data class TextContent(val text: String, val font: AppFont, val weight: Int, val align: TextAlignment, val spacing: Int) {
    val typeface get() = font.at(weight)

    /** A new one per call: a TextLayout is one thread's. Blank → [placeholder]'s, for the frame */
    fun layout(placeholder: String = "") = TextLayout(text.ifBlank { placeholder }, typeface, align.layout, spacing / 100f)
}

private fun TextOptions.last(fonts: Fonts) = TextContent(text, fonts.find(font), weight.value, align.value, spacing.value)

private fun firstFrame(cw: Int, ch: Int, l: TextLayout, o: TextOptions): ContentTransform {
    val k = o.size.value / TEXT_REF
    return ContentTransform(cw / 2f, ch / 2f, l.w * k, l.h * k, o.angle.value)
}

/** [content] placed by the frame as Move places an image: the canvas draws it live (drawLive), ✓ renders it into a new layer (AppViewModel.addText).
Blank shows nothing. The color isn't a step. [layer] — the index its layer will get; [blocked] — a touch then closes a popover, the frame stays. A step: the frame, opacity, content */
@Stable
class TextState(val layer: Int, id: Long, cw: Int, ch: Int, val options: TextOptions, val fonts: Fonts, private val placeholder: String, private val blocked: () -> Boolean) :
    PlaceState<Triple<ContentTransform, Int, TextContent>>(id, cw, ch, firstFrame(cw, ch, options.last(fonts).layout(placeholder), options), options.opacity.value) {
    var content by mutableStateOf(options.last(fonts))
        private set
    private val layout by derivedStateOf { content.layout(placeholder) }

    override val empty get() = content.text.isBlank()

    /** Font size, canvas px */
    val size get() = TEXT_REF * t.h / layout.h

    val color get() = options.color.value

    init { restore(options.frame) }

    override var step: Triple<ContentTransform, Int, TextContent>
        get() = Triple(t, opacity, content)
        set(v) {
            t = v.first
            opacity = v.second
            content = v.third
        }

    override fun hit(p: Offset, scale: Float, d: Density) = if (blocked()) IDLE else super.hit(p, scale, d)

    fun setText(text: String) = edit { refit(content.copy(text = text)) }

    fun setFont(font: AppFont) = edit { refit(content.copy(font = font)) }

    fun setAlign(a: TextAlignment) = edit { refit(content.copy(align = a)) }

    val weight get() = content.font.weights?.let { content.weight.coerceIn(it) } ?: content.weight

    fun setWeight(w: Int) = edit { refit(content.copy(weight = content.font.weights?.let { w.coerceIn(it) } ?: w)) }

    /** The slider's drag: one step on finish */
    fun slideWeight(w: Int) = hold { refit(content.copy(weight = w)) }

    fun setSpacing(v: Int) = edit { refit(content.copy(spacing = v.coerceIn(options.spacing.range))) }

    /** The slider's drag: one step on finish */
    fun slideSpacing(v: Int) = hold { refit(content.copy(spacing = v)) }

    /** The frame keeps its center, angle and stretch */
    private fun refit(c: TextContent) {
        val old = layout
        content = c
        t = t.copy(w = t.w * layout.w / old.w, h = t.h * layout.h / old.h)
    }

    fun save() {
        options.text = content.text
        options.font = content.font.key
        options.weight.value = content.weight
        options.align.value = content.align
        options.spacing.value = content.spacing
        options.size.value = size
        options.angle.value = t.angle
        options.opacity.value = opacity
        keep(options.frame)
    }

    /** As TextLayout.render bakes it. The opacity applies to the text as a whole (the layer's on ✓): overlapping glyphs don't darken */
    fun DrawScope.drawLive(s: Float, cw: Int, ch: Int) {
        if (empty) return
        val l = layout
        val t = t
        clipRect(0f, 0f, cw * s, ch * s) {
            // The chain of ContentTransform.matrix
            withTransform({
                translate(t.cx * s, t.cy * s)
                rotate(t.angle, Offset.Zero)
                scale(t.w * s / l.w * (if (t.flipH) -1f else 1f), t.h * s / l.h * (if (t.flipV) -1f else 1f), Offset.Zero)
                translate(-l.w / 2f, -l.h / 2f)
            }) {
                val c = drawContext.canvas.nativeCanvas
                val a = (alpha * 255).roundToInt()
                if (a < 255) c.saveLayerAlpha(0f, 0f, l.w.toFloat(), l.h.toFloat(), a)
                l.draw(c, color.toArgb())
                if (a < 255) c.restore()
            }
        }
    }
}
