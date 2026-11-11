package com.perkus.imagoedit.data

import android.graphics.Bitmap
import androidx.annotation.Keep

/** Native pixel loops (cpp/kernels.c) */
@Keep
internal object Kernels {
    init { System.loadLibrary("imagoedit") }

    /** Dab.visit: one dab into a block; [lut] — flow·tip by squared distance / r², [out] (null — none) gets the coverage where the dab reaches */
    external fun visit(x: Float, y: Float, px: Float, py: Float, r: Float, bx: Int, by: Int, bw: Int, bh: Int, stride: Int, lut: IntArray, acc: ByteArray, cur: ByteArray, out: ByteArray?)

    /** Every dab of [xy] (× [s]) reaching a block, in order: [acc] gets the coverage, [cur] is scratch */
    external fun visits(xy: FloatArray, s: Float, r: Float, bx: Int, by: Int, bw: Int, bh: Int, stride: Int, lut: IntArray, acc: ByteArray, cur: ByteArray)

    /** Smear.dab's px loop over [profile] (256 = 1): [acc] carries the color, [fp] takes the smudge; [first] — only takes the color up */
    external fun smear(profile: IntArray, acc: IntArray, fp: ByteArray, q: Int, first: Boolean)

    /** Recolor.swap: [src]'s px (ARGB_8888) into [dst] (as large; null — only tells; may be src), only where [only] (null — all; a byte per px) isn't 0;
    [from] — 1..SAMPLES samples. true — some px change */
    external fun replace(src: Bitmap, dst: Bitmap?, from: IntArray, to: Int, opacity: Float, threshold: Float, amount: Float, only: ByteArray?): Boolean

    /** Recolor.joined: [out] (a byte per [src]'s px) gets MATCH where Recolor replaces the px at all, else 0; true — some */
    external fun near(src: Bitmap, from: IntArray, threshold: Float, out: ByteArray): Boolean

    /** Relight's: the luminance ratio Highlights and Shadows (−1..1) give a px by its surroundings' lightness */
    external fun gains(highlights: Float, shadows: Float): FloatArray

    /** Relight's: luminance by lightness after Whites and Blacks (−1..1) */
    external fun points(whites: Float, blacks: Float): FloatArray

    /** Relight.adjust: [src]'s px (ARGB_8888; its px (0, 0) at file px ([x], [y]), each [s] file px a side) into [dst] (as large; null — only tells; may be src):
    by [gains] at the surroundings' lightness from [base] with [cells] (Base) or null, then by [points] or null. true — some px change */
    external fun relight(src: Bitmap, dst: Bitmap?, x: Int, y: Int, s: Int, gains: FloatArray?, base: FloatArray?, cells: IntArray?, points: FloatArray?): Boolean

    /** Relight's Guide: [out] gets Σα, ΣαL, ΣαL² (L — lightness) of each of [src]'s cells 2ⁿ px a side (a row of them ⌈width / 2ⁿ⌉) over src's px
    [x0]..[x1) × [y0]..[y1), the rest 0 */
    external fun lightness(src: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, n: Int, out: FloatArray)
}
