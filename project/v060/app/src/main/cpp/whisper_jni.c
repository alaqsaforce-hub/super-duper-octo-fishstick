// JNI bridge for com.voicedock.ai.voice.WhisperNative. Text is returned as raw UTF-8 bytes (decoded in Kotlin),
// because whisper can emit partial UTF-8 sequences that would crash NewStringUTF.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "VoiceDockWhisper"

static void quiet_log(enum ggml_log_level level, const char *text, void *user) {
    (void) user;
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
}

JNIEXPORT jlong JNICALL
Java_com_voicedock_ai_voice_WhisperNative_nativeInit(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    whisper_log_set(quiet_log, NULL);
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_voicedock_ai_voice_WhisperNative_nativeFree(JNIEnv *env, jclass clazz, jlong ctx) {
    (void) env; (void) clazz;
    if (ctx) whisper_free((struct whisper_context *) (intptr_t) ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_voicedock_ai_voice_WhisperNative_nativeTranscribe(JNIEnv *env, jclass clazz, jlong ctxp, jfloatArray pcm,
                                                          jstring lang, jstring prompt, jint threads, jint audio_ctx) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ctxp;
    if (!ctx) return NULL;
    const char *l = lang ? (*env)->GetStringUTFChars(env, lang, NULL) : NULL;
    const char *pr = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;
    jsize n = (*env)->GetArrayLength(env, pcm);
    jfloat *data = (*env)->GetFloatArrayElements(env, pcm, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false; p.print_progress = false; p.print_timestamps = false; p.print_special = false;
    p.translate = false;
    p.language = (l && l[0]) ? l : "auto";
    p.detect_language = false;
    p.n_threads = threads > 0 ? threads : 2;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.initial_prompt = (pr && pr[0]) ? pr : NULL;
    // Smaller encoder window for short clips (whisper otherwise always encodes 30 s). 0 = default (1500).
    if (audio_ctx > 0) p.audio_ctx = audio_ctx;

    int rc = whisper_full(ctx, p, data, n);
    (*env)->ReleaseFloatArrayElements(env, pcm, data, JNI_ABORT);

    jbyteArray out = NULL;
    if (rc == 0) {
        int segs = whisper_full_n_segments(ctx);
        size_t cap = 256, len = 0;
        char *buf = malloc(cap);
        if (buf) {
            for (int i = 0; i < segs; i++) {
                const char *t = whisper_full_get_segment_text(ctx, i);
                size_t tl = t ? strlen(t) : 0;
                if (len + tl + 1 > cap) {
                    while (len + tl + 1 > cap) cap *= 2;
                    char *nb = realloc(buf, cap);
                    if (!nb) break;
                    buf = nb;
                }
                if (tl) { memcpy(buf + len, t, tl); len += tl; }
            }
            out = (*env)->NewByteArray(env, (jsize) len);
            if (out && len) (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) buf);
            free(buf);
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full failed: %d", rc);
    }
    if (l) (*env)->ReleaseStringUTFChars(env, lang, l);
    if (pr) (*env)->ReleaseStringUTFChars(env, prompt, pr);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_voicedock_ai_voice_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
