// Pixel loops of brush strokes (Stroke.kt Dab: visits), of the Finger smudge (Blur.kt Smear.dab), of Replace color, Shadows/Highlights and Sharpness
// (Correction.kt), of directional blur (Effect.kt Streaked), of the brushes' edge-aware mode (Edges.kt); Kotlin side — Kernels.kt
#include <android/bitmap.h>
#include <jni.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
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
the dab before at (px, py) (NaN — none) tells a visit going on from a new one. lut[0..n] — flow·tip by squared distance / r². out (NULL — none) gets acc over cur.
m (NULL — none) — the dab's Edges cut, mw×mh px from (mx, my), 0 past them: scales lut */
static void visit(float x, float y, float px, float py, float r, int bx, int by, int bw, int bh, int stride, const jint* lut, int n,
                  uint8_t* acc, uint8_t* cur, uint8_t* out, const uint8_t* m, int mx, int my, int mw, int mh) {
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
        const uint8_t* mr = m && row >= my && row < my + mh ? m + (size_t) (row - my) * mw : NULL;
        int i = (row - by) * stride + lo - bx;
        for (int col = lo; col <= hi; col++, i++) {
            float dx = col + 0.5f - x;
            int j = (int) ((dx * dx + dy2) * k);
            int v = lut[j < n ? j : n], c = cur[i];
            if (m) v = mr && col >= mx && col < mx + mw ? (v * mr[col - mx] + 127) / 255 : 0;
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
    jint bx, jint by, jint bw, jint bh, jint stride, jintArray lut, jbyteArray acc, jbyteArray cur, jbyteArray out, jbyteArray mask, jint mx, jint my, jint mw, jint mh) {
    if (mask && (mw < 0 || mh < 0 || (size_t) (*env)->GetArrayLength(env, mask) < (size_t) mw * (size_t) mh)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "Bad mask");
        return;
    }
    int n = (*env)->GetArrayLength(env, lut) - 1;
    jint* l = HOLD(lut);
    uint8_t* a = l ? HOLD(acc) : NULL;
    uint8_t* c = a ? HOLD(cur) : NULL;
    uint8_t* o = c && out ? HOLD(out) : NULL;
    uint8_t* m = c && (!out || o) && mask ? HOLD(mask) : NULL;
    if (c && (!out || o) && (!mask || m)) visit(x, y, px, py, r, bx, by, bw, bh, stride, l, n, a, c, o, m, mx, my, mw, mh);
    FREE(mask, m, JNI_ABORT);
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
                visit(x, y, i ? p[2 * i - 2] * s : NAN, i ? p[2 * i - 1] * s : NAN, r, bx, by, bw, bh, stride, l, n, a, c, NULL, NULL, 0, 0, 0, 0);
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

/* Replace color, in Oklab (perceptually even). Px within the threshold's distance of a sample are replaced fully, a soft rim past it partly. Each sample on
its own (`from`): in linear light a px lighter than from is from mixed with white (added light: highlights, anti-aliasing, noise), a darker one — from dimmed
(a shade); it becomes `to` mixed or dimmed the same part — no more than its perceived room allows, so dark noise doesn't become spots on a light `to`. What's left
of the px off that model (its own hue, chroma) is turned and scaled as from's chroma into to's, as much light as it was. Where several samples reach a px, their
results are mixed by how fully each replaces it: no seam between their areas. A translucent `to` also takes the sample's share out of the px (un-mixing): no
fringe of it stays on the edges */
#define LIN_MAX 16383 /* steps of the linear → sRGB table: each well under half an 8-bit level */
#define SHADE 0.5f /* weight of lightness and chroma differences in the distance (shades, paler or deeper tints of from); a hue difference counts fully */
#define R_MAX 0.5f /* fully replaced radius at threshold 100%: about black to white */
#define RIM 0.5f /* the soft rim past it, of its radius */
#define R_MIN 0.001f /* the rim at threshold 0: from alone (a neighboring 8-bit level lies ~0.002 off) */
#define M_MAX 2.f /* most a chroma difference grows by */
#define C_REF 0.04f /* chroma from which from's hue counts; below it from is near gray and chroma differences are kept as they are */
#define CACHE_BITS 12
#define SAMPLES 5 /* most samples: Correction.kt SAMPLES */

static float LIN[256]; /* sRGB byte → linear */
static uint8_t SRGB[LIN_MAX + 1]; /* linear × LIN_MAX → sRGB byte */
static uint32_t UNPREMUL[256]; /* 255·2¹⁶ / alpha */

__attribute__((constructor)) static void tables(void) {
    for (int i = 0; i < 256; i++) {
        float c = i / 255.f;
        LIN[i] = c <= 0.04045f ? c / 12.92f : powf((c + 0.055f) / 1.055f, 2.4f);
        UNPREMUL[i] = i ? (255u * 65536u + i / 2) / i : 0;
    }
    for (int i = 0; i <= LIN_MAX; i++) {
        float l = (float) i / LIN_MAX;
        SRGB[i] = (uint8_t) ((l <= 0.0031308f ? l * 12.92f : 1.055f * powf(l, 1 / 2.4f) - 0.055f) * 255.f + 0.5f);
    }
}

/* Two Halley steps from the bit-trick guess: float-exact, several times faster than cbrtf */
static inline float cube_root(float x) {
    if (!(x > 0.f)) return 0.f;
    union { float f; uint32_t i; } u = { x };
    u.i = u.i / 3 + 709921077u;
    float y = u.f;
    for (int n = 0; n < 2; n++) {
        float y3 = y * y * y;
        y *= (y3 + 2.f * x) / (2.f * y3 + x);
    }
    return y;
}

static inline void oklab(const float* rgb, float* lab) {
    float l = cube_root(0.4122214708f * rgb[0] + 0.5363325363f * rgb[1] + 0.0514459929f * rgb[2]);
    float m = cube_root(0.2119034982f * rgb[0] + 0.6806995451f * rgb[1] + 0.1073969566f * rgb[2]);
    float s = cube_root(0.0883024619f * rgb[0] + 0.2817188376f * rgb[1] + 0.6299787005f * rgb[2]);
    lab[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s;
    lab[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s;
    lab[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s;
}

static inline void linear(const float* lab, float* rgb) {
    float l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2];
    float m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2];
    float s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2];
    l = l * l * l;
    m = m * m * m;
    s = s * s * s;
    rgb[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
    rgb[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
    rgb[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;
}

/* Into 0..1 toward its own gray: luminance and hue kept, chroma lost */
static inline void into_gamut(float* c) {
    float y = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2], f = 1.f;
    y = y < 0.f ? 0.f : y > 1.f ? 1.f : y;
    for (int i = 0; i < 3; i++) {
        if (c[i] > 1.f) f = fminf(f, (1.f - y) / (c[i] - y));
        else if (c[i] < 0.f) f = fminf(f, y / (y - c[i]));
    }
    if (f < 1.f) for (int i = 0; i < 3; i++) c[i] = y + f * (c[i] - y);
}

static inline uint32_t srgb(float c) { return SRGB[(int) ((c < 0.f ? 0.f : c > 1.f ? 1.f : c) * LIN_MAX + 0.5f)]; }

static inline float luma(const float* rgb) { return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]; }

/* Oklab L with its toe (Ottosson's Lr): perceived lightness near black as CIELAB's — plain L there is far too steep */
static inline float toe(float l) {
    const float k1 = 0.206f, k2 = 0.03f, k3 = (1.f + k1) / (1.f + k2);
    float x = k3 * l - k1;
    return 0.5f * (x + sqrtf(x * x + 4.f * k2 * k3 * l));
}

static inline void linear_of(jint argb, float* rgb) {
    rgb[0] = LIN[(argb >> 16) & 0xFF];
    rgb[1] = LIN[(argb >> 8) & 0xFF];
    rgb[2] = LIN[argb & 0xFF];
}

typedef struct {
    float s[3], sl[3], c; /* Oklab, linear, chroma */
    float ys, up, down; /* luminance; how much of a px' mixing with white (up) or dimming (down) `to` takes: to's perceived room / the sample's, ≤ 1 */
    float m[2]; /* chroma differences × m (complex: turned and scaled) */
} Sample;

typedef struct {
    Sample f[SAMPLES];
    int n;
    float t[3], tl[3]; /* to: Oklab, linear */
    float core, rim, amount, opacity; /* core — fully replaced distance */
} Swap;

/* from: n (1..SAMPLES) samples */
static void swap_init(Swap* w, const jint* from, int n, jint to, float opacity, float threshold, float amount) {
    linear_of(to, w->tl);
    oklab(w->tl, w->t);
    const float* t = w->t;
    float rt = toe(t[0]);
    w->n = n;
    for (int k = 0; k < n; k++) {
        Sample* f = &w->f[k];
        linear_of(from[k], f->sl);
        oklab(f->sl, f->s);
        const float* s = f->s;
        float c2 = s[1] * s[1] + s[2] * s[2], mr = 1.f, mi = 0.f;
        f->c = sqrtf(c2);
        if (c2 > 0.f) {
            float er = (t[1] * s[1] + t[2] * s[2]) / c2, ei = (t[2] * s[1] - t[1] * s[2]) / c2, e = sqrtf(er * er + ei * ei);
            if (e > M_MAX) {
                er *= M_MAX / e;
                ei *= M_MAX / e;
            }
            float x = fminf(f->c / C_REF, 1.f), b = x * x * (3.f - 2.f * x);
            mr += b * (er - mr);
            mi = b * ei;
        }
        f->m[0] = mr;
        f->m[1] = mi;
        float rs = toe(s[0]);
        f->ys = luma(f->sl);
        f->up = rt < 1.f ? fminf((1.f - rs) / (1.f - rt), 1.f) : 1.f;
        f->down = rt > 0.f ? fminf(rs / rt, 1.f) : 1.f;
    }
    w->core = threshold * threshold * R_MAX;
    w->rim = w->core * RIM + R_MIN;
    w->amount = amount;
    w->opacity = opacity;
}

/* Px c (premultiplied RGBA, alpha a > 0): linear p */
static inline void unpremul(uint32_t c, uint32_t a, float* p) {
    for (int i = 0; i < 3; i++) {
        uint32_t v = (((c >> (8 * i)) & 0xFF) * UNPREMUL[a] + 32768) >> 16;
        p[i] = LIN[v > 255 ? 255 : v];
    }
}

/* Linear p (0..1) at alpha a > 0: a premultiplied RGBA px */
static inline uint32_t pack(const float* p, uint32_t a) {
    uint32_t out = a << 24;
    for (int i = 0; i < 3; i++) out |= ((srgb(p[i]) * a + 127) / 255) << (8 * i);
    return out;
}

/* Px c (premultiplied RGBA, alpha a > 0): linear p and Oklab lab */
static inline void unpack(uint32_t c, uint32_t a, float* p, float* lab) {
    unpremul(c, a, p);
    oklab(p, lab);
}

/* How fully a px of Oklab lab is replaced by each sample, 0..1, into k; returns the most. The distance: ΔL, ΔC weighed by SHADE, ΔH² = Δa² + Δb² − ΔC² */
static float weigh(const Swap* w, const float* lab, float* k) {
    float cl = sqrtf(lab[1] * lab[1] + lab[2] * lab[2]), reach = w->core + w->rim, most = 0.f;
    for (int j = 0; j < w->n; j++) {
        const Sample* f = &w->f[j];
        float dl = lab[0] - f->s[0], da = lab[1] - f->s[1], db = lab[2] - f->s[2], dc = cl - f->c;
        float dist2 = SHADE * SHADE * (dl * dl + dc * dc) + fmaxf(da * da + db * db - dc * dc, 0.f), x = (sqrtf(dist2) - w->core) / w->rim;
        k[j] = dist2 >= reach * reach ? 0.f : x > 0.f ? 1.f - x * x * (3.f - 2.f * x) : 1.f;
        most = fmaxf(most, k[j]);
    }
    return most;
}

/* Px c (premultiplied RGBA) swapped */
static uint32_t swap(const Swap* w, uint32_t c) {
    uint32_t a = c >> 24;
    if (!a) return c;
    float p[3], lab[3], ks[SAMPLES], q[3] = { 0.f }, under[3] = { 0.f }, o[3], sum = 0.f; /* under — the samples' share in the px, mixed as q */
    unpack(c, a, p, lab);
    float k = weigh(w, lab, ks) * w->amount, y = luma(p);
    if (!(k > 0.f)) return c;
    for (int j = 0; j < w->n; j++) {
        if (!(ks[j] > 0.f)) continue;
        const Sample* sp = &w->f[j];
        /* Anchor: white above from's luminance, black below; the px is from moved part f toward it, the result — `to` moved part g */
        float an = y >= sp->ys ? 1.f : 0.f, room = fabsf(an - sp->ys);
        float f = room > 0.f ? fminf(fabsf(y - sp->ys) / room, 1.f) : 0.f, g = f * (an > 0.f ? sp->up : sp->down), mo[3], ba[3], ml[3], r[3];
        for (int i = 0; i < 3; i++) {
            mo[i] = sp->sl[i] + f * (an - sp->sl[i]);
            ba[i] = w->tl[i] + g * (an - w->tl[i]);
        }
        oklab(mo, ml);
        oklab(ba, r);
        /* The px off that model, as much light as it had: an Oklab difference's light goes as L², so onto a lighter result it shrinks (a dark px' tint of its
        neighbors doesn't become a blot), never grows */
        float e1 = lab[1] - ml[1], e2 = lab[2] - ml[2], sc = r[0] > ml[0] ? (ml[0] / r[0]) * (ml[0] / r[0]) : 1.f;
        r[0] += sc * (lab[0] - ml[0]);
        r[1] += sc * (sp->m[0] * e1 - sp->m[1] * e2);
        r[2] += sc * (sp->m[1] * e1 + sp->m[0] * e2);
        for (int i = 0; i < 3; i++) {
            q[i] += ks[j] * r[i];
            under[i] += ks[j] * sp->sl[i];
        }
        sum += ks[j];
    }
    for (int i = 0; i < 3; i++) {
        q[i] = lab[i] + k * (q[i] / sum - lab[i]);
        under[i] /= sum;
    }
    linear(q, o);
    into_gamut(o);
    float keep = 1.f; /* the px' alpha left */
    if (w->opacity < 1.f) {
        /* px = (1 − k)·rest + k·sample: the sample's share taken out (the rest kept a valid color), translucent `to` put in its place */
        float op = w->opacity;
        keep = 1.f - (1.f - op) * k;
        if (!(keep > 0.f)) return 0;
        for (int i = 0; i < 3; i++) {
            float rest = fminf(fmaxf(p[i] - k * under[i], 0.f), 1.f - k);
            o[i] = (op * o[i] + (1.f - op) * rest) / keep;
        }
    }
    uint32_t na = (uint32_t) (a * keep + 0.5f);
    return na ? pack(o, na) : 0;
}

/* The bitmap's px locked; NULL — it isn't RGBA_8888 or can't be locked */
static void* lock_px(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info) {
    void* px;
    if (AndroidBitmap_getInfo(env, bitmap, info) || info->format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &px)) return NULL;
    return px;
}

/* An OutOfMemoryError from HOLD set aside while the bitmaps are unlocked, then rethrown; else err as an IOException */
static void unlock_px(JNIEnv* env, jobject a, void* ap, jobject b, void* bp, const char* err) {
    jthrowable pending = (*env)->ExceptionOccurred(env);
    if (pending) (*env)->ExceptionClear(env);
    if (bp) AndroidBitmap_unlockPixels(env, b);
    if (ap) AndroidBitmap_unlockPixels(env, a);
    if (pending) (*env)->Throw(env, pending);
    else if (err) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), err);
}

/* Recolor's samples into out (SAMPLES long): their count; 0 — none or too many */
static int samples(JNIEnv* env, jintArray from, jint* out) {
    jsize n = (*env)->GetArrayLength(env, from);
    if (n < 1 || n > SAMPLES) return 0;
    (*env)->GetIntArrayRegion(env, from, 0, n, out);
    return n;
}

/* Recolor.swap: src's px into dst (as large; NULL — only tells, stopping at the first change; may be src), only where `only` (NULL — all; a byte per px) isn't 0.
Returns whether some px change */
JNIEXPORT jboolean JNICALL Java_com_perkus_imagoedit_data_Kernels_replace(JNIEnv* env, jobject thiz, jobject src, jobject dst, jintArray from, jint to,
    jfloat opacity, jfloat threshold, jfloat amount, jbyteArray only) {
    AndroidBitmapInfo si, di;
    jint fs[SAMPLES];
    int ns = samples(env, from, fs);
    int apart = dst && !(*env)->IsSameObject(env, src, dst);
    size_t n = only ? (size_t) (*env)->GetArrayLength(env, only) : 0;
    void* sp = lock_px(env, src, &si);
    void* dp = sp && apart ? lock_px(env, dst, &di) : NULL;
    const char* err = !ns ? "Bad samples" : !sp ? "Can't read the bitmap" : apart && (!dp || di.width != si.width || di.height != si.height) ? "Can't write the bitmap"
        : only && n < (size_t) si.width * si.height ? "Mask too small" : NULL;
    jboolean changed = JNI_FALSE;
    uint8_t* ok = !err && only ? HOLD(only) : NULL;
    if (!err && (!only || ok)) {
        Swap w;
        swap_init(&w, fs, ns, to, opacity, threshold, amount);
        uint8_t* out = apart ? dp : dst ? sp : NULL;
        uint32_t stride = apart ? di.stride : si.stride;
        uint32_t keys[1 << CACHE_BITS] = { 0 }, vals[1 << CACHE_BITS] = { 0 }; /* px → swapped: a flat color is swapped once; 0 → 0 holds */
        for (uint32_t y = 0; y < si.height && !(changed && !out); y++) {
            const uint32_t* s = (const uint32_t*) ((const uint8_t*) sp + (size_t) y * si.stride);
            uint32_t* d = out ? (uint32_t*) (out + (size_t) y * stride) : NULL;
            const uint8_t* row = ok ? ok + (size_t) y * si.width : NULL;
            for (uint32_t x = 0; x < si.width; x++) {
                uint32_t c = s[x], r = c;
                if (!row || row[x]) {
                    uint32_t h = (c * 2654435761u) >> (32 - CACHE_BITS);
                    if (keys[h] == c) r = vals[h];
                    else {
                        r = swap(&w, c);
                        keys[h] = c;
                        vals[h] = r;
                    }
                }
                if (r != c) {
                    changed = JNI_TRUE;
                    if (!d) break;
                }
                if (d) d[x] = r;
            }
        }
    }
    FREE(only, ok, JNI_ABORT);
    unlock_px(env, src, sp, dst, apart ? dp : NULL, err);
    return changed;
}

/* Recolor.joined: `out` (a byte per src's px) gets 1 where the px is replaced at all, else 0. Returns whether some are */
JNIEXPORT jboolean JNICALL Java_com_perkus_imagoedit_data_Kernels_near(JNIEnv* env, jobject thiz, jobject src, jintArray from, jfloat threshold, jbyteArray out) {
    AndroidBitmapInfo si;
    jint fs[SAMPLES];
    int ns = samples(env, from, fs);
    size_t n = (size_t) (*env)->GetArrayLength(env, out);
    void* sp = lock_px(env, src, &si);
    const char* err = !ns ? "Bad samples" : !sp ? "Can't read the bitmap" : n < (size_t) si.width * si.height ? "Mask too small" : NULL;
    jboolean any = JNI_FALSE;
    uint8_t* f = !err ? HOLD(out) : NULL;
    if (f) {
        Swap w;
        swap_init(&w, fs, ns, fs[0], 1.f, threshold, 1.f);
        uint32_t keys[1 << CACHE_BITS] = { 0 };
        uint8_t vals[1 << CACHE_BITS] = { 0 }; /* 0 → not near holds */
        for (uint32_t y = 0; y < si.height; y++) {
            const uint32_t* s = (const uint32_t*) ((const uint8_t*) sp + (size_t) y * si.stride);
            uint8_t* row = f + (size_t) y * si.width;
            for (uint32_t x = 0; x < si.width; x++) {
                uint32_t c = s[x], h = (c * 2654435761u) >> (32 - CACHE_BITS);
                if (keys[h] != c) {
                    float p[3], lab[3], ks[SAMPLES];
                    keys[h] = c;
                    vals[h] = 0;
                    if (c >> 24) {
                        unpack(c, c >> 24, p, lab);
                        vals[h] = weigh(&w, lab, ks) > 0.f;
                    }
                }
                row[x] = vals[h];
                any |= vals[h];
            }
        }
    }
    FREE(out, f, 0);
    unlock_px(env, src, sp, NULL, NULL, err);
    return any;
}

/* Shadows/Highlights (Correction.kt Relight). Lightness — CIE L* / 100 of the luminance: perceptually even, linear near black. Highlights and Shadows move the lightness of
a px' surroundings (Correction.kt Base: its own lightness through an edge-aware blur, A·L + B) along a curve, the px — by the same luminance ratio: its local
contrast and chromaticity are kept, black and white too. Whites and Blacks then move the px' own lightness about the ends: Whites — the white point (past it —
clipped), Blacks − the black point (below it — clipped), Blacks + the darkest tones lifted, black kept (a raised black point would fade it all to gray).
Luminance only scales: black stays black. A negative amount — the positive curve's inverse (Blacks apart): monotonic either way, −x undoes +x */
#define LSTAR_N 4096 /* steps of the luminance → lightness table over 0..1 */
#define TONE_N 1024 /* steps of the gain table over the surroundings' lightness 0..1 */
#define POINT_N 2048 /* steps of the luminance table over lightness 0..POINT_MAX */
#define POINT_MAX 2.f /* lightness past which a px is white anyway */
#define LOCAL 0.2f /* Highlights/Shadows at 100%: the most the surroundings' lightness moves, at 3/4 / 1/4 (Shadows: deep shadows up to ×4 in luminance) */
#define POINT 0.2f /* Whites/Blacks at 100%: how far the white / black point moves; Blacks +: the most the tones below DEEP move, of DEEP */
#define DEEP 0.4f /* Blacks +: the tones it lifts, lightness below (black up to ×3 in luminance) */
#define PEAK (256.f / 27.f) /* b(1 − b)³ → 1 at its peak */
#define BISECT 24 /* halvings of an inverse's interval: past float precision */

static float LSTAR[LSTAR_N + 1];

/* Lightness of linear luminance y ≥ 0 */
static float lstar(float y) { return y > 216.f / 24389.f ? 1.16f * cbrtf(y) - 0.16f : y * (24389.f / 2700.f); }

/* Linear luminance of lightness l */
static float lum(float l) {
    if (l <= 0.08f) return l * (2700.f / 24389.f);
    float t = (l + 0.16f) / 1.16f;
    return t * t * t;
}

__attribute__((constructor)) static void lightness_table(void) {
    for (int i = 0; i <= LSTAR_N; i++) LSTAR[i] = lstar((float) i / LSTAR_N);
}

/* Table t (n steps) at x, linear between steps; past its ends — the end */
static inline float sample(const float* t, int n, float x) {
    if (!(x > 0.f)) return t[0];
    if (x >= (float) n) return t[n];
    int i = (int) x;
    return t[i] + (x - (float) i) * (t[i + 1] - t[i]);
}

static inline float lightness(float y) { return y <= 1.f ? sample(LSTAR, LSTAR_N, y * LSTAR_N) : lstar(y); }

static inline float smooth(float x) { return x <= 0.f ? 0.f : x >= 1.f ? 1.f : x * x * (3.f - 2.f * x); }

/* A curve at amount 0 < k ≤ LOCAL / POINT: increasing over x ± k, |f(x) − x| ≤ k */
typedef float (*Curve)(float x, float k);

/* Shadows: lifted most at 1/4, 0 and 1 kept */
static float lift(float b, float k) {
    float r = 1.f - b;
    return b + k * PEAK * b * r * r * r;
}

/* Highlights: lowered most at 3/4, 0 and 1 kept */
static float lower(float b, float k) { return b - k * PEAK * b * b * b * (1.f - b); }

/* Whites: the upper half raised, by k at white: the white point at f⁻¹(1) */
static float whiten(float l, float k) { return l + k * smooth(2.f * l - 1.f); }

/* Blacks −: the lower half lowered, by k at black: the black point at f⁻¹(0) */
static float blacken(float l, float k) { return l - k * (1.f - smooth(2.f * l)); }

/* Blacks +: the tones below DEEP lifted as Shadows' curve, most at DEEP / 4, black and DEEP kept */
static float deepen(float l, float k) { return l < DEEP ? DEEP * lift(l / DEEP, k) : l; }

/* f at signed amount k: negative — f's inverse at −k */
static float bend(Curve f, float k, float x) {
    if (k > 0.f) return f(x, k);
    if (!(k < 0.f)) return x;
    float lo = x + k, hi = x - k;
    for (int i = 0; i < BISECT; i++) {
        float m = 0.5f * (lo + hi);
        if (f(m, -k) < x) lo = m;
        else hi = m;
    }
    return 0.5f * (lo + hi);
}

static jfloatArray table(JNIEnv* env, const float* t, int n) {
    jfloatArray out = (*env)->NewFloatArray(env, n);
    if (out) (*env)->SetFloatArrayRegion(env, out, 0, n, t);
    return out;
}

/* Relight.gains: the luminance ratio by the surroundings' lightness, TONE_N steps over 0..1 */
JNIEXPORT jfloatArray JNICALL Java_com_perkus_imagoedit_data_Kernels_gains(JNIEnv* env, jobject thiz, jfloat highlights, jfloat shadows) {
    float t[TONE_N + 1];
    for (int i = 1; i <= TONE_N; i++) {
        float b = (float) i / TONE_N;
        t[i] = lum(fmaxf(bend(lower, -highlights * LOCAL, bend(lift, shadows * LOCAL, b)), 0.f)) / lum(b);
    }
    t[0] = t[1]; /* the ratio's limit at black, near enough */
    return table(env, t, TONE_N + 1);
}

/* Relight.points: luminance by lightness, POINT_N steps over 0..POINT_MAX */
JNIEXPORT jfloatArray JNICALL Java_com_perkus_imagoedit_data_Kernels_points(JNIEnv* env, jobject thiz, jfloat whites, jfloat blacks) {
    float t[POINT_N + 1];
    for (int i = 0; i <= POINT_N; i++) {
        float l = (float) i * POINT_MAX / POINT_N;
        t[i] = lum(fmaxf(bend(whiten, whites * POINT, blacks > 0.f ? deepen(l, blacks * POINT) : blacken(l, -blacks * POINT)), 0.f));
    }
    return table(env, t, POINT_N + 1);
}

/* Px c (premultiplied RGBA, alpha > 0): by gains at its surroundings' lightness a·L + b (gains NULL — none), then by points (NULL — none) */
static uint32_t relit(uint32_t c, const float* gains, float a, float b, const float* points) {
    uint32_t al = c >> 24;
    float p[3], k = 1.f;
    unpremul(c, al, p);
    float y = luma(p);
    if (gains) k = sample(gains, TONE_N, (a * lightness(y) + b) * TONE_N);
    if (points) k = y > 0.f ? sample(points, POINT_N, lightness(y * k) * (POINT_N / POINT_MAX)) / y : 0.f;
    for (int i = 0; i < 3; i++) p[i] *= k;
    into_gamut(p);
    return pack(p, al);
}

/* Relight.adjust: src's px (its px (0, 0) at file px (x, y), each s file px a side) into dst (as large; NULL — only tells, stopping at the first change; may be
src). gains — by the surroundings' lightness from base (A, B a cell; cells: gw, gh, gx, gy, n — gw × gh cells 2ⁿ px a side from cell (gx, gy), bilinear between
their centers), NULL — none; points — NULL — none. Returns whether some px change */
JNIEXPORT jboolean JNICALL Java_com_perkus_imagoedit_data_Kernels_relight(JNIEnv* env, jobject thiz, jobject src, jobject dst, jint x, jint y, jint s,
    jfloatArray gains, jfloatArray base, jintArray cells, jfloatArray points) {
    AndroidBitmapInfo si, di;
    jint at[5] = { 0 };
    const char* bad = NULL;
    if (gains) {
        if (!base || !cells || (*env)->GetArrayLength(env, cells) < 5 || (*env)->GetArrayLength(env, gains) != TONE_N + 1) bad = "Bad base";
        else {
            (*env)->GetIntArrayRegion(env, cells, 0, 5, at);
            if (at[0] < 1 || at[1] < 1 || at[4] < 0 || at[4] > 16 || (size_t) (*env)->GetArrayLength(env, base) < 2 * (size_t) at[0] * (size_t) at[1]) bad = "Bad base";
        }
    }
    if (!bad && points && (*env)->GetArrayLength(env, points) != POINT_N + 1) bad = "Bad points";
    int apart = dst && !(*env)->IsSameObject(env, src, dst);
    void* sp = lock_px(env, src, &si);
    void* dp = sp && apart ? lock_px(env, dst, &di) : NULL;
    const char* err = bad ? bad : !sp ? "Can't read the bitmap" : apart && (!dp || di.width != si.width || di.height != si.height) ? "Can't write the bitmap" : NULL;
    jboolean changed = JNI_FALSE;
    float* g = !err && gains ? HOLD(gains) : NULL;
    float* ab = g ? HOLD(base) : NULL;
    float* pt = !err && points && (!gains || ab) ? HOLD(points) : NULL;
    if (!err && (!gains || ab) && (!points || pt)) {
        int gw = at[0], gh = at[1];
        float inv = ldexpf(1.f, -at[4]), ox = (float) at[2] + 0.5f, oy = (float) at[3] + 0.5f;
        uint8_t* out = apart ? dp : dst ? sp : NULL;
        uint32_t stride = apart ? di.stride : si.stride;
        uint32_t keys[1 << CACHE_BITS] = { 0 }, vals[1 << CACHE_BITS] = { 0 }; /* without gains: px → relit */
        for (uint32_t j = 0; j < si.height && !(changed && !out); j++) {
            const uint32_t* row = (const uint32_t*) ((const uint8_t*) sp + (size_t) j * si.stride);
            uint32_t* d = out ? (uint32_t*) (out + (size_t) j * stride) : NULL;
            const float *r0 = ab, *r1 = ab;
            float fv = 0.f;
            if (ab) {
                float v = fminf(fmaxf(((float) y + ((float) j + 0.5f) * (float) s) * inv - oy, 0.f), (float) (gh - 1));
                int v0 = (int) v;
                fv = v - (float) v0;
                r0 = ab + 2 * (size_t) v0 * (size_t) gw;
                r1 = v0 + 1 < gh ? r0 + 2 * (size_t) gw : r0;
            }
            for (uint32_t i = 0; i < si.width; i++) {
                uint32_t c = row[i], r = c;
                if (c >> 24) {
                    if (ab) {
                        float u = fminf(fmaxf(((float) x + ((float) i + 0.5f) * (float) s) * inv - ox, 0.f), (float) (gw - 1));
                        int u0 = (int) u, u1 = u0 + 1 < gw ? u0 + 1 : u0;
                        float fu = u - (float) u0;
                        const float *p00 = r0 + 2 * u0, *p01 = r0 + 2 * u1, *p10 = r1 + 2 * u0, *p11 = r1 + 2 * u1;
                        float a0 = p00[0] + fu * (p01[0] - p00[0]), a1 = p10[0] + fu * (p11[0] - p10[0]);
                        float b0 = p00[1] + fu * (p01[1] - p00[1]), b1 = p10[1] + fu * (p11[1] - p10[1]);
                        r = relit(c, g, a0 + fv * (a1 - a0), b0 + fv * (b1 - b0), pt);
                    } else {
                        uint32_t h = (c * 2654435761u) >> (32 - CACHE_BITS);
                        if (keys[h] == c) r = vals[h];
                        else {
                            r = relit(c, NULL, 0.f, 0.f, pt);
                            keys[h] = c;
                            vals[h] = r;
                        }
                    }
                }
                if (r != c) {
                    changed = JNI_TRUE;
                    if (!d) break;
                }
                if (d) d[i] = r;
            }
        }
    }
    FREE(points, pt, JNI_ABORT);
    FREE(base, ab, JNI_ABORT);
    FREE(gains, g, JNI_ABORT);
    unlock_px(env, src, sp, dst, apart ? dp : NULL, err);
    return changed;
}

/* Relight's Guide: out — src's cells 2ⁿ px a side, a row of them ⌈w / 2ⁿ⌉ — gets each one's Σα, ΣαL, ΣαL² (α 0..1, L — lightness) over src's px
[x0, x1) × [y0, y1), the rest 0 */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_lightness(JNIEnv* env, jobject thiz, jobject src, jint x0, jint y0, jint x1, jint y1, jint n,
    jfloatArray out) {
    AndroidBitmapInfo si;
    size_t len = (size_t) (*env)->GetArrayLength(env, out);
    void* sp = lock_px(env, src, &si);
    uint32_t q = 0, cells = 0;
    if (sp && n >= 0 && n <= 16) {
        q = (si.width + (1u << n) - 1) >> n;
        cells = q * ((si.height + (1u << n) - 1) >> n);
    }
    const char* err = !sp ? "Can't read the bitmap" : !q ? "Bad cells" : x0 < 0 || y0 < 0 || x1 > (jint) si.width || y1 > (jint) si.height ? "Bad rect"
        : len < 3 * (size_t) cells ? "Sums too small" : NULL;
    float* f = !err ? HOLD(out) : NULL;
    if (f) {
        memset(f, 0, 3 * (size_t) cells * sizeof(float));
        uint32_t keys[1 << CACHE_BITS] = { 0 };
        float vals[1 << CACHE_BITS]; /* px → lightness: read only after written */
        for (jint j = y0; j < y1; j++) {
            const uint32_t* row = (const uint32_t*) ((const uint8_t*) sp + (size_t) j * si.stride);
            float* e = f + 3 * (size_t) (j >> n) * q;
            for (jint i = x0; i < x1; i++) {
                uint32_t c = row[i], a = c >> 24;
                if (!a) continue;
                uint32_t h = (c * 2654435761u) >> (32 - CACHE_BITS);
                if (keys[h] != c) {
                    float p[3];
                    unpremul(c, a, p);
                    keys[h] = c;
                    vals[h] = lightness(luma(p));
                }
                float l = vals[h], w = (float) a * (1.f / 255.f), *cell = e + 3 * (i >> n);
                cell[0] += w;
                cell[1] += w * l;
                cell[2] += w * l * l;
            }
        }
    }
    FREE(out, f, 0);
    unlock_px(env, src, sp, NULL, NULL, err);
}

/* Sharpness (Correction.kt Sharpen): an unsharp mask of the luma. A px' surroundings — the Gaussian (σ px) mean of the px about it weighed by alpha: transparent
ones don't count, the layer's edge gets no halo. The px' gamma-encoded luma (detail as the eye sees it) moves off theirs amount×, each channel alike: no color
fringes; past 0..1 the chroma gives way. Alpha kept */
#define SHARP_REACH 16 /* most kernel radius, px */

/* Sharpen.into: out's px (i, j) — src's px (i + m, j + m) sharpened; src — out's px with m px (the kernel's radius) about them */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_sharpen(JNIEnv* env, jobject thiz, jobject src, jobject out, jint m, jfloat sigma, jfloat amount) {
    AndroidBitmapInfo si, oi;
    int apart = !(*env)->IsSameObject(env, src, out);
    void* sp = lock_px(env, src, &si);
    void* op = sp && apart ? lock_px(env, out, &oi) : NULL;
    const char* err = !sp ? "Can't read the bitmap" : !op ? "Can't write the bitmap" : m < 0 || m > SHARP_REACH || !(sigma > 0.f)
        || si.width != oi.width + 2u * (uint32_t) m || si.height != oi.height + 2u * (uint32_t) m ? "Bad sharpen" : NULL;
    float* rows = !err ? malloc((size_t) si.height * oi.width * 4 * sizeof(float)) : NULL; /* src's rows blurred over out's columns: premultiplied RGBA */
    if (!err && !rows) err = "Out of memory";
    if (!err) {
        uint32_t ow = oi.width, n = 2 * (uint32_t) m + 1;
        float w[2 * SHARP_REACH + 1], sum = 0.f;
        for (uint32_t k = 0; k < n; k++) {
            float d = (float) k - (float) m;
            w[k] = expf(-0.5f * d * d / (sigma * sigma));
            sum += w[k];
        }
        for (uint32_t k = 0; k < n; k++) w[k] /= sum;
        for (uint32_t y = 0; y < si.height; y++) {
            const uint8_t* s = (const uint8_t*) sp + (size_t) y * si.stride;
            float* r = rows + (size_t) y * ow * 4;
            for (uint32_t x = 0; x < ow; x++) {
                float a[4] = { 0.f };
                const uint8_t* q = s + 4 * (size_t) x;
                for (uint32_t k = 0; k < n; k++)
                    for (int c = 0; c < 4; c++) a[c] += w[k] * q[4 * k + c];
                memcpy(r + 4 * (size_t) x, a, sizeof a);
            }
        }
        for (uint32_t y = 0; y < oi.height; y++) {
            const uint32_t* s = (const uint32_t*) ((const uint8_t*) sp + (size_t) (y + (uint32_t) m) * si.stride) + m;
            uint32_t* d = (uint32_t*) ((uint8_t*) op + (size_t) y * oi.stride);
            for (uint32_t x = 0; x < ow; x++) {
                uint32_t c = s[x], al = c >> 24;
                if (!al) {
                    d[x] = c;
                    continue;
                }
                /* b[3] > 0: the px' own alpha has the center's weight */
                float b[4] = { 0.f }, p[3], q[3];
                for (uint32_t k = 0; k < n; k++) {
                    const float* r = rows + ((size_t) (y + k) * ow + x) * 4;
                    for (int i = 0; i < 4; i++) b[i] += w[k] * r[i];
                }
                for (int i = 0; i < 3; i++) {
                    p[i] = (float) ((c >> (8 * i)) & 0xFF) / (float) al;
                    q[i] = b[i] / b[3];
                }
                float dl = amount * (luma(p) - luma(q));
                for (int i = 0; i < 3; i++) p[i] += dl;
                into_gamut(p);
                uint32_t o = al << 24;
                for (int i = 0; i < 3; i++) o |= (uint32_t) (p[i] * (float) al + 0.5f) << (8 * i);
                d[x] = o;
            }
        }
    }
    free(rows);
    unlock_px(env, src, sp, out, op, err);
}

/* Streaked's src: px (along, across) of a line, clamped into src */
typedef struct {
    const uint8_t* px;
    size_t along, across; /* bytes a px */
    int w, h, p0, pa, sa;  /* w along, h across */
    const jint* r;         /* r[i]: the line's offset across at p0 + i */
} Line;

static inline const uint8_t* line_px(const Line* l, int i, int j) {
    int p = l->p0 + i - l->pa, s = j + l->r[i] - l->sa;
    p = p < 0 ? 0 : p >= l->w ? l->w - 1 : p;
    s = s < 0 ? 0 : s >= l->h ? l->h - 1 : s;
    return l->px + (size_t) p * l->along + (size_t) s * l->across;
}

/* Streaked.into (Effect.kt): out's px (p, s) — x, y along x, else y, x — from (p0, s0), each the box average of src's px (p + k, s + r(p + k) − r(p)) for |k| ≤ n,
those at |k| = n + 1 weighing edge / 256: its digital line, nothing resampled. Walked by lines j = s − r(p): a running sum. r — r(i) from p0 − n − 1. src — rendered
from (pa, sa), its px clamped: the raster's edge repeated */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_streak(JNIEnv* env, jobject thiz, jobject src, jobject out, jboolean along_x, jint pa, jint sa,
    jint p0, jint s0, jint n, jint edge, jintArray r) {
    AndroidBitmapInfo si, oi;
    void* sp = lock_px(env, src, &si);
    void* op = sp ? lock_px(env, out, &oi) : NULL;
    int lp = 0, ls = 0;
    if (op) {
        lp = (int) (along_x ? oi.width : oi.height);
        ls = (int) (along_x ? oi.height : oi.width);
    }
    const char* err = !sp ? "Can't read the bitmap" : !op ? "Can't write the bitmap" : n < 0 || edge < 0 || edge > 256 || !si.width || !si.height
        || (*env)->GetArrayLength(env, r) < lp + 2 * n + 2 ? "Bad streak" : NULL;
    jint* rr = !err ? HOLD(r) : NULL;
    if (rr) {
        Line l = { sp, along_x ? 4 : si.stride, along_x ? si.stride : 4, (int) (along_x ? si.width : si.height), (int) (along_x ? si.height : si.width),
                   p0, pa, sa, rr + n + 1 };
        uint8_t* o8 = op;
        size_t oa = along_x ? 4 : oi.stride, oc = along_x ? oi.stride : 4;
        int lo = l.r[0], hi = l.r[0];
        for (int i = 1; i < lp; i++) {
            if (l.r[i] < lo) lo = l.r[i];
            if (l.r[i] > hi) hi = l.r[i];
        }
        uint32_t total = 256u * (uint32_t) (2 * n + 1) + 2u * (uint32_t) edge, w = (uint32_t) edge;
        for (int j = s0 - hi; j <= s0 + ls - 1 - lo; j++) {
            uint32_t a[4] = { 0 };
            for (int i = -n; i < n; i++) {
                const uint8_t* q = line_px(&l, i, j);
                for (int c = 0; c < 4; c++) a[c] += q[c];
            }
            for (int i = 0; i < lp; i++) {
                const uint8_t* q = line_px(&l, i + n, j);
                for (int c = 0; c < 4; c++) a[c] += q[c];
                int s = j + l.r[i];
                if (s >= s0 && s < s0 + ls) {
                    uint32_t e[4] = { 0 };
                    if (w) {
                        const uint8_t* u = line_px(&l, i - n - 1, j);
                        const uint8_t* v = line_px(&l, i + n + 1, j);
                        for (int c = 0; c < 4; c++) e[c] = (uint32_t) u[c] + v[c];
                    }
                    uint8_t* d = o8 + (size_t) i * oa + (size_t) (s - s0) * oc;
                    for (int c = 0; c < 4; c++) d[c] = (uint8_t) ((256u * a[c] + w * e[c] + total / 2) / total);
                }
                q = line_px(&l, i - n, j);
                for (int c = 0; c < 4; c++) a[c] -= q[c];
            }
        }
    }
    FREE(r, rr, JNI_ABORT);
    unlock_px(env, src, sp, out, op, err);
}

/* «С учётом краёв» (Edges.kt): a dab reaches only the px of its circle joined to its center through px like the one under it, the sample. How unlike a px is:
the most a channel of their straight colors differs × their lesser alpha (a faint px' color counts little), or a quarter of their alphas' difference, if more:
an object's soft rim stays with it, transparency next to an opaque px is a wall */

/* t[unlikeness] — likeness: fully up to tolerance, falling to 0 at limit: not like at all, nor joins; ≥ 1 between. limit ≤ tolerance — a hard step past it */
static void alike_table(uint8_t* t, int tolerance, int limit) {
    for (int d = 0; d < 256; d++)
        t[d] = d <= tolerance ? 255 : d >= limit ? 0 : (uint8_t) fmaxf(1.f, smooth((float) (limit - d) / (float) (limit - tolerance)) * 255.f + 0.5f);
}

/* Straight RGBA of premultiplied p */
static inline void straight(const uint8_t* p, int* c) {
    uint32_t a = p[3];
    c[3] = (int) a;
    for (int i = 0; i < 3; i++) {
        uint32_t v = (p[i] * UNPREMUL[a] + 32768) >> 16;
        c[i] = v > 255 ? 255 : (int) v;
    }
}

/* Likeness (by table t) of premultiplied p to the straight sample s */
static inline int alike(const uint8_t* p, const int* s, const uint8_t* t) {
    int c[4], d = 0;
    straight(p, c);
    for (int i = 0; i < 3; i++) {
        int e = abs(c[i] - s[i]);
        if (e > d) d = e;
    }
    d = d * (c[3] < s[3] ? c[3] : s[3]) / 255;
    int da = abs(c[3] - s[3]) / 4;
    return t[d > da ? d : da];
}

/* stack with room for one more past top; NULL — out of memory, stack freed */
static int* room(int* stack, size_t top, size_t* cap) {
    if (top < *cap) return stack;
    int* g = realloc(stack, 2 * *cap * sizeof(int));
    if (!g) free(stack);
    else *cap *= 2;
    return g;
}

/* Open to a flood: likeness past 0, or (gaps) below full */
static inline int passable(uint8_t v, int gaps) { return gaps ? v < 255 : v > 0; }

/* Scanline flood from px `from` over the px of the circle's spans lo..hi not seen and passable, 4-connected (gaps: 8-), marking them seen.
0 — out of memory, *stack freed */
static int flood(const uint8_t* o, uint8_t* seen, const int* lo, const int* hi, int w, int h, int from, int gaps, int** stack, size_t* cap) {
    size_t top = 0;
    int* st = *stack;
    st[top++] = from;
    while (top) {
        int i = st[--top], row = i / w, a = i % w, b = a;
        const uint8_t* line = o + (size_t) row * w;
        uint8_t* done = seen + (size_t) row * w;
        if (a < lo[row] || a > hi[row] || done[a] || !passable(line[a], gaps)) continue;
        while (a > lo[row] && !done[a - 1] && passable(line[a - 1], gaps)) a--;
        while (b < hi[row] && !done[b + 1] && passable(line[b + 1], gaps)) b++;
        memset(done + a, 1, (size_t) (b - a + 1));
        for (int j = row - 1; j <= row + 1; j += 2) {
            if (j < 0 || j >= h) continue;
            const uint8_t* nl = o + (size_t) j * w;
            const uint8_t* nd = seen + (size_t) j * w;
            int k = a - gaps > lo[j] ? a - gaps : lo[j], e = b + gaps < hi[j] ? b + gaps : hi[j];
            for (; k <= e; k++) {
                if (nd[k] || !passable(nl[k], gaps)) continue;
                if (!(st = room(st, top, cap))) {
                    *stack = NULL;
                    return 0;
                }
                st[top++] = j * w + k;
                while (k < e && !nd[k + 1] && passable(nl[k + 1], gaps)) k++; // the run's one seed
            }
        }
    }
    *stack = st;
    return 1;
}

/* Edges.cut: out (w×h, a byte a px) gets the likeness of src's px — premultiplied RGBA of the raster px from (x0, y0) — to the one under (x, y) over the px of the
circle (x, y), r that Dab.visit holds, joined to it through px with some, 4-connected; the rest 0; likeness by tolerance, limit (alike_table). Then holes —
8-connected px held less than fully, off the circle's edge, at most `hole` of them (specks, a dotted line inside an area) — are held fully: a wall crossing the
dab touches its edge. Each px' likeness is found once; the joined px and the gaps reaching the edge by scanline floods, only the rest — enclosed — px by px */
JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Kernels_edges(JNIEnv* env, jobject thiz, jbyteArray src, jint x0, jint y0, jint w, jint h, jfloat x, jfloat y,
    jfloat r, jint tolerance, jint limit, jint hole, jbyteArray out) {
    size_t n = w > 0 && h > 0 ? (size_t) w * (size_t) h : 0, cap = 1024;
    const char* err = !n || n > INT_MAX || (size_t) (*env)->GetArrayLength(env, src) / 4 < n || (size_t) (*env)->GetArrayLength(env, out) < n ? "Bad edges" : NULL;
    int* lo = !err ? malloc(2 * (size_t) h * sizeof(int)) : NULL;
    int* stack = lo ? malloc(cap * sizeof(int)) : NULL;
    uint8_t* seen = stack ? calloc(n, 1) : NULL;
    if (!err && !seen) err = "Out of memory";
    uint8_t* s = !err ? HOLD(src) : NULL;
    uint8_t* o = s ? HOLD(out) : NULL;
    if (o) {
        int *hi = lo + h, t[4];
        float r2 = r * r;
        memset(o, 0, n);
        for (int j = 0; j < h; j++) {
            if (!span(x, y, r2, (float) (y0 + j) + 0.5f, &lo[j], &hi[j])) {
                lo[j] = 1;
                hi[j] = 0;
                continue;
            }
            lo[j] = lo[j] - x0 > 0 ? lo[j] - x0 : 0;
            hi[j] = hi[j] - x0 < w - 1 ? hi[j] - x0 : w - 1;
        }
        int cx = (int) floorf(x) - x0, cy = (int) floorf(y) - y0;
        cx = cx < 0 ? 0 : cx < w ? cx : w - 1;
        cy = cy < 0 ? 0 : cy < h ? cy : h - 1;
        straight(s + 4 * ((size_t) cy * w + cx), t);
        uint8_t like[256];
        alike_table(like, tolerance, limit);
        for (int j = 0; j < h; j++) {
            const uint8_t* p = s + 4 * (size_t) j * w;
            uint8_t* line = o + (size_t) j * w;
            for (int c = lo[j]; c <= hi[j]; c++) line[c] = (uint8_t) alike(p + 4 * c, t, like);
        }
        if (!flood(o, seen, lo, hi, w, h, cy * w + cx, 0, &stack, &cap)) err = "Out of memory";
        for (int j = 0; j < h; j++) {
            uint8_t* line = o + (size_t) j * w;
            const uint8_t* done = seen + (size_t) j * w;
            for (int c = lo[j]; c <= hi[j]; c++) if (!done[c]) line[c] = 0;
        }
        if (hole > 0 && !err) {
            memset(seen, 0, n);
            for (int j = 0; j < h && !err; j++) {
                int a = lo[j] + 1, b = hi[j] - 1; // the px off the edge: no 4-neighbor past it
                for (int q = j - 1; q <= j + 1; q += 2) {
                    if (q < 0 || q >= h || lo[q] > hi[q]) {
                        a = hi[j] + 1;
                        break;
                    }
                    if (lo[q] > a) a = lo[q];
                    if (hi[q] < b) b = hi[q];
                }
                for (int c = lo[j]; c <= hi[j] && !err; c++) {
                    if (c >= a && c <= b) {
                        c = b;
                        continue;
                    }
                    size_t k = (size_t) j * w + c;
                    if (o[k] < 255 && !seen[k] && !flood(o, seen, lo, hi, w, h, (int) k, 1, &stack, &cap)) err = "Out of memory";
                }
            }
            for (int j = 0; j < h && !err; j++)
                for (int c = lo[j]; c <= hi[j] && !err; c++) {
                    size_t i0 = (size_t) j * w + c, head = 0, top = 0;
                    if (o[i0] == 255 || seen[i0]) continue;
                    seen[i0] = 1;
                    stack[top++] = (int) i0;
                    while (head < top && !err) { // the stack as a queue: it keeps the hole's px
                        int i = stack[head++], row = i / w, col = i % w;
                        for (int nr = row - 1; nr <= row + 1 && !err; nr++) {
                            if (nr < 0 || nr >= h) continue;
                            for (int nc = col - 1; nc <= col + 1; nc++) {
                                if (nc < lo[nr] || nc > hi[nr]) continue;
                                size_t k = (size_t) nr * w + nc;
                                if (o[k] == 255 || seen[k]) continue;
                                seen[k] = 1;
                                if (!(stack = room(stack, top, &cap))) {
                                    err = "Out of memory";
                                    break;
                                }
                                stack[top++] = (int) k;
                            }
                        }
                    }
                    if (!err && top <= (size_t) hole)
                        for (size_t q = 0; q < top; q++) o[stack[q]] = 255;
                }
        }
    }
    FREE(out, o, 0);
    FREE(src, s, JNI_ABORT);
    free(seen);
    free(stack);
    free(lo);
    if (err) unlock_px(env, NULL, NULL, NULL, NULL, err);
}
