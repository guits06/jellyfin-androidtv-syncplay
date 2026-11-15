#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>

#define LOG_TAG "CineFast"

static inline uint32_t clamp_u8(int32_t v) {
    if (v < 0) return 0u;
    if (v > 255) return 255u;
    return (uint32_t)v;
}

static inline void rgb_to_ambient_pair(
    uint32_t r_avg,
    uint32_t g_avg,
    uint32_t b_avg,
    uint32_t r_vib,
    uint32_t g_vib,
    uint32_t b_vib,
    int32_t* out_colors
) {
    // Base canvas tint: deeply darkened (~22%) towards deep obsidian #09090F
    int32_t base_r = 9 + ((int32_t)(r_avg * 22u) >> 8);
    int32_t base_g = 9 + ((int32_t)(g_avg * 22u) >> 8);
    int32_t base_b = 15 + ((int32_t)(b_avg * 28u) >> 8);

    // Vibrant ambient accent (~48% intensity with minimum legibility)
    int32_t acc_r = 18 + ((int32_t)(r_vib * 128u) >> 8);
    int32_t acc_g = 18 + ((int32_t)(g_vib * 128u) >> 8);
    int32_t acc_b = 26 + ((int32_t)(b_vib * 138u) >> 8);

    out_colors[0] = (int32_t)(0xFF000000u | (clamp_u8(base_r) << 16) | (clamp_u8(base_g) << 8) | clamp_u8(base_b));
    out_colors[1] = (int32_t)(0xFF000000u | (clamp_u8(acc_r) << 16) | (clamp_u8(acc_g) << 8) | clamp_u8(acc_b));
}

JNIEXPORT jintArray JNICALL
Java_org_jellyfin_androidtv_util_CineFastNative_nativeExtractAmbientColors(
    JNIEnv* env,
    jclass clazz,
    jobject bitmap
) {
    (void)clazz;
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) return NULL;

    int32_t defaults[2] = { (int32_t)0xFF12121B, (int32_t)0xFF281218 };

    if (bitmap == NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, defaults);
        return result;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, defaults);
        return result;
    }

    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, defaults);
        return result;
    }

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    uint32_t step_x = w > 48u ? (w / 48u) : 1u;
    uint32_t step_y = h > 48u ? (h / 48u) : 1u;

    uint64_t sum_r = 0, sum_g = 0, sum_b = 0, count = 0;
    uint64_t vib_r = 0, vib_g = 0, vib_b = 0, vib_weight_sum = 0;

    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        for (uint32_t y = 0; y < h; y += step_y) {
            const uint16_t* row = (const uint16_t*)((const uint8_t*)pixels + (size_t)y * info.stride);
            for (uint32_t x = 0; x < w; x += step_x) {
                uint16_t px = row[x];
                uint32_t r = ((px >> 11) & 0x1Fu) * 255u / 31u;
                uint32_t g = ((px >> 5) & 0x3Fu) * 255u / 63u;
                uint32_t b = (px & 0x1Fu) * 255u / 31u;

                sum_r += r; sum_g += g; sum_b += b;
                ++count;

                uint32_t max_c = r > g ? (r > b ? r : b) : (g > b ? g : b);
                uint32_t min_c = r < g ? (r < b ? r : b) : (g < b ? g : b);
                uint32_t chroma = max_c - min_c;
                if (max_c > 36u && max_c < 240u && chroma > 18u) {
                    uint32_t weight = 1u + (chroma >> 4);
                    vib_r += (uint64_t)r * weight;
                    vib_g += (uint64_t)g * weight;
                    vib_b += (uint64_t)b * weight;
                    vib_weight_sum += weight;
                }
            }
        }
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        for (uint32_t y = 0; y < h; y += step_y) {
            const uint8_t* row = (const uint8_t*)pixels + (size_t)y * info.stride;
            for (uint32_t x = 0; x < w; x += step_x) {
                const uint8_t* p = row + (x * 4u);
                uint32_t a = p[3];
                if (a < 64u) continue;
                uint32_t r = p[0];
                uint32_t g = p[1];
                uint32_t b = p[2];

                sum_r += r; sum_g += g; sum_b += b;
                ++count;

                uint32_t max_c = r > g ? (r > b ? r : b) : (g > b ? g : b);
                uint32_t min_c = r < g ? (r < b ? r : b) : (g < b ? g : b);
                uint32_t chroma = max_c - min_c;
                if (max_c > 36u && max_c < 240u && chroma > 18u) {
                    uint32_t weight = 1u + (chroma >> 4);
                    vib_r += (uint64_t)r * weight;
                    vib_g += (uint64_t)g * weight;
                    vib_b += (uint64_t)b * weight;
                    vib_weight_sum += weight;
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    if (count > 0u) {
        uint32_t r_avg = (uint32_t)(sum_r / count);
        uint32_t g_avg = (uint32_t)(sum_g / count);
        uint32_t b_avg = (uint32_t)(sum_b / count);
        uint32_t r_v = vib_weight_sum > 0u ? (uint32_t)(vib_r / vib_weight_sum) : r_avg;
        uint32_t g_v = vib_weight_sum > 0u ? (uint32_t)(vib_g / vib_weight_sum) : g_avg;
        uint32_t b_v = vib_weight_sum > 0u ? (uint32_t)(vib_b / vib_weight_sum) : b_avg;
        rgb_to_ambient_pair(r_avg, g_avg, b_avg, r_v, g_v, b_v, defaults);
    }

    (*env)->SetIntArrayRegion(env, result, 0, 2, defaults);
    return result;
}

// Fast in-place separable box blur for small ARGB_8888 ambient bitmaps (e.g. <= 128x72)
JNIEXPORT jboolean JNICALL
Java_org_jellyfin_androidtv_util_CineFastNative_nativeBlurArgb8888InPlace(
    JNIEnv* env,
    jclass clazz,
    jobject bitmap,
    jint radius
) {
    (void)clazz;
    if (bitmap == NULL || radius < 1) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 2u || info.height < 2u) return JNI_FALSE;
    // Bound max size cleanly so stack/scratch buffer remains tiny and deterministic
    if (info.width > 256u || info.height > 256u) return JNI_FALSE;

    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) return JNI_FALSE;

    const int w = (int)info.width;
    const int h = (int)info.height;
    const int rad = radius > 16 ? 16 : (int)radius;
    const int div = rad * 2 + 1;

    uint32_t* tmp = (uint32_t*)malloc((size_t)w * (size_t)h * sizeof(uint32_t));
    if (tmp == NULL) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    // Horizontal pass -> tmp
    for (int y = 0; y < h; ++y) {
        const uint32_t* src_row = (const uint32_t*)((const uint8_t*)pixels + (size_t)y * info.stride);
        uint32_t* dst_row = tmp + (size_t)y * (size_t)w;
        for (int x = 0; x < w; ++x) {
            uint32_t r = 0, g = 0, b = 0, a = 0;
            for (int k = -rad; k <= rad; ++k) {
                int sx = x + k;
                if (sx < 0) sx = 0;
                else if (sx >= w) sx = w - 1;
                uint32_t px = src_row[sx];
                r += px & 0xFFu;
                g += (px >> 8) & 0xFFu;
                b += (px >> 16) & 0xFFu;
                a += (px >> 24) & 0xFFu;
            }
            dst_row[x] = (r / (uint32_t)div)
                | ((g / (uint32_t)div) << 8)
                | ((b / (uint32_t)div) << 16)
                | ((a / (uint32_t)div) << 24);
        }
    }

    // Vertical pass -> bitmap
    for (int y = 0; y < h; ++y) {
        uint32_t* dst_row = (uint32_t*)((uint8_t*)pixels + (size_t)y * info.stride);
        for (int x = 0; x < w; ++x) {
            uint32_t r = 0, g = 0, b = 0, a = 0;
            for (int k = -rad; k <= rad; ++k) {
                int sy = y + k;
                if (sy < 0) sy = 0;
                else if (sy >= h) sy = h - 1;
                uint32_t px = tmp[(size_t)sy * (size_t)w + (size_t)x];
                r += px & 0xFFu;
                g += (px >> 8) & 0xFFu;
                b += (px >> 16) & 0xFFu;
                a += (px >> 24) & 0xFFu;
            }
            dst_row[x] = (r / (uint32_t)div)
                | ((g / (uint32_t)div) << 8)
                | ((b / (uint32_t)div) << 16)
                | ((a / (uint32_t)div) << 24);
        }
    }

    free(tmp);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
