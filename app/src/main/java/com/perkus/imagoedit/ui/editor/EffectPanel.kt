package com.perkus.imagoedit.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.ANGLES
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.Cast
import com.perkus.imagoedit.data.Effect
import com.perkus.imagoedit.data.Figure
import com.perkus.imagoedit.data.Filter
import com.perkus.imagoedit.data.FilterOn
import com.perkus.imagoedit.data.IntOption
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.LayerOverlay
import com.perkus.imagoedit.data.MAX_OFFSET
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.MAX_PCT
import com.perkus.imagoedit.data.MaskPaints
import com.perkus.imagoedit.data.PERCENT
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.TILE
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.WORKERS
import com.perkus.imagoedit.data.angleVector
import com.perkus.imagoedit.data.asPath
import com.perkus.imagoedit.data.blurReach
import com.perkus.imagoedit.data.blurSigma
import com.perkus.imagoedit.data.built
import com.perkus.imagoedit.data.canvasToFile
import com.perkus.imagoedit.data.cast
import com.perkus.imagoedit.data.castFilter
import com.perkus.imagoedit.data.castShape
import com.perkus.imagoedit.data.composite
import com.perkus.imagoedit.data.drawTiles
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.gaussian
import com.perkus.imagoedit.data.glowGain
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.mark
import com.perkus.imagoedit.data.parallel
import com.perkus.imagoedit.data.patchTile
import com.perkus.imagoedit.data.replaceSelected
import com.perkus.imagoedit.data.streak
import com.perkus.imagoedit.data.streakLength
import com.perkus.imagoedit.data.tilePaint
import com.perkus.imagoedit.ui.common.Bounds
import com.perkus.imagoedit.ui.common.trackBounds
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.ScreenBg
import com.perkus.imagoedit.ui.theme.TranslucentGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val PREVIEW_PX = 1 shl 22 // px превью всей области: ≈ экран
private const val TICK_STEP = 15 // °, риски круга
private const val KNOB_IN = 0.55f // кружок ручки, доля её радиуса
private const val GLOW_COLOR = 0xFFFFE066.toInt()
private val BTN = 32.dp // = ToolBtn
private val DIAL_R = 48.dp // круг с рисками
private val NEAR = 32.dp // от круга: дальше — шаг 10°
private val FAR = 96.dp // от круга: дальше — шаг 45°
private val DIAL_BG = Color(0xE61C1C1C)
private val MOTION_LABELS = listOf(R.string.angle, R.string.tool_blur)
private val SHADOW_LABELS = listOf(R.string.angle, R.string.tool_blur, R.string.offset, R.string.opacity)
private val GLOW_LABELS = listOf(R.string.tool_blur, R.string.intensity)

/** Persisted at once */
@Stable
class EffectOptions(settings: AppSettings) {
    val blur = IntOption(settings, "effect.blur", PERCENT, 20)
    val motion = IntOption(settings, "effect.motion", PERCENT, 30)
    val motionAngle = IntOption(settings, "effect.motion.angle", ANGLES, 0)
    val shadowAngle = IntOption(settings, "effect.shadow.angle", ANGLES, 45)
    val shadowOffset = IntOption(settings, "effect.shadow.offset", 0..MAX_OFFSET, 10)
    val shadowBlur = IntOption(settings, "effect.shadow.blur", PERCENT, 20)
    val shadowOpacity = IntOption(settings, "effect.shadow.opacity", 0..MAX_OPACITY, 60)
    val shadowColor = ColorOptions(settings, "effect.shadow.color", android.graphics.Color.BLACK)
    val glowBlur = IntOption(settings, "effect.glow.blur", PERCENT, 25)
    val glowIntensity = IntOption(settings, "effect.glow.intensity", PERCENT, 50)
    val glowColor = ColorOptions(settings, "effect.glow.color", GLOW_COLOR)
}

/** [kind] (a filter: not Replace color) on layer [l] by [o]: blurs — within [sel] (null — all of it); Shadow, Glow — of the shape sel selects, cast (Canvas.cast). Previewed in the layer
(LayerOverlay) from its px [src], [srcMask], [srcPatch] off the main thread, at ≤ PREVIEW_PX over region: the layer with the most its spread reaches on the [cw]×[ch]
canvas, as the bake's layer grows (Stroke.room) — edges repeated alike. Shadow, Glow — only the blurred shape, moved, colored and cast as drawn. ✓ bakes it as a brush
stroke, the layer grows to hold it */
@Stable
class EffectTool(val kind: Effect, l: Layer, val o: EffectOptions, private val sel: Selection?, private val src: Bitmap?, private val srcMask: LayerMask?,
    private val srcPatch: Tiles?, cw: Int, ch: Int) : LayerOverlay {
    override val layer = l.id
    private val file = l.file
    private val bakes = l.hasImage
    private val fw = l.fileW
    private val fh = l.fileH
    private val own = Rect(l.x, l.y, l.x + l.w, l.y + l.h)
    private val side = sqrt(cw.toFloat() * ch)
    private val toFile = l.canvasToFile()
    private val fromFile = l.fileToCanvas()
    private val casts = kind == Effect.Shadow || kind == Effect.Glow

    // Canvas px the effect spreads past the shape at most
    private val margin = when (kind) {
        Effect.MotionBlur -> ceil(streakLength(MAX_PCT, side) / 2).toInt() + 1
        Effect.Shadow -> blurReach(blurSigma(MAX_PCT, side)) + MAX_OFFSET
        else -> blurReach(blurSigma(MAX_PCT, side))
    }

    /** File px of the preview */
    private val region = Rect(own).run {
        inset(-margin, -margin)
        if (!intersect(0, 0, cw, ch)) setEmpty()
        union(own)
        if (!intersect(-margin, -margin, cw + margin, ch + margin)) setEmpty()
        Rect().also { RectF(this).apply { toFile.mapRect(this) }.round(it) }
    }
    private val n = if (region.isEmpty) 0 else maxOf(0, ceil(log2(region.width().toFloat() * region.height() / PREVIEW_PX) / 2).toInt())
    private val rw = (region.width() + (1 shl n) - 1) shr n
    private val rh = (region.height() + (1 shl n) - 1) shr n
    private val cols = (rw + TILE - 1) / TILE
    private val rows = (rh + TILE - 1) / TILE

    /** Canvas px → the preview's */
    private val toRaster = Matrix(toFile).apply {
        postTranslate(-region.left.toFloat(), -region.top.toFloat())
        postScale(1f / (1 shl n), 1f / (1 shl n))
    }

    /** The preview's tiles that change: blurs — about the selection */
    private val changing = BooleanArray(cols * rows).apply {
        val b = sel?.takeUnless { casts }?.let { RectF(it.reach.bounds).apply { toRaster.mapRect(this) } }
        if (b == null) fill(true) else mark(cols, rows, b.left, b.top, b.right, b.bottom)
    }.let { t -> t.indices.filter { t[it] } }

    private var shown by mutableStateOf<Grid?>(null)

    override val shows get() = bakes && shown != null

    /** A worker's scratch (see parallel) */
    private inner class Reader {
        val c = Canvas()
        val mp = MaskPaints()
        val px = patchTile(src)
        val raw = ByteArray(4 * TILE * TILE)
        val out = ByteArray(4 * TILE * TILE)
        val r = RectF()

        /** The preview's raster [base] from ([x], [y]) into [b] */
        fun draw(base: Array<Bitmap?>, b: Bitmap, x: Int, y: Int) {
            b.eraseColor(android.graphics.Color.TRANSPARENT)
            c.setBitmap(b)
            c.translate(-x.toFloat(), -y.toFloat())
            c.drawTiles(base, cols, 1f, copy, r)
        }
    }

    private val readers = arrayOfNulls<Reader>(WORKERS)

    private fun reader(wk: Int) = readers[wk] ?: Reader().also { readers[wk] = it }

    private val copy = tilePaint() // workers' only
    private val plain = tilePaint()
    private val tinted = tilePaint()
    private var tint: Pair<Int, Float>? = null // tinted's color and gain
    private val cell = RectF()
    private val clip = RectF(region)

    private fun color() = (if (kind == Effect.Shadow) o.shadowColor else o.glowColor).value.toArgb()

    private fun gain() = if (kind == Effect.Shadow) o.shadowOpacity.value / MAX_OPACITY.toFloat() else glowGain(o.glowIntensity.value)

    /** Shadow's offset, canvas px */
    private fun shift() = if (kind != Effect.Shadow) floatArrayOf(0f, 0f) else angleVector(o.shadowAngle.value).also { it[0] *= o.shadowOffset.value; it[1] *= o.shadowOffset.value }

    /** What the computed preview depends on */
    private fun setting() = when (kind) {
        Effect.GaussianBlur -> o.blur.value to 0
        Effect.MotionBlur -> o.motion.value to o.motionAngle.value
        Effect.Shadow -> o.shadowBlur.value to 0
        else -> o.glowBlur.value to 0
    }

    /** null — nothing to show */
    private fun previewed(v: Pair<Int, Int>): FilterOn? = when (kind) {
        Effect.GaussianBlur -> blurSigma(v.first, side).takeIf { it > 0f }?.let { gaussian(it) }
        Effect.MotionBlur -> streakLength(v.first, side).takeIf { it > 0f }?.let { streak(v.second, it) }
        else -> castShape(blurSigma(v.first, side), sel)
    }

    /** ✓; null — nothing to change */
    fun stroke(): Stroke? {
        if (!bakes) return null
        val f: FilterOn
        val reach: Rect
        when (kind) {
            Effect.GaussianBlur -> {
                val s = blurSigma(o.blur.value, side).takeIf { it > 0f } ?: return null
                f = gaussian(s)
                reach = Rect(own).apply { blurReach(s).let { inset(-it, -it) } }
            }
            Effect.MotionBlur -> {
                val len = streakLength(o.motion.value, side).takeIf { it > 0f } ?: return null
                f = streak(o.motionAngle.value, len)
                reach = Rect(own).apply { (ceil(len / 2).toInt() + 1).let { inset(-it, -it) } }
            }
            else -> {
                val g = gain().takeIf { it > 0f } ?: return null
                val shape = Rect(own).takeIf { sel == null || it.intersect(sel.reach.bounds) } ?: return null
                val s = blurSigma((if (kind == Effect.Shadow) o.shadowBlur else o.glowBlur).value, side)
                val d = shift()
                f = Cast(s, d[0], d[1], color(), g, sel)::filter
                reach = shape.apply { offset(d[0].roundToInt(), d[1].roundToInt()); blurReach(s).let { inset(-it, -it) } }
            }
        }
        return Stroke(layer, file, null, area = sel.takeUnless { casts }, figure = Figure(reach.asPath()), filter = f)
    }

    /** While running: the computed preview for the latest settings, one at a time; the layer's px read once first */
    suspend fun preview() {
        if (!bakes || region.isEmpty) return
        var base: Array<Bitmap?>? = null
        try {
            val b = withContext(Dispatchers.Default) { sampled().also { base = it } }
            snapshotFlow(::setting).conflate().collect { v ->
                val g = previewed(v)?.let { f -> withContext(Dispatchers.Default) { made(f(rw, rh, toRaster), b) } }
                val old = shown
                shown = g
                old?.recycle()
            }
        } finally {
            val g = shown
            shown = null
            g?.recycle()
            base?.forEach { it?.recycle() }
            readers.forEach { it?.px?.recycle() }
            readers.fill(null)
        }
    }

    /** The layer's px over region at the preview's level: each tile's TILE² blocks at full size, averaged; transparent past the layer. Off the main thread */
    private fun sampled(): Array<Bitmap?> {
        val m = 1 shl n
        val tiles = arrayOfNulls<Bitmap>(cols * rows)
        parallel(tiles.size) { wk, k ->
            val d = reader(wk)
            val tx = k % cols * TILE
            val ty = k / cols * TILE
            d.out.fill(0)
            for (j in 0 until m * m) {
                val x = region.left + (tx shl n) + j % m * TILE
                val y = region.top + (ty shl n) + j / m * TILE
                if (x >= region.right || y >= region.bottom || x >= fw || y >= fh || x + TILE <= 0 || y + TILE <= 0) continue
                d.c.composite(d.px, x, y, src, fw, fh, srcMask, srcPatch, d.mp)
                d.px.copyPixelsToBuffer(ByteBuffer.wrap(d.raw))
                d.raw.shrinkInto(n, d.out, j % m * (TILE shr n), j / m * (TILE shr n))
            }
            tiles[k] = patchTile(src).apply { copyPixelsFromBuffer(ByteBuffer.wrap(d.out)) }
        }
        return tiles
    }

    /** [f]'s tiles that change over [base], with smaller copies: new ones, the old may still be on screen. Off the main thread */
    private fun made(f: Filter, base: Array<Bitmap?>): Grid? {
        val out = arrayOfNulls<Bitmap>(base.size)
        parallel(changing.size) { wk, i ->
            val k = changing[i]
            val d = reader(wk)
            out[k] = patchTile(src).also { f.into(it, k % cols * TILE, k / cols * TILE) { b, x, y -> d.draw(base, b, x, y) } }
        }
        return built("", rw, rh, out)?.let { Grid(Spot(region, n), it, region.left.toFloat(), region.top.toFloat()) }
    }

    /** In its layer's offscreen, as the bake: blurs — the selected part replaced; Shadow, Glow — the blurred shape moved, colored and cast. Draw phase */
    override fun draw(c: Canvas, l: Layer, filter: Boolean, bmp: Bitmap?, mask: LayerMask?, patch: Tiles?, k: Float) {
        val g = shown ?: return
        if (!casts) return c.replaceSelected(sel, toFile, fromFile, null) { u -> u.save(); u.clipRect(clip); g.draw(u, plain, filter, cell, k); u.restore() }
        val t = color() to gain()
        if (t.second <= 0f) return
        if (t != tint) { tinted.colorFilter = castFilter(t.first, t.second); tint = t }
        val d = shift().also { toFile.mapVectors(it) }
        c.cast(sel, toFile) { u -> u.save(); u.translate(d[0], d[1]); u.clipRect(clip); g.draw(u, tinted, filter, cell, k); u.restore() }
    }
}

/** Runs [s]'s preview; its panel, away while the color panel is open; the angle dial over it while the knob is held */
@Composable
fun EffectOverlay(s: EffectTool, picture: PipetteSource, open: ToolPopup?, onPopup: (OpenPopup?) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    LaunchedEffect(s) { s.preview() }
    val dial = remember { AngleDial() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { dial.origin = it.positionInWindow() }) {
        val away = open == ToolPopup.Color
        val shown = remember { MutableTransitionState(false) }
        LaunchedEffect(away) { shown.targetState = !away }
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), PANEL_IN, PANEL_OUT) {
            val w = Modifier.width(IntrinsicSize.Max) // = коррекции
            when (s.kind) {
                Effect.GaussianBlur -> GaussianPanel(s.o, w, onCancel, onConfirm)
                Effect.MotionBlur -> MotionPanel(s.o, dial, w, onCancel, onConfirm)
                Effect.Shadow -> ShadowPanel(s.o, dial, picture, onPopup, w, onCancel, onConfirm)
                else -> GlowPanel(s.o, picture, onPopup, w, onCancel, onConfirm)
            }
        }
        DialOverlay(dial)
    }
}

/** Blur, Cancel, ✓ */
@Composable
private fun GaussianPanel(o: EffectOptions, modifier: Modifier, onCancel: () -> Unit, onConfirm: () -> Unit) = BottomPanel(false, null, modifier, buttons = {
    EffectSlider(R.string.tool_blur, listOf(R.string.tool_blur), o.blur)
    ConfirmButtons(onCancel, onConfirm)
}) {}

/** Row 1: angle, Cancel, ✓; row 2: blur */
@Composable
private fun MotionPanel(o: EffectOptions, dial: AngleDial, modifier: Modifier, onCancel: () -> Unit, onConfirm: () -> Unit) = BottomPanel(false, null, modifier, buttons = {
    AngleRow(o.motionAngle, MOTION_LABELS, dial)
    Spacer(Modifier.weight(1f))
    ConfirmButtons(onCancel, onConfirm)
}, more = { EffectSlider(R.string.tool_blur, MOTION_LABELS, o.motion) }) {}

/** Two pages, the arrows turn them. Row 1: angle and color | offset; ↑, ✓. Row 2: blur | opacity; ↓, Cancel */
@Composable
private fun ShadowPanel(o: EffectOptions, dial: AngleDial, picture: PipetteSource, onPopup: (OpenPopup?) -> Unit, modifier: Modifier, onCancel: () -> Unit,
    onConfirm: () -> Unit) {
    val page = remember { mutableIntStateOf(0) }
    BottomPanel(false, null, modifier, buttons = {
        PagedRow(page.intValue) {
            if (it == 0) {
                AngleRow(o.shadowAngle, SHADOW_LABELS, dial)
                ColorBtn(o.shadowColor, ColorOpacity(o.shadowOpacity), picture, onPopup, Modifier, BTN)
            } else EffectSlider(R.string.offset, SHADOW_LABELS, o.shadowOffset, "px")
        }
        PageBtn(page, up = true)
        ConfirmBtn(onConfirm)
    }, more = {
        PagedRow(page.intValue) { if (it == 0) EffectSlider(R.string.tool_blur, SHADOW_LABELS, o.shadowBlur) else EffectSlider(R.string.opacity, SHADOW_LABELS, o.shadowOpacity) }
        PageBtn(page, up = false)
        CancelBtn(onCancel)
    }) {}
}

/** Row 1: blur, color, ✓; row 2: intensity, Cancel */
@Composable
private fun GlowPanel(o: EffectOptions, picture: PipetteSource, onPopup: (OpenPopup?) -> Unit, modifier: Modifier, onCancel: () -> Unit, onConfirm: () -> Unit) =
    BottomPanel(false, null, modifier, buttons = {
        EffectSlider(R.string.tool_blur, GLOW_LABELS, o.glowBlur)
        ColorBtn(o.glowColor, null, picture, onPopup, Modifier, BTN)
        ConfirmBtn(onConfirm)
    }, more = {
        EffectSlider(R.string.intensity, GLOW_LABELS, o.glowIntensity)
        CancelBtn(onCancel)
    }) {}

@Composable
private fun RowScope.EffectSlider(label: Int, labels: List<Int>, o: IntOption, suffix: String = "%") =
    AlignedSlider(label, labels, o.value, o.range, suffix, resettable = false) { o.value = it }

/** The knob's dial, shown while [held]: the angle being set */
@Stable
private class AngleDial {
    var held by mutableStateOf<Int?>(null)
    var origin = Offset.Zero // the overlay's top-left, window px
    val knob = Bounds()
    val field = Bounds()
}

/** Any whole angle as one of ANGLES; those kept */
private fun wrapped(a: Int) = if (a in ANGLES) a else Math.floorMod(a + 180, 360) - 180

/** "Angle" with its field — the angle being set while the knob is held — and the knob */
@Composable
private fun AngleRow(o: IntOption, labels: List<Int>, dial: AngleDial) {
    Row(Modifier.trackBounds(dial.field), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        AlignedLabel(R.string.angle, labels)
        PanelField((dial.held ?: o.value).toFloat(), {}, "°") { o.value = wrapped(it.roundToInt()) }
    }
    AngleKnob(o.value, dial) { o.value = it }
}

/** Its needle at [value]°. A press shows the dial; the finger's direction from the center sets the angle — near the ring by 1°, farther off by 10°, then by 45°;
released → [onSet] */
@Composable
private fun AngleKnob(value: Int, dial: AngleDial, onSet: (Int) -> Unit) {
    val now by rememberUpdatedState(value)
    val set by rememberUpdatedState(onSet)
    Box(Modifier.size(BTN).trackBounds(dial.knob).pointerInput(dial) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val c = Offset(size.width / 2f, size.height / 2f)
            val near = (DIAL_R + NEAR).toPx()
            val far = (DIAL_R + FAR).toPx()
            val from = now
            dial.held = from
            try {
                val released = drag(down.id) { p ->
                    p.consume()
                    val v = p.position - c
                    val d = v.getDistance()
                    // Over the knob the direction jumps: kept
                    if (d > c.x) {
                        val step = if (d < near) 1 else if (d < far) 10 else 45
                        dial.held = (atan2(v.y, v.x) / DEG / step).roundToInt() * step
                    }
                }
                dial.held?.takeIf { released && it != from }?.let(set)
            } finally { dial.held = null }
        }
    }.drawBehind { knob(center, size.minDimension / 2, dial.held ?: value) })
}

/** A dark disc with a gray hub, the needle from the hub toward [deg]° */
private fun DrawScope.knob(c: Offset, r: Float, deg: Int) {
    drawCircle(TranslucentGray, r, c)
    drawCircle(ScreenBg, r - 1.dp.toPx(), c)
    drawCircle(BtnGray, r * KNOB_IN, c)
    val u = Offset(cos(deg * DEG), sin(deg * DEG))
    drawLine(Color.White, c + u * (r * KNOB_IN), c + u * (r - 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
}

/** While the knob is held: the ring of ticks about it with the knob in the middle; the angle field stays uncovered */
@Composable
private fun DialOverlay(dial: AngleDial) {
    val a = dial.held ?: return
    Spacer(Modifier.fillMaxSize().drawBehind {
        val c = dial.knob.rect.center - dial.origin
        val f = dial.field.rect.translate(-dial.origin)
        clipRect(f.left, f.top, f.right, f.bottom, ClipOp.Difference) {
            val r = DIAL_R.toPx()
            drawCircle(DIAL_BG, r, c)
            for (t in 0 until 360 step TICK_STEP) {
                val u = Offset(cos(t * DEG), sin(t * DEG))
                val from = if (t % 90 == 0) 0.55f else if (t % 45 == 0) 0.7f else 0.8f
                drawLine(Color.White, c + u * (r * from), c + u * (r * 0.92f), (if (t % 45 == 0) 2.dp else 1.dp).toPx(), StrokeCap.Round)
            }
            knob(c, BTN.toPx() / 2, a)
        }
    })
}
