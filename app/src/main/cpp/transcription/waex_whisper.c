// JNI bridge for com.waenhancer.core.transcription.local.WhisperNative.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "ggml-backend.h"
#include "whisper.h"

/**
 * Loads one ggml CPU backend library by file name (resolved from the app's own libraries).
 * ggml refuses a variant whose instructions this CPU lacks, so callers try best-first.
 */
JNIEXPORT jboolean JNICALL
Java_com_waenhancer_core_transcription_local_WhisperNative_loadCpuBackend(JNIEnv *env, jclass cls, jstring name) {
    (void) cls;
    const char *library = (*env)->GetStringUTFChars(env, name, NULL);
    if (library == NULL) return JNI_FALSE;
    ggml_backend_reg_t reg = ggml_backend_load(library);
    (*env)->ReleaseStringUTFChars(env, name, library);
    return reg != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_waenhancer_core_transcription_local_WhisperNative_init(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *model = (*env)->GetStringUTFChars(env, path, NULL);
    if (model == NULL) return 0;
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(model, params);
    (*env)->ReleaseStringUTFChars(env, path, model);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_waenhancer_core_transcription_local_WhisperNative_free(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle != 0) whisper_free((struct whisper_context *) (intptr_t) handle);
}

/**
 * Transcribes 16 kHz mono float samples. Returns the text as UTF-8 bytes (NewStringUTF only
 * accepts modified UTF-8 and would reject emoji), or null when inference failed.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_waenhancer_core_transcription_local_WhisperNative_transcribe(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray samples, jstring language, jint threads) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (ctx == NULL || samples == NULL) return NULL;

    const char *lang = language != NULL ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.no_timestamps = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.language = (lang != NULL && lang[0] != '\0') ? lang : "auto";

    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int rc = data != NULL ? whisper_full(ctx, params, data, count) : -1;
    if (data != NULL) (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    if (lang != NULL) (*env)->ReleaseStringUTFChars(env, language, lang);
    if (rc != 0) return NULL;

    size_t length = 0;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != NULL) length += strlen(text);
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) length);
    if (out == NULL) return NULL;
    size_t offset = 0;
    for (int i = 0; i < segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == NULL) continue;
        size_t n = strlen(text);
        (*env)->SetByteArrayRegion(env, out, (jsize) offset, (jsize) n, (const jbyte *) text);
        offset += n;
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_waenhancer_core_transcription_local_WhisperNative_systemInfo(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
