// Encoders for ImageExport.kt: the platform ones expose only quality (JPEG is always 4:2:0, WebP runs at medium effort)
#include <android/bitmap.h>
#include <jni.h>
#include <setjmp.h>
#include <stdio.h>
#include <jpeglib.h>
#include <webp/encode.h>
#include <webp/mux.h>

/* [mode]: JPEG — 4:4:4 chroma, WebP — lossless. Returns NULL on success, otherwise the error; [msg] is a buffer for it */
typedef const char* (*Encoder)(JNIEnv* env, jobject bitmap, int quality, int mode, const uint8_t* icc, unsigned icc_size, FILE* f, char* msg);

static int lock_pixels(JNIEnv* env, jobject bitmap, AndroidBitmapInfo* info, void** px) {
    return AndroidBitmap_getInfo(env, bitmap, info) == ANDROID_BITMAP_RESULT_SUCCESS && AndroidBitmap_lockPixels(env, bitmap, px) == ANDROID_BITMAP_RESULT_SUCCESS;
}

struct jpeg_fail {
    struct jpeg_error_mgr mgr;
    jmp_buf jump;
};

/* libjpeg's default handler exits the process */
static void jpeg_exit(j_common_ptr c) { longjmp(((struct jpeg_fail*) c->err)->jump, 1); }

/* Progressive with optimized Huffman tables: a smaller file, the same pixels; costs a whole-image coefficient buffer (3–6 B/px).
The canvas is opaque: layers over white */
static const char* encode_jpeg(JNIEnv* env, jobject bitmap, int quality, int full_chroma, const uint8_t* icc, unsigned icc_size, FILE* f, char* msg) {
    AndroidBitmapInfo info;
    void* px;
    if (!lock_pixels(env, bitmap, &info, &px)) return "Can't read the bitmap";
    struct jpeg_compress_struct c;
    struct jpeg_fail fail;
    c.err = jpeg_std_error(&fail.mgr);
    fail.mgr.error_exit = jpeg_exit;
    const char* err = NULL;
    if (setjmp(fail.jump)) {
        fail.mgr.format_message((j_common_ptr) &c, msg);
        err = msg;
    } else {
        jpeg_create_compress(&c);
        jpeg_stdio_dest(&c, f);
        c.image_width = info.width;
        c.image_height = info.height;
        c.input_components = 4;
        c.in_color_space = JCS_EXT_RGBX;
        jpeg_set_defaults(&c);
        jpeg_set_quality(&c, quality, TRUE);
        if (full_chroma) c.comp_info[0].h_samp_factor = c.comp_info[0].v_samp_factor = 1; // luma at chroma's resolution: 4:4:4 instead of 4:2:0
        jpeg_simple_progression(&c); // implies optimized Huffman tables
        jpeg_start_compress(&c, TRUE);
        jpeg_write_icc_profile(&c, icc, icc_size);
        for (JSAMPROW row = px; c.next_scanline < c.image_height; row += info.stride) jpeg_write_scanlines(&c, &row, 1);
        jpeg_finish_compress(&c);
    }
    jpeg_destroy_compress(&c);
    AndroidBitmap_unlockPixels(env, bitmap);
    return err;
}

static const char* const WEBP_ERRORS[VP8_ENC_ERROR_LAST] = {
    [VP8_ENC_ERROR_OUT_OF_MEMORY] = "WebP: out of memory",
    [VP8_ENC_ERROR_BITSTREAM_OUT_OF_MEMORY] = "WebP: out of memory",
    [VP8_ENC_ERROR_PARTITION0_OVERFLOW] = "WebP: partition 0 is over 512 KB",
    [VP8_ENC_ERROR_PARTITION_OVERFLOW] = "WebP: partition is over 16 MB",
    [VP8_ENC_ERROR_FILE_TOO_BIG] = "WebP: file is over 4 GB",
};

/* Max effort. The pixels are copied, then [bitmap] is recycled: its RAM goes to the encoder */
static const char* encode_webp(JNIEnv* env, jobject bitmap, int quality, int lossless, const uint8_t* icc, unsigned icc_size, FILE* f, char* msg) {
    AndroidBitmapInfo info;
    void* px;
    WebPConfig config;
    WebPPicture pic;
    if (!lock_pixels(env, bitmap, &info, &px)) return "Can't read the bitmap";
    int ok = WebPPictureInit(&pic) && WebPConfigPreset(&config, WEBP_PRESET_DEFAULT, quality);
    if (lossless) ok = ok && WebPConfigLosslessPreset(&config, 9); // method 6; "quality" is effort here: 100
    else {
        config.method = 6;
        config.use_sharp_yuv = 1; // sharper color edges in 4:2:0
        config.autofilter = 1;
        config.alpha_filtering = 2;
    }
    pic.use_argb = 1;
    pic.width = (int) info.width;
    pic.height = (int) info.height;
    ok = ok && WebPPictureAlloc(&pic);
    for (uint32_t y = 0; ok && y < info.height; y++) {
        const uint8_t* s = (const uint8_t*) px + (size_t) y * info.stride;
        uint32_t* d = pic.argb + (size_t) y * pic.argb_stride;
        // Android keeps color premultiplied by alpha, WebP stores it straight
        for (uint32_t x = 0; x < info.width; x++, s += 4) {
            uint32_t a = s[3], h = a / 2;
            d[x] = a ? a << 24 | (s[0] * 255 + h) / a << 16 | (s[1] * 255 + h) / a << 8 | (s[2] * 255 + h) / a : 0;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    (*env)->CallVoidMethod(env, bitmap, (*env)->GetMethodID(env, (*env)->GetObjectClass(env, bitmap), "recycle", "()V"));
    WebPMemoryWriter out;
    WebPMemoryWriterInit(&out);
    pic.writer = WebPMemoryWrite;
    pic.custom_ptr = &out;
    ok = ok && WebPEncode(&config, &pic);
    const char* err = ok ? NULL : WEBP_ERRORS[pic.error_code] ? WEBP_ERRORS[pic.error_code] : "WebP encode failed";
    WebPPictureFree(&pic);
    WebPData file;
    WebPDataInit(&file);
    if (!err) {
        // An ICC profile needs the extended container: the mux rebuilds the file around the encoded image
        WebPData image = {out.mem, out.size}, profile = {icc, icc_size};
        WebPMux* mux = WebPMuxNew();
        if (!mux || WebPMuxSetImage(mux, &image, 0) != WEBP_MUX_OK || WebPMuxSetChunk(mux, "ICCP", &profile, 0) != WEBP_MUX_OK
            || WebPMuxAssemble(mux, &file) != WEBP_MUX_OK) err = "WebP: can't add the color profile";
        WebPMuxDelete(mux);
    }
    WebPMemoryWriterClear(&out);
    if (!err && fwrite(file.bytes, 1, file.size, f) != file.size) err = "Can't write the file";
    WebPDataClear(&file);
    return err;
}

static void run(JNIEnv* env, Encoder encode, jobject bitmap, jint quality, jboolean mode, jbyteArray icc, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE* f = fopen(p, "wb");
    (*env)->ReleaseStringUTFChars(env, path, p);
    char msg[JMSG_LENGTH_MAX];
    const char* err = "Can't create the file";
    if (f) {
        setvbuf(f, NULL, _IOFBF, 1 << 16); // as IO_BUFFER: fewer system calls on shared storage (FUSE)
        jbyte* profile = (*env)->GetByteArrayElements(env, icc, NULL);
        err = encode(env, bitmap, quality, mode, (const uint8_t*) profile, (unsigned) (*env)->GetArrayLength(env, icc), f, msg);
        (*env)->ReleaseByteArrayElements(env, icc, profile, JNI_ABORT);
        if (fclose(f) && !err) err = "Can't write the file";
    }
    if (err) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), err);
}

JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Encoders_jpeg(JNIEnv* env, jobject thiz, jobject bitmap, jint quality, jboolean full_chroma, jbyteArray icc, jstring path) {
    run(env, encode_jpeg, bitmap, quality, full_chroma, icc, path);
}

JNIEXPORT void JNICALL Java_com_perkus_imagoedit_data_Encoders_webp(JNIEnv* env, jobject thiz, jobject bitmap, jint quality, jboolean lossless, jbyteArray icc, jstring path) {
    run(env, encode_webp, bitmap, quality, lossless, icc, path);
}
