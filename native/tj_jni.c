/* JNI bridge over zf_tajweed.h (the TajweedFormer V4 engine, API version 4: inference only), for
 * ai.quranlab.tajweedformer.engine.TajweedJni.
 *
 * Handles are the C API's own pointers (model, stream, reference) passed as longs. Heads come
 * back to Kotlin as copies, a frame range at a time, in the head order of the engine; the C API keeps
 * every head of the take, so Kotlin asks for the frames it has not seen after each push.
 *
 * Not thread-safe per handle, like the C API: Kotlin calls a stream from one thread at a time. A model
 * and a reference are immutable after open.
 *
 * Errors: a model that cannot be opened throws RecognizerException.ModelLoadFailed(message); any
 * other failure RecognizerException.EngineFailed(message); a closed handle IllegalStateException. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "zf_tajweed.h"

#define HEADS 12

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
    JNIEnv *env, jclass cls, jstring dir, jstring context, jint threads, jint sample_rate, jint batch, jint no_pin) {
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
  /* the context is the SDK's choice (the app, then the signed config, then the manifest's default): the engine
   * needs it to load, and no longer looks one up */
  o.context = c;
  o.load_engine = 1;
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

/* The exact engine's own front end, for a backend that runs the network elsewhere (the NPU): the
 * resampler to 16 kHz (zf/resample.h) and the kaldi fbank (zf/fbank.h), the same bits the engine's
 * stream computes, plus the same flush at the end (accel/src/stream.c zf_stream_finish_audio: the
 * resampler's tail, 16,800 zero samples, then the fbank's last frames). Only in a library linked with
 * the engine (TJ_WITH_FRONTEND). */
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

/* ---------------------------------------------------------------------------- heads and top-2 */

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

/* ------------------------------------------------------------------------------------------ top-K */

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

/* --------------------------------------------------------------------------- reference data */

/* The reference data (the expected units of each ayah) through the engine's reader: read-only data with no
 * link to a model. Nothing here compares it with a take. */

JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_referenceOpen(JNIEnv *env, jclass cls, jstring path) {
  (void)cls;
  const char *p = utf(env, path);
  if (!p) {
    throw_load(env, "no reference file");
    return 0;
  }
  const char *err = NULL;
  ZfTajweedReference *r = zf_tajweed_reference_open(p, &err);
  release_utf(env, path, p);
  if (!r) throw_load(env, err ? err : "zf_tajweed_reference_open failed");
  return (jlong)(intptr_t)r;
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_referenceClose(JNIEnv *env, jclass cls, jlong r) {
  (void)env;
  (void)cls;
  if (r) zf_tajweed_reference_close((ZfTajweedReference *)(intptr_t)r);
}

JNIEXPORT jint JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_referenceAyahCount(JNIEnv *env, jclass cls, jlong r,
                                                                                         jint surah) {
  (void)cls;
  ZfTajweedReference *p = ptr(env, r, "the reference is closed");
  return p ? zf_tajweed_reference_ayah_count(p, surah) : 0;
}

/* One ayah as [unit ids (n), then the 0-based word of each unit (n)]; null for an ayah the table does not have. */
JNIEXPORT jintArray JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_referenceUnits(JNIEnv *env, jclass cls, jlong r,
                                                                                          jint surah, jint ayah) {
  (void)cls;
  ZfTajweedReference *p = ptr(env, r, "the reference is closed");
  if (!p) return NULL;
  const int *units = NULL;
  const int *words = NULL;
  int n_words = 0;
  const int n = zf_tajweed_reference_units(p, surah, ayah, &units, &words, &n_words);
  if (n < 0 || (n > 0 && (!units || !words))) return NULL;
  jintArray out = (*env)->NewIntArray(env, 2 * n);
  if (out && n > 0) {
    (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *)units);
    (*env)->SetIntArrayRegion(env, out, n, n, (const jint *)words);
  }
  return out;
}

/* The SHA-256 (64 lower-case hex) of the tokens.txt the table's ids refer to. */
JNIEXPORT jstring JNICALL Java_ai_quranlab_tajweedformer_engine_TajweedJni_referenceTokensSha256(JNIEnv *env, jclass cls,
                                                                                               jlong r) {
  (void)cls;
  ZfTajweedReference *p = ptr(env, r, "the reference is closed");
  if (!p) return NULL;
  const unsigned char *h = zf_tajweed_reference_tokens_sha256(p);
  if (!h) return NULL;
  char hex[65];
  static const char digits[] = "0123456789abcdef";
  for (int i = 0; i < 32; i++) {
    hex[2 * i] = digits[h[i] >> 4];
    hex[2 * i + 1] = digits[h[i] & 15];
  }
  hex[64] = 0;
  return (*env)->NewStringUTF(env, hex);
}
