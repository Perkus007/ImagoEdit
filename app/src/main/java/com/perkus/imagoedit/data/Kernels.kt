package com.perkus.imagoedit.data

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
}
