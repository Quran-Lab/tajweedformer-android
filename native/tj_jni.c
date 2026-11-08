/* JNI bridge over zf_tajweed.h (the TajweedFormer V4 engine, API version 2), for
 * ai.quranlab.tajweedformer.engine.TajweedJni.
 *
 * Handles are the C API's own pointers (model, stream, config, result) passed as longs. Heads come
 * back to Kotlin as copies, a frame range at a time, in TajweedHead order; the C API keeps every
 * head of the take, so Kotlin asks for the frames it has not seen after each push. Results come
 * back as flat double arrays (one fixed-size record per site, madd vowel or letter) plus the
 * engine's JSON.
 *
 * Not thread-safe per handle, like the C API: Kotlin calls a stream, config and result from one
 * thread at a time. A model is immutable after open.
 *
 * Errors: a model that cannot be opened throws RecognizerException.ModelLoadFailed(message); any
 * other failure RecognizerException.EngineFailed(message); a closed handle IllegalStateException. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "zf_tajweed.h"

#define HEADS 12
#define SITE_FIELDS 13
#define MADD_FIELDS 13
#define LETTER_FIELDS 9
#define SUMMARY_FIELDS 10

static void throw_class(JNIEnv *env, const char *cls, const char *message) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass c = (*env)->FindClass(env, cls);
  if (c) (*env)->ThrowNew(env, c, message ? message : "");
}

static void throw_load(JNIEnv *env, const char *message) {
  throw_class(env, "ai/quranlab/tajweedformer/RecognizerException$ModelLoadFailed", message);
}

static void throw_engine(JNIEnv *env, const char *message) {
  throw_class(env, "ai/quranlab/tajweedformer/RecognizerException$EngineFailed", message);
}

static void *ptr(JNIEnv *env, jlong p, const char *what) {
  if (!p) throw_class(env, "java/lang/IllegalStateException", what);
  return (void *)(intptr_t)p;
}

/* A Java string as UTF-8, or NULL for null; release with release_utf. */
static const char *utf(JNIEnv *env, jstring s) {
  return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static void release_utf(JNIEnv *env, jstring s, const char *c) {
  if (s && c) (*env)->ReleaseStringUTFChars(env, s, c);
}

/* ------------------------------------------------------------------------------------- names */

JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_apiVersion(JNIEnv *env, jclass cls) {
  (void)env;
  (void)cls;
  return zf_tajweed_api_version();
}

JNIEXPORT jstring JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_version(JNIEnv *env, jclass cls) {
  (void)cls;
  const char *v = zf_tajweed_version();
  return (*env)->NewStringUTF(env, v ? v : "");
}

/* ------------------------------------------------------------------------------------- model */

JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_modelOpen(
    JNIEnv *env, jclass cls, jstring dir, jstring context, jboolean load_engine, jint threads, jint sample_rate,
    jint batch, jint no_pin) {
  (void)cls;
  ZfTajweedOpenOptions o = zf_tajweed_open_default_options();
  o.struct_size = sizeof(o);
  const char *d = utf(env, dir);
  const char *c = utf(env, context);
  if (!d) {
    release_utf(env, context, c);
    throw_load(env, "no model folder");
    return 0;
  }
  o.context = c;
  o.load_engine = load_engine ? 1 : 0;
  o.threads = threads;
  o.sample_rate = sample_rate;
  o.batch = batch;
  /* API 3: struct_size is this header's sizeof (40 bytes on 64-bit); no_pin < 0 keeps the engine's
   * platform default (pinned on Android, unpinned on desktops) */
  if (no_pin >= 0) o.no_pin = no_pin ? 1 : 0;
  const char *err = NULL;
  ZfTajweedModel *m = zf_tajweed_model_open(d, &o, &err);
  release_utf(env, dir, d);
  release_utf(env, context, c);
  if (!m) throw_load(env, err ? err : "zf_tajweed_model_open failed");
  return (jlong)(intptr_t)m;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_modelClose(JNIEnv *env, jclass cls, jlong m) {
  (void)env;
  (void)cls;
  if (m) zf_tajweed_model_close((ZfTajweedModel *)(intptr_t)m);
}

JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_modelChunkMs(JNIEnv *env, jclass cls, jlong m) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  return p ? zf_tajweed_model_chunk_ms(p) : 0;
}

JNIEXPORT jstring JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_modelContext(JNIEnv *env, jclass cls, jlong m) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  if (!p) return NULL;
  const char *c = zf_tajweed_model_context(p);
  return (*env)->NewStringUTF(env, c ? c : "");
}

/* The ayah's expected tokens, or null for an unknown ayah. */
JNIEXPORT jintArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_expectedTokens(
    JNIEnv *env, jclass cls, jlong m, jint surah, jint ayah) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  if (!p) return NULL;
  const int *tokens = NULL;
  const int n = zf_tajweed_expected_tokens(p, surah, ayah, &tokens);
  if (n < 0 || (n > 0 && !tokens)) return NULL;
  jintArray out = (*env)->NewIntArray(env, n);
  if (out && n > 0) (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *)tokens);
  return out;
}

/* The word (0-based) of every expected token of surah:ayah; null for an unknown ayah. */
JNIEXPORT jintArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_expectedWords(
    JNIEnv *env, jclass cls, jlong m, jint surah, jint ayah) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  if (!p) return NULL;
  const int *words = NULL;
  int n_words = 0;
  const int n = zf_tajweed_expected_words(p, surah, ayah, &words, &n_words);
  if (n < 0 || (n > 0 && !words)) return NULL;
  jintArray out = (*env)->NewIntArray(env, n);
  if (out && n > 0) (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *)words);
  return out;
}

/* ------------------------------------------------------------------------------------ stream */

JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamCreate(JNIEnv *env, jclass cls, jlong m) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  if (!p) return 0;
  const char *err = NULL;
  ZfTajweedStream *s = zf_tajweed_stream_create(p, &err);
  if (!s) throw_engine(env, err ? err : "zf_tajweed_stream_create failed");
  return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamDestroy(JNIEnv *env, jclass cls, jlong s) {
  (void)env;
  (void)cls;
  if (s) zf_tajweed_stream_destroy((ZfTajweedStream *)(intptr_t)s);
}

/* Diagnostics: the engine threads taking part in the stream's regions now (zf_stream_threads_active,
 * engine de88490 and later). zf_tajweed.h does not hand out the engine stream; ZfTajweedStream starts
 * with its model and its engine stream (tajweed/src/engine.c), read here for this diagnostic only and
 * compiled against the same engine source. */
typedef struct ZfStream ZfStream;
int zf_stream_threads_active(const ZfStream *s);
struct tj_stream_head {
  void *model;
  ZfStream *zs;
};

JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamThreadsActive(JNIEnv *env, jclass cls, jlong s) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return -1;
  return zf_stream_threads_active(((const struct tj_stream_head *)(const void *)p)->zs);
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamReset(JNIEnv *env, jclass cls, jlong s) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (p) zf_tajweed_stream_reset(p);
}

static void throw_stream(JNIEnv *env, ZfTajweedStream *s, const char *call) {
  const char *e = zf_tajweed_stream_last_error(s);
  throw_engine(env, e && *e ? e : call);
}

#define BLOCK 16000

/* Pushes samples[offset, offset + length) in blocks; returns the chunks run. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamPush(
    JNIEnv *env, jclass cls, jlong s, jfloatArray samples, jint offset, jint length) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return -1;
  float *stage = (float *)malloc(sizeof(float) * BLOCK);
  if (!stage) {
    throw_engine(env, "no memory");
    return -1;
  }
  jint chunks = 0;
  for (jint at = 0; at < length;) {
    const jint n = length - at < BLOCK ? length - at : BLOCK;
    (*env)->GetFloatArrayRegion(env, samples, offset + at, n, stage);
    if ((*env)->ExceptionCheck(env)) break;
    const int r = zf_tajweed_stream_push_audio(p, stage, n);
    if (r < 0) {
      throw_stream(env, p, "zf_tajweed_stream_push_audio failed");
      break;
    }
    chunks += r;
    at += n;
  }
  free(stage);
  return chunks;
}

JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamFinish(JNIEnv *env, jclass cls, jlong s) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return -1;
  const int r = zf_tajweed_stream_finish(p);
  if (r < 0) throw_stream(env, p, "zf_tajweed_stream_finish failed");
  return r;
}

/* The take's 40 ms frame count so far. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamFrames(JNIEnv *env, jclass cls, jlong s) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return -1;
  ZfTajweedHeads h;
  memset(&h, 0, sizeof(h));
  if (zf_tajweed_stream_heads(p, &h) < 0) {
    throw_stream(env, p, "zf_tajweed_stream_heads failed");
    return -1;
  }
  return h.n40;
}

/* The heads' pointers and widths in TajweedHead order (TajweedHeads.kt). */
static void head_table(const ZfTajweedHeads *h, const float *ptrs[HEADS], int width[HEADS], int per40[HEADS]) {
  const float *p[HEADS] = {h->log_probs, h->inter1_log_probs, h->inter4_log_probs, h->id_log_probs,
                           h->timing_log_probs, h->gem, h->attr_tafkheem, h->attr_ghunna,
                           h->attr_qalqalah, h->attr_face, h->boundary, h->conf};
  const int v = h->vocab;
  const int w[HEADS] = {v, v, v, v, v, 2, 3, 5, 3, 4, 1, 1};
  const int r[HEADS] = {1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2};
  for (int i = 0; i < HEADS; i++) {
    ptrs[i] = p[i];
    width[i] = w[i];
    per40[i] = r[i];
  }
}

/* Copies of every head over 40 ms frames [from, to) (the 20 ms heads over [2 from, 2 to)), in
 * TajweedHead order; an element is null when the source has no such head. */
JNIEXPORT jobjectArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamHeads(
    JNIEnv *env, jclass cls, jlong s, jint from, jint to) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return NULL;
  ZfTajweedHeads h;
  memset(&h, 0, sizeof(h));
  if (zf_tajweed_stream_heads(p, &h) < 0) {
    throw_stream(env, p, "zf_tajweed_stream_heads failed");
    return NULL;
  }
  if (from < 0 || to < from || to > h.n40 || h.n20 != 2 * h.n40) {
    throw_class(env, "java/lang/IndexOutOfBoundsException", "head frames out of range");
    return NULL;
  }
  jclass fa = (*env)->FindClass(env, "[F");
  if (!fa) return NULL;
  jobjectArray out = (*env)->NewObjectArray(env, HEADS, fa, NULL);
  if (!out) return NULL;
  const float *ptrs[HEADS];
  int width[HEADS], per40[HEADS];
  head_table(&h, ptrs, width, per40);
  for (int i = 0; i < HEADS; i++) {
    if (!ptrs[i]) continue;
    const jsize lo = (jsize)from * per40[i] * width[i];
    const jsize n = (jsize)(to - from) * per40[i] * width[i];
    jfloatArray a = (*env)->NewFloatArray(env, n);
    if (!a) return NULL;
    if (n > 0) (*env)->SetFloatArrayRegion(env, a, 0, n, ptrs[i] + lo);
    (*env)->SetObjectArrayElement(env, out, i, a);
    (*env)->DeleteLocalRef(env, a);
  }
  return out;
}

/* ------------------------------------------------------------------------------------ config */

JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configCreate(JNIEnv *env, jclass cls, jlong m) {
  (void)cls;
  ZfTajweedModel *p = ptr(env, m, "the model is closed");
  if (!p) return 0;
  ZfTajweedConfig *c = zf_tajweed_config_create(p);
  if (!c) throw_engine(env, "zf_tajweed_config_create failed");
  return (jlong)(intptr_t)c;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configDestroy(JNIEnv *env, jclass cls, jlong c) {
  (void)env;
  (void)cls;
  if (c) zf_tajweed_config_destroy((ZfTajweedConfig *)(intptr_t)c);
}

/* The portal's block (or a /v1/config document); returns the fields dropped, -1 when not JSON. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configSetRemote(
    JNIEnv *env, jclass cls, jlong c, jbyteArray json) {
  (void)cls;
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (!p) return -1;
  if (!json) return zf_tajweed_config_set_remote(p, NULL, 0);
  const jsize n = (*env)->GetArrayLength(env, json);
  char *buf = (char *)malloc((size_t)n + 1);
  if (!buf) {
    throw_engine(env, "no memory");
    return -1;
  }
  (*env)->GetByteArrayRegion(env, json, 0, n, (jbyte *)buf);
  buf[n] = 0;
  const int r = zf_tajweed_config_set_remote(p, buf, (size_t)n);
  free(buf);
  return r;
}

/* App setters. kind: 0 preset (a = preset or null), 1 class preset (a = class, or "madd",
 * "qalqalah", "gemination" in API 2; b = preset or null), 2 class threshold (a = class, t), 3 clear
 * class (a), 4 check (a = check, on = 1, 0 or -1), 5 context (a or null), 6 backend (API 2: a =
 * "int8", "fp32" or null). Returns the C API's 0 or -1. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configSetApp(
    JNIEnv *env, jclass cls, jlong c, jint kind, jstring a, jstring b, jdouble t, jint on) {
  (void)cls;
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (!p) return -1;
  const char *sa = utf(env, a);
  const char *sb = utf(env, b);
  int r = -1;
  switch (kind) {
    case 0: r = zf_tajweed_config_set_preset(p, sa); break;
    case 1: r = zf_tajweed_config_set_class_preset(p, sa, sb); break;
    case 2: r = zf_tajweed_config_set_class_threshold(p, sa, t); break;
    case 3: r = zf_tajweed_config_clear_class(p, sa); break;
    case 4: r = zf_tajweed_config_set_check(p, sa, on); break;
    case 5: r = zf_tajweed_config_set_context(p, sa); break;
    case 6: r = zf_tajweed_config_set_backend(p, sa); break;
    default: break;
  }
  release_utf(env, a, sa);
  release_utf(env, b, sb);
  return r;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configClearApp(JNIEnv *env, jclass cls, jlong c) {
  (void)cls;
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (p) zf_tajweed_config_clear_app(p);
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configSetDebugBuild(
    JNIEnv *env, jclass cls, jlong c, jboolean debug) {
  (void)cls;
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (p) zf_tajweed_config_set_debug_build(p, debug ? 1 : 0);
}

JNIEXPORT jstring JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configJson(JNIEnv *env, jclass cls, jlong c) {
  (void)cls;
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (!p) return NULL;
  const char *j = zf_tajweed_config_json(p);
  return j ? (*env)->NewStringUTF(env, j) : NULL;
}

/* ----------------------------------------------------------------------------------- scoring */

/* Scores heads given as float arrays in TajweedHead order (null = absent) over n40 frames. */
JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_score(
    JNIEnv *env, jclass cls, jlong m, jlong c, jobjectArray heads, jint n40, jint surah, jint ayah) {
  (void)cls;
  ZfTajweedModel *pm = ptr(env, m, "the model is closed");
  if (!pm) return 0;
  ZfTajweedConfig *pc = ptr(env, c, "the config is closed");
  if (!pc) return 0;
  if ((*env)->GetArrayLength(env, heads) != HEADS) {
    throw_class(env, "java/lang/IllegalArgumentException", "12 heads expected");
    return 0;
  }
  jfloatArray arr[HEADS];
  float *data[HEADS];
  for (int i = 0; i < HEADS; i++) {
    arr[i] = (jfloatArray)(*env)->GetObjectArrayElement(env, heads, i);
    data[i] = arr[i] ? (*env)->GetFloatArrayElements(env, arr[i], NULL) : NULL;
  }
  ZfTajweedHeads h;
  memset(&h, 0, sizeof(h));
  h.n40 = n40;
  h.n20 = 2 * n40;
  h.vocab = ZF_TAJWEED_VOCAB;
  h.log_probs = data[0];
  h.inter1_log_probs = data[1];
  h.inter4_log_probs = data[2];
  h.id_log_probs = data[3];
  h.timing_log_probs = data[4];
  h.gem = data[5];
  h.attr_tafkheem = data[6];
  h.attr_ghunna = data[7];
  h.attr_qalqalah = data[8];
  h.attr_face = data[9];
  h.boundary = data[10];
  h.conf = data[11];
  const char *err = NULL;
  ZfTajweedResult *r = zf_tajweed_score(pm, pc, &h, surah, ayah, &err);
  for (int i = 0; i < HEADS; i++) {
    if (arr[i]) {
      (*env)->ReleaseFloatArrayElements(env, arr[i], data[i], JNI_ABORT);
      (*env)->DeleteLocalRef(env, arr[i]);
    }
  }
  if (!r) throw_engine(env, err ? err : "zf_tajweed_score failed");
  return (jlong)(intptr_t)r;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_resultFree(JNIEnv *env, jclass cls, jlong r) {
  (void)env;
  (void)cls;
  if (r) zf_tajweed_result_free((ZfTajweedResult *)(intptr_t)r);
}

JNIEXPORT jstring JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_resultJson(JNIEnv *env, jclass cls, jlong r) {
  (void)cls;
  ZfTajweedResult *p = ptr(env, r, "the result is freed");
  if (!p) return NULL;
  const char *j = zf_tajweed_result_json(p);
  return j ? (*env)->NewStringUTF(env, j) : NULL;
}

static jdoubleArray doubles(JNIEnv *env, const double *v, int n) {
  jdoubleArray a = (*env)->NewDoubleArray(env, n);
  if (a && n > 0) (*env)->SetDoubleArrayRegion(env, a, 0, n, v);
  return a;
}

/* kind 0: sites, SITE_FIELDS per site (cls, status, pos, word, expected, alt, llr_final,
 * llr_inter1, llr, threshold, flagged, frame_lo, frame_hi); 1: madd, MADD_FIELDS per vowel (pos,
 * word, token, madd_class, status, ratio, count, display_count, allowed_lo, allowed_hi, flagged,
 * frame_lo, frame_hi); 2 qalqalah and 3 gemination, LETTER_FIELDS per letter (pos, word, token,
 * status, score, threshold, flagged, frame_lo, frame_hi); 4: the summary, SUMMARY_FIELDS (n_sites,
 * n_flagged, n_madd, n_madd_flagged, n_qalqalah, n_qalqalah_flagged, n_gemination,
 * n_gemination_flagged, short_vowel_frames, madd_unit_ok). */
JNIEXPORT jdoubleArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_resultRecords(
    JNIEnv *env, jclass cls, jlong r, jint kind) {
  (void)cls;
  ZfTajweedResult *p = ptr(env, r, "the result is freed");
  if (!p) return NULL;
  double *v = NULL;
  int n = 0;
  if (kind == 0) {
    const ZfTajweedSite *s = NULL;
    const int k = zf_tajweed_result_sites(p, &s);
    if (k < 0) goto fail;
    v = (double *)malloc(sizeof(double) * (size_t)(k * SITE_FIELDS + 1));
    if (!v) goto fail;
    for (int i = 0; i < k; i++) {
      const ZfTajweedSite *x = &s[i];
      const double f[SITE_FIELDS] = {x->cls, x->status, x->pos, x->word, x->expected, x->alt, x->llr_final,
                                     x->llr_inter1, x->llr, x->threshold, x->flagged, x->frame_lo, x->frame_hi};
      memcpy(v + i * SITE_FIELDS, f, sizeof(f));
    }
    n = k * SITE_FIELDS;
  } else if (kind == 1) {
    const ZfTajweedMadd *s = NULL;
    const int k = zf_tajweed_result_madd(p, &s);
    if (k < 0) goto fail;
    v = (double *)malloc(sizeof(double) * (size_t)(k * MADD_FIELDS + 1));
    if (!v) goto fail;
    for (int i = 0; i < k; i++) {
      const ZfTajweedMadd *x = &s[i];
      const double f[MADD_FIELDS] = {x->pos, x->word, x->token, x->madd_class, x->status, x->ratio, x->count,
                                     x->display_count, x->allowed_lo, x->allowed_hi, x->flagged, x->frame_lo,
                                     x->frame_hi};
      memcpy(v + i * MADD_FIELDS, f, sizeof(f));
    }
    n = k * MADD_FIELDS;
  } else if (kind == 2 || kind == 3) {
    const ZfTajweedLetter *s = NULL;
    const int k = kind == 2 ? zf_tajweed_result_qalqalah(p, &s) : zf_tajweed_result_gemination(p, &s);
    if (k < 0) goto fail;
    v = (double *)malloc(sizeof(double) * (size_t)(k * LETTER_FIELDS + 1));
    if (!v) goto fail;
    for (int i = 0; i < k; i++) {
      const ZfTajweedLetter *x = &s[i];
      const double f[LETTER_FIELDS] = {x->pos, x->word, x->token, x->status, x->score, x->threshold, x->flagged,
                                       x->frame_lo, x->frame_hi};
      memcpy(v + i * LETTER_FIELDS, f, sizeof(f));
    }
    n = k * LETTER_FIELDS;
  } else if (kind == 4) {
    ZfTajweedSummary x;
    memset(&x, 0, sizeof(x));
    if (zf_tajweed_result_summary(p, &x) < 0) goto fail;
    v = (double *)malloc(sizeof(double) * SUMMARY_FIELDS);
    if (!v) goto fail;
    const double f[SUMMARY_FIELDS] = {x.n_sites, x.n_flagged, x.n_madd, x.n_madd_flagged, x.n_qalqalah,
                                      x.n_qalqalah_flagged, x.n_gemination, x.n_gemination_flagged,
                                      x.short_vowel_frames, x.madd_unit_ok};
    memcpy(v, f, sizeof(f));
    n = SUMMARY_FIELDS;
  } else {
    throw_class(env, "java/lang/IllegalArgumentException", "unknown record kind");
    return NULL;
  }
  jdoubleArray out = doubles(env, v, n);
  free(v);
  return out;
fail:
  free(v);
  throw_engine(env, "reading the result failed");
  return NULL;
}

/* ---------------------------------------------------------------------------------- frontend */

/* The exact engine's own front end, for a backend that runs the network elsewhere (the NPU): the
 * resampler to 16 kHz (zf/resample.h) and the kaldi fbank (zf/fbank.h), the same bits the engine's
 * stream computes, plus the same flush at the end (accel/src/stream.c zf_stream_finish_audio: the
 * resampler's tail, 16,800 zero samples, then the fbank's last frames). Only in a library linked with
 * the engine (TJ_WITH_FRONTEND); the scorer-only build has no front end. */
#ifdef TJ_WITH_FRONTEND
#include "zf/fbank.h"
#include "zf/resample.h"

#define TJ_FLUSH_SAMPLES 16800

typedef struct {
  ZfResampler *rs; /* NULL at 16 kHz */
  ZfFbank *fb;
  float *a;        /* 16 kHz samples */
  int a_cap;
  float *f;        /* frames */
  int f_cap;
} TjFrontend;

static int tj_grow(float **p, int *cap, int need) {
  if (need <= *cap) return 1;
  float *b = (float *)realloc(*p, (size_t)need * sizeof(float));
  if (!b) return 0;
  *p = b;
  *cap = need;
  return 1;
}

/* 16 kHz samples into the fbank; frames appended to fe->f from *nf. 0 on failure. */
static int tj_fbank(TjFrontend *fe, const float *x, int n, int *nf) {
  const int most = zf_fbank_max_frames(n);
  if (!tj_grow(&fe->f, &fe->f_cap, (*nf + most) * ZF_FBANK_BINS)) return 0;
  const int got = zf_fbank_accept(fe->fb, x, n, fe->f + (size_t)*nf * ZF_FBANK_BINS, most);
  if (got < 0) return 0;
  *nf += got;
  return 1;
}

static jfloatArray tj_frames(JNIEnv *env, TjFrontend *fe, int nf) {
  jfloatArray out = (*env)->NewFloatArray(env, nf * ZF_FBANK_BINS);
  if (out && nf > 0) (*env)->SetFloatArrayRegion(env, out, 0, nf * ZF_FBANK_BINS, fe->f);
  return out;
}

JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_frontendCreate(JNIEnv *env, jclass cls,
                                                                                       jint rate) {
  (void)cls;
  TjFrontend *fe = (TjFrontend *)calloc(1, sizeof(TjFrontend));
  if (!fe) {
    throw_engine(env, "no memory");
    return 0;
  }
  if (rate != 16000) {
    fe->rs = zf_resampler_create(rate);
    if (!fe->rs) {
      free(fe);
      throw_class(env, "java/lang/IllegalArgumentException", "the resampler does not take this rate");
      return 0;
    }
  }
  fe->fb = zf_fbank_create();
  if (!fe->fb) {
    if (fe->rs) zf_resampler_destroy(fe->rs);
    free(fe);
    throw_engine(env, "no memory");
    return 0;
  }
  return (jlong)(intptr_t)fe;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_frontendDestroy(JNIEnv *env, jclass cls,
                                                                                       jlong h) {
  (void)env;
  (void)cls;
  TjFrontend *fe = (TjFrontend *)(intptr_t)h;
  if (!fe) return;
  if (fe->rs) zf_resampler_destroy(fe->rs);
  zf_fbank_destroy(fe->fb);
  free(fe->a);
  free(fe->f);
  free(fe);
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_frontendReset(JNIEnv *env, jclass cls,
                                                                                     jlong h) {
  (void)cls;
  TjFrontend *fe = (TjFrontend *)ptr(env, h, "the front end is closed");
  if (!fe) return;
  if (fe->rs) zf_resampler_reset(fe->rs);
  zf_fbank_reset(fe->fb);
}

/* Samples at the front end's rate in; every fbank frame they complete out, [frames * 80]. */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_frontendPush(
    JNIEnv *env, jclass cls, jlong h, jfloatArray samples, jint offset, jint length) {
  (void)cls;
  TjFrontend *fe = (TjFrontend *)ptr(env, h, "the front end is closed");
  if (!fe) return NULL;
  if (offset < 0 || length < 0 || offset + length > (*env)->GetArrayLength(env, samples)) {
    throw_class(env, "java/lang/IndexOutOfBoundsException", "samples");
    return NULL;
  }
  float *in = (float *)malloc((size_t)(length > 0 ? length : 1) * sizeof(float));
  if (!in) {
    throw_engine(env, "no memory");
    return NULL;
  }
  (*env)->GetFloatArrayRegion(env, samples, offset, length, in);
  const float *x = in;
  int n = length;
  if (fe->rs) {
    const int cap = zf_resampler_max_out(fe->rs, length);
    if (!tj_grow(&fe->a, &fe->a_cap, cap) || (n = zf_resampler_push(fe->rs, in, length, fe->a, cap)) < 0) {
      free(in);
      throw_engine(env, "the resampler failed");
      return NULL;
    }
    x = fe->a;
  }
  int nf = 0;
  const int ok = tj_fbank(fe, x, n, &nf);
  free(in);
  if (!ok) {
    throw_engine(env, "the fbank failed");
    return NULL;
  }
  return tj_frames(env, fe, nf);
}

/* The end of the take: the resampler's tail, the 16,800-sample zero flush and the fbank's last
 * frames, exactly as the engine's stream ends a take. Reset before the next take. */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_frontendFinish(JNIEnv *env, jclass cls,
                                                                                             jlong h) {
  (void)cls;
  TjFrontend *fe = (TjFrontend *)ptr(env, h, "the front end is closed");
  if (!fe) return NULL;
  int nf = 0;
  if (fe->rs) {
    const int cap = zf_resampler_max_out(fe->rs, 0);
    int got;
    if (!tj_grow(&fe->a, &fe->a_cap, cap > 0 ? cap : 1) || (got = zf_resampler_finish(fe->rs, fe->a, cap)) < 0 ||
        !tj_fbank(fe, fe->a, got, &nf)) {
      throw_engine(env, "the resampler failed");
      return NULL;
    }
  }
  static const float zeros[1200] = {0};
  for (int left = TJ_FLUSH_SAMPLES; left > 0;) {
    const int k = left < 1200 ? left : 1200;
    if (!tj_fbank(fe, zeros, k, &nf)) {
      throw_engine(env, "the fbank failed");
      return NULL;
    }
    left -= k;
  }
  const int most = zf_fbank_max_frames(0);
  if (!tj_grow(&fe->f, &fe->f_cap, (nf + most) * ZF_FBANK_BINS)) {
    throw_engine(env, "no memory");
    return NULL;
  }
  const int got = zf_fbank_finish(fe->fb, fe->f + (size_t)nf * ZF_FBANK_BINS, most);
  if (got < 0) {
    throw_engine(env, "the fbank failed");
    return NULL;
  }
  return tj_frames(env, fe, nf + got);
}
#endif /* TJ_WITH_FRONTEND */

/* ------------------------------------------------------------------------------- words (API 3) */

/* The per-word score of zf_tajweed.h API 3. Against an older header these report "not available"
 * (configWord returns null, configSetWord -2, wordRecords null) and the Kotlin side falls back. */

/* kind 0: a word preset (name; null clears), 1: a custom threshold, 2: clear. 0 ok, -1 refused, -2 no API 3. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configSetWord(JNIEnv *env, jclass cls, jlong c,
                                                                                    jint kind, jstring preset,
                                                                                    jdouble threshold) {
  (void)cls;
#if ZF_TAJWEED_API_VERSION >= 3
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (!p) return -1;
  if (kind == 2) return zf_tajweed_config_clear_word(p);
  if (kind == 1) return zf_tajweed_config_set_word_threshold(p, threshold);
  const char *s = utf(env, preset);
  const int r = zf_tajweed_config_set_word_preset(p, s);
  release_utf(env, preset, s);
  return r;
#else
  (void)env;
  (void)c;
  (void)kind;
  (void)preset;
  (void)threshold;
  return -2;
#endif
}

/* [available, status, enabled, enabled_source, preset, preset_source, threshold, floor, custom_lo,
 *  custom_hi, tau strict, tau general, tau beginner], or null without API 3. */
JNIEXPORT jdoubleArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_configWord(JNIEnv *env, jclass cls,
                                                                                        jlong c) {
  (void)cls;
#if ZF_TAJWEED_API_VERSION >= 3
  ZfTajweedConfig *p = ptr(env, c, "the config is closed");
  if (!p) return NULL;
  ZfTajweedWordSettings w;
  memset(&w, 0, sizeof(w));
  w.struct_size = sizeof(w);
  if (zf_tajweed_config_word(p, &w) < 0) {
    throw_engine(env, "zf_tajweed_config_word failed");
    return NULL;
  }
  const double f[13] = {w.available, w.status, w.enabled, w.enabled_source, w.preset, w.preset_source, w.threshold,
                        w.floor, w.custom_lo, w.custom_hi, w.preset_tau[0], w.preset_tau[1], w.preset_tau[2]};
  return doubles(env, f, 13);
#else
  (void)env;
  (void)c;
  return NULL;
#endif
}

/* 14 fields per word: word, tok_lo, tok_hi, has_score, score, threshold, flagged, cls, edit, edit_pos,
 * expected, alt, llr_final, llr_inter1; null without API 3. */
JNIEXPORT jdoubleArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_wordRecords(JNIEnv *env, jclass cls,
                                                                                         jlong r) {
  (void)cls;
#if ZF_TAJWEED_API_VERSION >= 3
  ZfTajweedResult *p = ptr(env, r, "the result is freed");
  if (!p) return NULL;
  const ZfTajweedWord *w = NULL;
  const int k = zf_tajweed_result_words(p, &w);
  if (k < 0) {
    throw_engine(env, "zf_tajweed_result_words failed");
    return NULL;
  }
  double *v = (double *)malloc(sizeof(double) * 14 * (size_t)(k > 0 ? k : 1));
  if (!v) {
    throw_engine(env, "no memory");
    return NULL;
  }
  for (int i = 0; i < k; i++) {
    const ZfTajweedWord *x = &w[i];
    const double f[14] = {x->word, x->tok_lo, x->tok_hi, x->has_score, x->score, x->threshold, x->flagged, x->cls,
                          x->edit, x->edit_pos, x->expected, x->alt, x->llr_final, x->llr_inter1};
    memcpy(v + i * 14, f, sizeof(f));
  }
  jdoubleArray out = doubles(env, v, k * 14);
  free(v);
  return out;
#else
  (void)env;
  (void)r;
  return NULL;
#endif
}

/* ------------------------------------------------------------------- heads and top-2 (API 3) */

#ifdef TJ_WITH_FRONTEND /* streams exist only in a library with the engine */
/* The heads a take computes (ZF_TAJWEED_HEAD_* bits); 0, or -1 once the take has chunks. */
JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamSetHeads(JNIEnv *env, jclass cls, jlong s,
                                                                                     jint heads) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return -1;
  return zf_tajweed_stream_set_heads(p, (unsigned)heads);
}
#endif

static jfloatArray top2_array(JNIEnv *env, const ZfTajweedTop2 *t, int n) {
  jfloatArray out = (*env)->NewFloatArray(env, n * 5);
  if (!out || n <= 0) return out;
  float *v = (float *)malloc(sizeof(float) * 5 * (size_t)n);
  if (!v) {
    throw_engine(env, "no memory");
    return NULL;
  }
  for (int i = 0; i < n; i++) {
    v[5 * i] = (float)t[i].token;
    v[5 * i + 1] = (float)t[i].runner_up;
    v[5 * i + 2] = t[i].log_prob;
    v[5 * i + 3] = t[i].runner_up_log_prob;
    v[5 * i + 4] = t[i].margin;
  }
  (*env)->SetFloatArrayRegion(env, out, 0, n * 5, v);
  free(v);
  return out;
}

/* zf_tajweed_top2 over log-probs from any source: [token, runner_up, log_prob, runner_up_log_prob,
 * margin] per frame. */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_top2(JNIEnv *env, jclass cls,
                                                                                  jfloatArray log_probs, jint frames,
                                                                                  jint vocab) {
  (void)cls;
  if (frames < 0 || vocab < 2 || (*env)->GetArrayLength(env, log_probs) < frames * vocab) {
    throw_class(env, "java/lang/IllegalArgumentException", "log_probs is not [frames, vocab]");
    return NULL;
  }
  ZfTajweedTop2 *t = (ZfTajweedTop2 *)malloc(sizeof(ZfTajweedTop2) * (size_t)(frames > 0 ? frames : 1));
  float *lp = (float *)malloc(sizeof(float) * (size_t)(frames * vocab > 0 ? frames * vocab : 1));
  if (!t || !lp) {
    free(t);
    free(lp);
    throw_engine(env, "no memory");
    return NULL;
  }
  (*env)->GetFloatArrayRegion(env, log_probs, 0, frames * vocab, lp);
  const int n = zf_tajweed_top2(lp, frames, vocab, t);
  jfloatArray out = n < 0 ? NULL : top2_array(env, t, n);
  if (n < 0) throw_engine(env, "zf_tajweed_top2 failed");
  free(lp);
  free(t);
  return out;
}

#ifdef TJ_WITH_FRONTEND
/* The stream's top-2 of 40 ms frames [from, to) of the take. */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamTop2(JNIEnv *env, jclass cls,
                                                                                        jlong s, jint from, jint to) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return NULL;
  const ZfTajweedTop2 *t = NULL;
  const int n = zf_tajweed_stream_top2(p, &t);
  if (n < 0 || from < 0 || to < from || to > n) {
    throw_class(env, "java/lang/IndexOutOfBoundsException", "top-2 frames out of range");
    return NULL;
  }
  return top2_array(env, t + from, to - from);
}
#endif

/* ---------------------------------------------------------------------------------- top-K (API 3) */

/* Packs [frames, k] ids, values and margins as one float array of 3 floats per entry. */
static jfloatArray topk_array(JNIEnv *env, const int *ids, const float *vals, const float *margins, int n) {
  jfloatArray out = (*env)->NewFloatArray(env, n * 3);
  if (!out || n <= 0) return out;
  float *v = (float *)malloc(sizeof(float) * 3 * (size_t)n);
  if (!v) {
    throw_engine(env, "no memory");
    return NULL;
  }
  for (int i = 0; i < n; i++) {
    v[3 * i] = (float)ids[i];
    v[3 * i + 1] = vals[i];
    v[3 * i + 2] = margins[i];
  }
  (*env)->SetFloatArrayRegion(env, out, 0, n * 3, v);
  free(v);
  return out;
}

/* zf_tajweed_topk over [frames, cols] values from any source: [id, value, margin] per (frame, rank). */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_topk(JNIEnv *env, jclass cls,
                                                                                  jfloatArray values, jint frames,
                                                                                  jint cols, jint k) {
  (void)cls;
  if (frames < 0 || cols < 1 || k < 1 || k > cols || (*env)->GetArrayLength(env, values) < frames * cols) {
    throw_class(env, "java/lang/IllegalArgumentException", "values is not [frames, cols] or k is not 1..cols");
    return NULL;
  }
  const size_t n = (size_t)(frames > 0 ? frames : 1);
  float *x = (float *)malloc(sizeof(float) * n * (size_t)cols);
  int *ids = (int *)malloc(sizeof(int) * n * (size_t)k);
  float *v = (float *)malloc(sizeof(float) * n * (size_t)k);
  float *m = (float *)malloc(sizeof(float) * n * (size_t)k);
  jfloatArray out = NULL;
  if (!x || !ids || !v || !m) {
    throw_engine(env, "no memory");
  } else {
    (*env)->GetFloatArrayRegion(env, values, 0, frames * cols, x);
    if (zf_tajweed_topk(x, frames, cols, k, ids, v, m) < 0) throw_engine(env, "zf_tajweed_topk failed");
    else out = topk_array(env, ids, v, m, frames * k);
  }
  free(x);
  free(ids);
  free(v);
  free(m);
  return out;
}

#ifdef TJ_WITH_FRONTEND
/* zf_tajweed_stream_topk of one kept head (a ZF_TAJWEED_HEAD_* bit) over the take so far. */
JNIEXPORT jfloatArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_streamTopk(JNIEnv *env, jclass cls,
                                                                                        jlong s, jint head, jint k) {
  (void)cls;
  ZfTajweedStream *p = ptr(env, s, "the stream is closed");
  if (!p) return NULL;
  const int frames = zf_tajweed_stream_topk(p, (unsigned)head, k, NULL, NULL, NULL, 0);
  if (frames < 0) {
    throw_class(env, "java/lang/IllegalArgumentException", "the head is not kept, has no ranking, or k is out of range");
    return NULL;
  }
  const size_t n = (size_t)(frames > 0 ? frames : 1) * (size_t)k;
  int *ids = (int *)malloc(sizeof(int) * n);
  float *v = (float *)malloc(sizeof(float) * n);
  float *m = (float *)malloc(sizeof(float) * n);
  jfloatArray out = NULL;
  if (!ids || !v || !m) throw_engine(env, "no memory");
  else if (zf_tajweed_stream_topk(p, (unsigned)head, k, ids, v, m, frames) < 0) throw_engine(env, "zf_tajweed_stream_topk failed");
  else out = topk_array(env, ids, v, m, frames * k);
  free(ids);
  free(v);
  free(m);
  return out;
}
#endif
