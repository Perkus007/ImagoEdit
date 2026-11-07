package com.perkus.imagoedit.ui.editor

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.MIN_SIDE

private val dimPaint = Paint().apply { color = 0x99000000.toInt(); isAntiAlias = false } // чёрный, 60 %
private val edgePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = android.graphics.Color.WHITE; isAntiAlias = false } // 1 px экрана при любом масштабе

/** [area]'s part on the canvas, else the whole canvas; a side under MIN_SIDE widens around its center */
private fun firstFrame(cw: Int, ch: Int, area: Rect?): ContentTransform {
    val r = Rect(0, 0, cw, ch).apply { area?.let { intersect(it) } }
    val w = maxOf(r.width(), MIN_SIDE)
    val h = maxOf(r.height(), MIN_SIDE)
    val x = (r.centerX() - w / 2).coerceIn(0, cw - w)
    val y = (r.centerY() - h / 2).coerceIn(0, ch - h)
    return ContentTransform(x + w / 2f, y + h / 2f, w.toFloat(), h.toFloat())
}

/** The canvas cropped to the frame, at first [area]'s bounds (firstFrame); the frame stays on the canvas, sides ≥ MIN_SIDE. A step: the frame */
@Stable
class CropState(cw: Int, ch: Int, area: Rect?) :
    FrameState<ContentTransform>(cw, ch, firstFrame(cw, ch, area), free = true, min = MIN_SIDE.toFloat(), inside = true) {
    val changed get() = t.w < cw || t.h < ch

    /** Whole canvas px */
    val rect get() = Rect().also { t.bounds().round(it) }

    override var step: ContentTransform
        get() = t
        set(v) { t = v }

    override fun setField(f: FrameField, v: Float) = edit {
        val left = t.cx - t.w / 2
        val top = t.cy - t.h / 2
        t = when (f) {
            FrameField.X -> limited(t.copy(cx = v + t.w / 2))
            FrameField.Y -> limited(t.copy(cy = v + t.h / 2))
            // As if its handle were dragged to the value: top-left stays, a locked ratio holds
            FrameField.W -> resizedTo(t, if (free) 3 else 4, Offset(left + v, top + t.h * v / t.w), 0f)
            FrameField.H -> resizedTo(t, if (free) 5 else 4, Offset(left + t.w * v / t.h, top + v), 0f)
            else -> t
        }
    }

    /** Native canvas, crisp: outside dimmed, the frame's edge, handles */
    override fun DrawScope.drawOverlay(s: Float) {
        val b = t.bounds()
        val c = drawContext.canvas.nativeCanvas
        c.save()
        c.clipOutRect(b.left * s, b.top * s, b.right * s, b.bottom * s)
        c.drawRect(0f, 0f, cw * s, ch * s, dimPaint)
        c.restore()
        c.drawRect(b.left * s, b.top * s, b.right * s, b.bottom * s, edgePaint)
        drawHandles(t, s)
    }
}
