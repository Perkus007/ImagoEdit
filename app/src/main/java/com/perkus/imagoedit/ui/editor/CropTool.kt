package com.perkus.imagoedit.ui.editor

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.MIN_SIDE

private val dimPaint = Paint().apply { color = 0x99000000.toInt(); isAntiAlias = false } // холст за рамкой: чёрный, 60 %
private val edgePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = android.graphics.Color.WHITE; isAntiAlias = false } // контур рамки: 1 px экрана при любом масштабе

/** The first frame: [area]'s part on the canvas [cw]×[ch] (canvas px), the whole canvas without it or when it's all off the canvas. A side under MIN_SIDE widens around its center, staying on the canvas */
private fun firstFrame(cw: Int, ch: Int, area: Rect?): ContentTransform {
    val r = Rect(0, 0, cw, ch).apply { area?.let { intersect(it) } }
    val w = maxOf(r.width(), MIN_SIDE)
    val h = maxOf(r.height(), MIN_SIDE)
    val x = (r.centerX() - w / 2).coerceIn(0, cw - w)
    val y = (r.centerY() - h / 2).coerceIn(0, ch - h)
    return ContentTransform(x + w / 2f, y + h / 2f, w.toFloat(), h.toFloat())
}

/** The canvas [cw]×[ch] cropped to the frame. At first it's [area]'s bounds — the selection's, whatever its shape and parts — see firstFrame.
The frame stays on the canvas, its sides at least MIN_SIDE. A step: the frame */
@Stable
class CropState(cw: Int, ch: Int, area: Rect?) :
    FrameState<ContentTransform>(cw, ch, firstFrame(cw, ch, area), free = true, min = MIN_SIDE.toFloat(), inside = true) {
    val changed get() = t.w < cw || t.h < ch

    /** The frame, whole canvas px */
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
            // As if its handle were dragged to the value: the top-left stays, a locked ratio holds
            FrameField.W -> resizedTo(t, if (free) 3 else 4, Offset(left + v, top + t.h * v / t.w), 0f)
            FrameField.H -> resizedTo(t, if (free) 5 else 4, Offset(left + t.w * v / t.h, top + v), 0f)
            else -> t
        }
    }

    /** On the native canvas, crisp: the canvas outside the frame dimmed (the screen around the canvas isn't), the frame's edge, the handles */
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
