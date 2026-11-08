// Pixel loops of brush strokes (Stroke.kt Dab: visits) and of the Finger smudge (Blur.kt Smear.dab); Kotlin side — Kernels.kt
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>

#define KEEP_ONE (1 << 15)

/* Coverage a (0..255) under b: SRC_OVER */
static inline int over(int a, int b) { return a + b - (a * b + 127) / 255; }

/* The px a dab at (x, y), radius² r2, holds on the row centered at cy: [lo, hi]; none (also NaN y) → 0. Both a dab and the one after it use this: the same bounds */
static inline int span(float x, float y, float r2, float cy, int* lo, int* hi) {
    float d = r2 - (cy - y) * (cy - y);
    if (!(d > 0.f)) return 0;
    float h = sqrtf(d);
    *lo = (int) floorf(x - h - 0.5f) + 1;
    *hi = (int) ceilf(x + h - 0.5f) - 1;
    return 1;
}

/* Dab.visit: the dab at (x, y), radius r, into the bw×bh block at (bx, by) (stride B a row). cur — each px's visit going on, acc — its done ones;
the dab before at (px, py) (NaN — none) tells a visit going on from a new one. lut[0..n] — flow·tip by squared distance / r². out (NULL — none) gets acc over cur */
static void visit(float x, float y, float px, float py, float r, int bx, int by, int bw, int bh, int stride, const jint* lut, int n,
                  uint8_t* acc, uint8_t* cur, uint8_t* out) {
    float r2 = r * r, k = n / r2;
    int top = (int) floorf(y - r), bottom = (int) ceilf(y + r);
    if (top < by) top = by;
    if (bottom > by + bh - 1) bottom = by + bh - 1;
    for (int row = top; row <= bottom; row++) {
        float cy = row + 0.5f, dy2 = (cy - y) * (cy - y);
        int lo, hi, plo = 0, phi = -1;
        if (!span(x, y, r2, cy, &lo, &hi)) continue;
        if (lo < bx) lo = bx;
        if (hi > bx + bw - 1) hi = bx + bw - 1;
        span(px, py, r2, cy, &plo, &phi);
        int i = (row - by) * stride + lo - bx;
        for (int col = lo; col <= hi; col++, i++) {
            float dx = col + 0.5f - x;
            int j = (int) ((dx * dx + dy2) * k);
            int v = lut[j < n ? j : n], c = cur[i];
            if (col < plo || col > phi) { // a new visit: the last one is done
                if (c) acc[i] = (uint8_t) over(acc[i], c);
                c = 0;
            }
            if (v > c) c = v;
            cur[i] = (uint8_t) c;
            if (out) out[i] = (uint8_t) over(acc[i], c);
        }
    }
}

/* Arrays held critical: no JNI call in between. NULL → OutOfMemoryError pending: no more taken, the held ones released, back to Kotlin */
#define HOLD(a) (*env)->GetPrimitiveArrayCritical(env, a, NULL)
#define FREE(a, p, mode) if (p) (*env)->ReleasePrimitiveArrayCritical(env, a, p, mode)

JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_visit(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat px, jfloat py, jfloat r,
    jint bx, jint by, jint bw, jint bh, jint stride, jintArray lut, jbyteArray acc, jbyteArray cur, jbyteArray out) {
    int n = (*env)->GetArrayLength(env, lut) - 1;
    jint* l = HOLD(lut);
    uint8_t* a = l ? HOLD(acc) : NULL;
    uint8_t* c = a ? HOLD(cur) : NULL;
    uint8_t* o = c && out ? HOLD(out) : NULL;
    if (c && (!out || o)) visit(x, y, px, py, r, bx, by, bw, bh, stride, l, n, a, c, o);
    FREE(out, o, 0);
    FREE(cur, c, 0);
    FREE(acc, a, 0);
    FREE(lut, l, JNI_ABORT);
}

/* Every dab of xy (× s) reaching the block, in order; acc gets the coverage, clear past the block. acc, cur: as long, cleared here */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_visits(JNIEnv* env, jobject thiz, jfloatArray xy, jfloat s, jfloat r,
    jint bx, jint by, jint bw, jint bh, jint stride, jintArray lut, jbyteArray acc, jbyteArray cur) {
    int n = (*env)->GetArrayLength(env, lut) - 1, dabs = (*env)->GetArrayLength(env, xy) / 2;
    size_t size = (size_t) (*env)->GetArrayLength(env, acc);
    float* p = HOLD(xy);
    jint* l = p ? HOLD(lut) : NULL;
    uint8_t* a = l ? HOLD(acc) : NULL;
    uint8_t* c = a ? HOLD(cur) : NULL;
    if (c) {
        memset(a, 0, size);
        memset(c, 0, size);
        for (int i = 0; i < dabs; i++) {
            float x = p[2 * i] * s, y = p[2 * i + 1] * s;
            if (x + r > bx && y + r > by && x - r < bx + bw && y - r < by + bh)
                visit(x, y, i ? p[2 * i - 2] * s : NAN, i ? p[2 * i - 1] * s : NAN, r, bx, by, bw, bh, stride, l, n, a, c, NULL);
        }
        for (size_t i = 0; i < size; i++) a[i] = (uint8_t) over(a[i], c[i]);
    }
    FREE(cur, c, 0);
    FREE(acc, a, 0);
    FREE(lut, l, JNI_ABORT);
    FREE(xy, p, JNI_ABORT);
}

/* Smear.dab's px loop: profile — n² weights (256 = 1), acc — the carried color ×256 (RGBA), fp — the block under the brush, takes the smudge;
q — the kept part of KEEP_ONE; first — only takes the color up */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_smear(JNIEnv* env, jobject thiz, jintArray profile, jintArray acc, jbyteArray fp, jint q, jboolean first) {
    int n = (*env)->GetArrayLength(env, profile);
    jint* m = HOLD(profile);
    jint* a = m ? HOLD(acc) : NULL;
    uint8_t* f = a ? HOLD(fp) : NULL;
    if (f) {
        for (int k = 0; k < n; k++) {
            int w = m[k];
            if (!w) continue;
            jint* ak = a + 4 * k;
            uint8_t* fk = f + 4 * k;
            for (int ch = 0; ch < 4; ch++) {
                int v = fk[ch];
                if (first) { ak[ch] = v << 8; continue; }
                int t = (q * ak[ch] + (KEEP_ONE - q) * (v << 8) + KEEP_ONE / 2) >> 15;
                ak[ch] = t;
                fk[ch] = (uint8_t) (v + (((t - (v << 8)) * w + (1 << 15)) >> 16));
            }
        }
    }
    FREE(fp, f, 0);
    FREE(acc, a, 0);
    FREE(profile, m, JNI_ABORT);
}
