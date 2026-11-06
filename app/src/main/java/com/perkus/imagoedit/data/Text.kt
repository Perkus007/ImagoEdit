package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import kotlin.math.ceil

const val TEXT_REF = 1000f // кегль раскладки, px; крупный — getTextBounds (целые px) точен до 0,1 %

/** [text] at TEXT_REF px, a line per paragraph, [align]ed within the widest; [spacing] between letters, em. [w]×[h] — its ink's box, without the lines' room; no ink → the lines' box.
The frame stretches it: the live text and the baked layer match. The paint is mutable → one thread per instance */
class TextLayout(text: String, typeface: Typeface, align: Layout.Alignment, spacing: Float) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        textSize = TEXT_REF
        letterSpacing = spacing
    }
    private val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, ceil(Layout.getDesiredWidth(text, paint)).toInt().coerceAtLeast(1))
        .setAlignment(align).build()
    private val box = RectF().apply {
        val r = Rect()
        for (i in 0 until layout.lineCount) {
            paint.getTextBounds(text, layout.getLineStart(i), layout.getLineVisibleEnd(i), r)
            if (r.isEmpty) continue
            val x = layout.getLineLeft(i)
            val y = layout.getLineBaseline(i).toFloat()
            union(r.left + x, r.top + y, r.right + x, r.bottom + y)
        }
        if (isEmpty) set(0f, 0f, layout.width.toFloat(), layout.height.toFloat())
    }
    val w = ceil(box.width()).toInt()
    val h = ceil(box.height()).toInt()

    /** The box centered in [w]×[h] at (0, 0) */
    fun draw(c: Canvas, color: Int) {
        paint.color = color
        c.save()
        c.translate((w - box.width()) / 2 - box.left, (h - box.height()) / 2 - box.top)
        layout.draw(c)
        c.restore()
    }

    /** Into a new bitmap over ContentTransform.layerRect; null — off the canvas */
    fun render(t: ContentTransform, color: Int, cw: Int, ch: Int): Pair<Bitmap, Rect>? {
        val r = t.layerRect(cw, ch, w, h) ?: return null
        val bmp = createBitmap(r.width(), r.height())
        Canvas(bmp).run {
            translate(-r.left.toFloat(), -r.top.toFloat())
            concat(t.matrix(w, h, 0))
            draw(this, color)
        }
        return bmp to r
    }
}
