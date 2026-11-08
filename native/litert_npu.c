/* The NPU backend's runner: LiteRT's C API (libLiteRt.so, LiteRT 2.2.0 ABI), loaded with dlopen when
 * an NPU model is opened, so an app without LiteRT never needs it. For
 * ai.quranlab.tajweedformer.npu.LiteRtRunner.
 *
 * Why C and not LiteRT's Kotlin API: the V4 NPU builds have int16 inputs and outputs, and the Kotlin
 * TensorBuffer (2.2.0 to 2.3.2) reads and writes only int8, int32, int64, float and bool (its int8
 * read returns one byte per element). Here the bytes are copied as they are.
 *
 * The prototypes are LiteRT's own (the headers in litert/c at tag v2.2.0, Apache-2.0), restated with opaque
 * handles; the structs used by value or by address (LiteRtAny, LiteRtEnvOption, LiteRtLayout,
 * LiteRtRankedTensorType) are restated with the same sizes.
 *
 * Two ways to open a model:
 *
 * - ONE model (tj_npu_open, JNI `open`): its own environment (vendor dispatch library from `dispatch_dir`),
 *   options NPU + CPU, buffers from the compiled model's requirements and the model's tensor types, as the
 *   bench runner (run_model --accelerator=npu --dispatch_library_dir) ran it on the Pixels and the Exynos 2600
 *   board.
 * - A CHAINED build (the Exynos 2500's 9 parts): one shared environment for every part (tj_npu_env_open, JNI
 *   `openEnv`, then tj_npu_open_part / `openPart` per part), set up exactly as the board runner that verified the
 *   chain (run_model --graph=P0,...,P8 stream mode, RunStreamMulti, LiteRT C++ API 2.2.0): ONE environment with
 *   the options AutoRegisterAccelerators = CPU | NPU and DispatchLibraryDir; per part, as CompiledModel::Create
 *   does, its own compilation options (hardware CPU | NPU) made BEFORE the model is loaded and kept until the
 *   compiled model is gone; buffers made like CreateInputBuffers / CreateOutputBuffers (the compiled model's
 *   runtime tensor layout over the model's element type); every run given its buffers in the signature's order.
 *   The environment is reference counted: each part holds it, and it is destroyed (with the library handle)
 *   after the last part and the opener have let go.
 *
 * The JNI-free core (everything above the JNI section) is also compiled into the device check harness
 * (native/tools/litert_npu_harness.c, -DTJ_NPU_NO_JNI), so a board runs this file's own code.
 *
 * Status (11-08, Samsung reference boards, the harness over one DEV clip, 15 chunks): this file's core on the
 * Exynos 2500 board (chained path, 9 parts, one shared environment) and on the Exynos 2600 board (one-model path)
 * gives all 12 heads byte-identical to run_model in the same session and to the boards' stored verification
 * outputs. The JNI wrappers and an Android app process have not run on a phone yet; Google Tensor not run. */
#ifndef TJ_NPU_NO_JNI
#include <jni.h>
#endif
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "litert_npu.h"

#if defined(__ANDROID__) || defined(__linux__)
#include <dlfcn.h>
#define TJ_HAVE_DLOPEN 1
#endif

typedef int LiteRtStatus; /* kLiteRtStatusOk = 0 */
typedef void *LiteRtEnvironment;
typedef void *LiteRtModel;
typedef void *LiteRtOptions;
typedef void *LiteRtCompiledModel;
typedef void *LiteRtSignature;
typedef void *LiteRtTensor;
typedef void *LiteRtTensorBufferRequirements;
typedef void *LiteRtTensorBuffer;
typedef size_t LiteRtParamIndex;

/* litert_any.h */
typedef struct {
  int type; /* LiteRtAnyType: 2 = int, 8 = string */
  union {
    _Bool bool_value;
    int64_t int_value;
    double real_value;
    const char *str_value;
    const void *ptr_value;
  };
} LiteRtAny;

/* litert_environment_options.h */
typedef struct {
  int tag; /* kLiteRtEnvOptionTagDispatchLibraryDir = 1, kLiteRtEnvOptionTagAutoRegisterAccelerators = 24 */
  LiteRtAny value;
} LiteRtEnvOption;

/* litert_layout.h: LiteRtLayout is 68 bytes (rank and has_strides bit-fields in one unsigned int, then
 * int32 dimensions[8] at 4 and uint32 strides[8] at 36). litert_model_types.h: LiteRtRankedTensorType is
 * {LiteRtElementType (4 bytes), LiteRtLayout} = 72 bytes, the layout at offset 4. Both are only passed by
 * address and copied as bytes here. */
typedef struct {
  _Alignas(4) unsigned char bytes[68];
} TjLayout;
typedef struct {
  _Alignas(8) unsigned char bytes[128];
} TjRankedTensorType;
#define TJ_TYPE_LAYOUT_OFFSET 4

#define LITERT_ANY_INT 2
#define LITERT_ANY_STRING 8
#define LITERT_ENV_DISPATCH_DIR 1
#define LITERT_ENV_AUTO_REGISTER_ACCELERATORS 24
#define LITERT_HW_CPU 1
#define LITERT_HW_NPU 4
#define LITERT_LOCK_READ 0
#define LITERT_LOCK_WRITE 1

typedef struct {
  LiteRtStatus (*CreateEnvironment)(int, const LiteRtEnvOption *, LiteRtEnvironment *);
  void (*DestroyEnvironment)(LiteRtEnvironment);
  LiteRtStatus (*CreateModelFromFile)(LiteRtEnvironment, const char *, LiteRtModel *);
  void (*DestroyModel)(LiteRtModel);
  LiteRtStatus (*CreateOptions)(LiteRtOptions *);
  LiteRtStatus (*SetOptionsHardwareAccelerators)(LiteRtOptions, int);
  void (*DestroyOptions)(LiteRtOptions);
  LiteRtStatus (*CreateCompiledModel)(LiteRtEnvironment, LiteRtModel, LiteRtOptions, LiteRtCompiledModel *);
  void (*DestroyCompiledModel)(LiteRtCompiledModel);
  LiteRtStatus (*GetModelSignature)(LiteRtModel, LiteRtParamIndex, LiteRtSignature *);
  LiteRtStatus (*GetNumSignatureInputs)(LiteRtSignature, LiteRtParamIndex *);
  LiteRtStatus (*GetSignatureInputName)(LiteRtSignature, LiteRtParamIndex, const char **);
  LiteRtStatus (*GetNumSignatureOutputs)(LiteRtSignature, LiteRtParamIndex *);
  LiteRtStatus (*GetSignatureOutputName)(LiteRtSignature, LiteRtParamIndex, const char **);
  LiteRtStatus (*GetSignatureInputTensorByIndex)(LiteRtSignature, LiteRtParamIndex, LiteRtTensor *);
  LiteRtStatus (*GetSignatureOutputTensorByIndex)(LiteRtSignature, LiteRtParamIndex, LiteRtTensor *);
  LiteRtStatus (*GetRankedTensorType)(LiteRtTensor, TjRankedTensorType *);
  LiteRtStatus (*GetCompiledModelInputBufferRequirements)(LiteRtCompiledModel, LiteRtParamIndex, LiteRtParamIndex,
                                                          LiteRtTensorBufferRequirements *);
  LiteRtStatus (*GetCompiledModelOutputBufferRequirements)(LiteRtCompiledModel, LiteRtParamIndex, LiteRtParamIndex,
                                                           LiteRtTensorBufferRequirements *);
  LiteRtStatus (*CreateManagedTensorBufferFromRequirements)(LiteRtEnvironment, const TjRankedTensorType *,
                                                            LiteRtTensorBufferRequirements, LiteRtTensorBuffer *);
  LiteRtStatus (*LockTensorBuffer)(LiteRtTensorBuffer, void **, int);
  LiteRtStatus (*UnlockTensorBuffer)(LiteRtTensorBuffer);
  LiteRtStatus (*GetTensorBufferPackedSize)(LiteRtTensorBuffer, size_t *);
  void (*DestroyTensorBuffer)(LiteRtTensorBuffer);
  LiteRtStatus (*RunCompiledModel)(LiteRtCompiledModel, LiteRtParamIndex, size_t, LiteRtTensorBuffer *, size_t,
                                   LiteRtTensorBuffer *);
  /* the chained path only (bound without failing; checked when a shared environment is opened) */
  LiteRtStatus (*GetCompiledModelInputTensorLayout)(LiteRtCompiledModel, LiteRtParamIndex, LiteRtParamIndex, TjLayout *);
  LiteRtStatus (*GetCompiledModelOutputTensorLayouts)(LiteRtCompiledModel, LiteRtParamIndex, size_t, TjLayout *, _Bool);
} TjLiteRt;

/* A shared environment (chained builds): what the board runner had once for all its graphs. */
struct TjNpuEnv {
  TjLiteRt api;
  void *lib;
  LiteRtEnvironment env;
  int refs; /* the opener + one per open part; the env goes with the last */
};

struct TjNpu {
  TjLiteRt api;
  void *lib;               /* own library handle (one model); NULL for a part */
  LiteRtEnvironment env;   /* own or the shared one's */
  LiteRtModel model;
  LiteRtOptions options;
  LiteRtCompiledModel compiled;
  int n_in, n_out;
  LiteRtTensorBuffer *in, *out; /* in the caller's name order */
  size_t *in_size, *out_size;   /* packed bytes */
  TjNpuEnv *shared;             /* a part of a chained build: the environment it holds */
  LiteRtTensorBuffer *run_in, *run_out; /* a part: the same buffers in the signature's order, as Run takes them */
};

static void tj_err(char *err, size_t errlen, const char *msg) {
  if (err && errlen) snprintf(err, errlen, "%s", msg);
}

static void tj_env_release(TjNpuEnv *e) {
  if (!e || --e->refs > 0) return;
  if (e->env) e->api.DestroyEnvironment(e->env);
#ifdef TJ_HAVE_DLOPEN
  if (e->lib) dlclose(e->lib);
#endif
  free(e);
}

void tj_npu_free(TjNpu *n) {
  if (!n) return;
  for (int i = 0; i < n->n_in; i++)
    if (n->in && n->in[i]) n->api.DestroyTensorBuffer(n->in[i]);
  for (int i = 0; i < n->n_out; i++)
    if (n->out && n->out[i]) n->api.DestroyTensorBuffer(n->out[i]);
  if (n->compiled) n->api.DestroyCompiledModel(n->compiled);
  if (n->options) n->api.DestroyOptions(n->options);
  if (n->model) n->api.DestroyModel(n->model);
  if (n->env && !n->shared) n->api.DestroyEnvironment(n->env);
#ifdef TJ_HAVE_DLOPEN
  if (n->lib) dlclose(n->lib);
#endif
  free(n->in);
  free(n->out);
  free(n->in_size);
  free(n->out_size);
  free(n->run_in);
  free(n->run_out);
  if (n->shared) tj_env_release(n->shared);
  free(n);
}

void tj_npu_env_close(TjNpuEnv *e) { tj_env_release(e); }

#ifdef TJ_HAVE_DLOPEN
static int tj_bind(TjLiteRt *api, void **lib, char *err, size_t errlen) {
  *lib = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
  if (!*lib) {
    snprintf(err, errlen, "libLiteRt.so not found (add com.google.ai.edge.litert:litert:2.2.0 to the app): %s", dlerror());
    return 0;
  }
#define TJ_SYM(field, name)                                         \
  do {                                                              \
    *(void **)&api->field = dlsym(*lib, name);                      \
    if (!api->field) {                                              \
      snprintf(err, errlen, "libLiteRt.so has no %s", name);        \
      return 0;                                                     \
    }                                                               \
  } while (0)
  TJ_SYM(CreateEnvironment, "LiteRtCreateEnvironment");
  TJ_SYM(DestroyEnvironment, "LiteRtDestroyEnvironment");
  TJ_SYM(CreateModelFromFile, "LiteRtCreateModelFromFile");
  TJ_SYM(DestroyModel, "LiteRtDestroyModel");
  TJ_SYM(CreateOptions, "LiteRtCreateOptions");
  TJ_SYM(SetOptionsHardwareAccelerators, "LiteRtSetOptionsHardwareAccelerators");
  TJ_SYM(DestroyOptions, "LiteRtDestroyOptions");
  TJ_SYM(CreateCompiledModel, "LiteRtCreateCompiledModel");
  TJ_SYM(DestroyCompiledModel, "LiteRtDestroyCompiledModel");
  TJ_SYM(GetModelSignature, "LiteRtGetModelSignature");
  TJ_SYM(GetNumSignatureInputs, "LiteRtGetNumSignatureInputs");
  TJ_SYM(GetSignatureInputName, "LiteRtGetSignatureInputName");
  TJ_SYM(GetNumSignatureOutputs, "LiteRtGetNumSignatureOutputs");
  TJ_SYM(GetSignatureOutputName, "LiteRtGetSignatureOutputName");
  TJ_SYM(GetSignatureInputTensorByIndex, "LiteRtGetSignatureInputTensorByIndex");
  TJ_SYM(GetSignatureOutputTensorByIndex, "LiteRtGetSignatureOutputTensorByIndex");
  TJ_SYM(GetRankedTensorType, "LiteRtGetRankedTensorType");
  TJ_SYM(GetCompiledModelInputBufferRequirements, "LiteRtGetCompiledModelInputBufferRequirements");
  TJ_SYM(GetCompiledModelOutputBufferRequirements, "LiteRtGetCompiledModelOutputBufferRequirements");
  TJ_SYM(CreateManagedTensorBufferFromRequirements, "LiteRtCreateManagedTensorBufferFromRequirements");
  TJ_SYM(LockTensorBuffer, "LiteRtLockTensorBuffer");
  TJ_SYM(UnlockTensorBuffer, "LiteRtUnlockTensorBuffer");
  TJ_SYM(GetTensorBufferPackedSize, "LiteRtGetTensorBufferPackedSize");
  TJ_SYM(DestroyTensorBuffer, "LiteRtDestroyTensorBuffer");
  TJ_SYM(RunCompiledModel, "LiteRtRunCompiledModel");
#undef TJ_SYM
  *(void **)&api->GetCompiledModelInputTensorLayout = dlsym(*lib, "LiteRtGetCompiledModelInputTensorLayout");
  *(void **)&api->GetCompiledModelOutputTensorLayouts = dlsym(*lib, "LiteRtGetCompiledModelOutputTensorLayouts");
  return 1;
}

/* names[i] -> buffers[i]: signature 0's tensor of that name, a buffer from the compiled model's
 * requirements. One model: the model's tensor type. A part (n->shared): the compiled model's runtime
 * layout over the model's element type, and run_in / run_out in the signature's order (RunStreamMulti's
 * CreateInputBuffers / CreateOutputBuffers). */
static int tj_buffers(TjNpu *n, LiteRtSignature sig, const char *const *names, int want, int is_input,
                      LiteRtTensorBuffer *bufs, size_t *sizes, char *err, size_t errlen) {
  LiteRtParamIndex count = 0;
  if ((is_input ? n->api.GetNumSignatureInputs(sig, &count) : n->api.GetNumSignatureOutputs(sig, &count)) != 0) {
    snprintf(err, errlen, "the model's signature has no %s", is_input ? "inputs" : "outputs");
    return 0;
  }
  if ((int)count != want) {
    snprintf(err, errlen, "the model has %d %s, io.json %d", (int)count, is_input ? "inputs" : "outputs", want);
    return 0;
  }
  TjLayout *out_layouts = NULL;
  if (n->shared && !is_input) {
    out_layouts = (TjLayout *)calloc(count ? count : 1, sizeof(TjLayout));
    if (!out_layouts || n->api.GetCompiledModelOutputTensorLayouts(n->compiled, 0, count, out_layouts, 0) != 0) {
      free(out_layouts);
      snprintf(err, errlen, "the compiled model's output layouts failed");
      return 0;
    }
  }
  LiteRtTensorBuffer *run = is_input ? n->run_in : n->run_out;
  for (int i = 0; i < want; i++) {
    const char *name = names[i];
    LiteRtParamIndex k = 0;
    int found = 0;
    for (; k < count; k++) {
      const char *nm = NULL;
      if ((is_input ? n->api.GetSignatureInputName(sig, k, &nm) : n->api.GetSignatureOutputName(sig, k, &nm)) == 0 &&
          nm && strcmp(nm, name) == 0) {
        found = 1;
        break;
      }
    }
    int ok = found;
    LiteRtTensor t = NULL;
    TjRankedTensorType type;
    LiteRtTensorBufferRequirements req = NULL;
    memset(&type, 0, sizeof(type));
    if (ok) ok = (is_input ? n->api.GetSignatureInputTensorByIndex(sig, k, &t) : n->api.GetSignatureOutputTensorByIndex(sig, k, &t)) == 0;
    if (ok) ok = n->api.GetRankedTensorType(t, &type) == 0;
    if (ok && n->shared) {
      if (is_input) {
        TjLayout l;
        memset(&l, 0, sizeof(l));
        if (n->api.GetCompiledModelInputTensorLayout(n->compiled, 0, k, &l) == 0)
          memcpy(type.bytes + TJ_TYPE_LAYOUT_OFFSET, l.bytes, sizeof(l.bytes));
      } else {
        memcpy(type.bytes + TJ_TYPE_LAYOUT_OFFSET, out_layouts[k].bytes, sizeof(out_layouts[k].bytes));
      }
      if (run[k]) ok = 0; /* the same tensor twice */
    }
    if (ok)
      ok = (is_input ? n->api.GetCompiledModelInputBufferRequirements(n->compiled, 0, k, &req)
                     : n->api.GetCompiledModelOutputBufferRequirements(n->compiled, 0, k, &req)) == 0;
    if (ok) ok = n->api.CreateManagedTensorBufferFromRequirements(n->env, &type, req, &bufs[i]) == 0;
    if (ok) ok = n->api.GetTensorBufferPackedSize(bufs[i], &sizes[i]) == 0;
    if (ok && n->shared) run[k] = bufs[i];
    if (!ok) snprintf(err, errlen, "%s %s: %s", is_input ? "input" : "output", name, found ? "no buffer" : "not in the model");
    if (!ok) {
      free(out_layouts);
      return 0;
    }
  }
  free(out_layouts);
  return 1;
}

/* Model, options (unless made already: a part makes them first), compiled model, signature, buffers. */
static TjNpu *tj_open_model(TjNpu *n, const char *model, const char *const *in_names, int n_in,
                            const char *const *out_names, int n_out, char *err, size_t errlen) {
  const int model_ok = n->api.CreateModelFromFile(n->env, model, &n->model) == 0;
  LiteRtSignature sig = NULL;
  const char *why = NULL;
  if (!model_ok) why = "LiteRtCreateModelFromFile failed";
  else if (!n->options && (n->api.CreateOptions(&n->options) != 0 ||
                           n->api.SetOptionsHardwareAccelerators(n->options, LITERT_HW_NPU | LITERT_HW_CPU) != 0))
    why = "LiteRT options failed";
  else if (n->api.CreateCompiledModel(n->env, n->model, n->options, &n->compiled) != 0)
    why = "LiteRtCreateCompiledModel failed (no NPU or no vendor dispatch library?)";
  else if (n->api.GetModelSignature(n->model, 0, &sig) != 0)
    why = "the model has no signature";
  if (why) {
    tj_npu_free(n);
    tj_err(err, errlen, why);
    return NULL;
  }
  n->n_in = n_in;
  n->n_out = n_out;
  n->in = (LiteRtTensorBuffer *)calloc((size_t)n->n_in, sizeof(LiteRtTensorBuffer));
  n->out = (LiteRtTensorBuffer *)calloc((size_t)n->n_out, sizeof(LiteRtTensorBuffer));
  n->in_size = (size_t *)calloc((size_t)n->n_in, sizeof(size_t));
  n->out_size = (size_t *)calloc((size_t)n->n_out, sizeof(size_t));
  int ok = n->in && n->out && n->in_size && n->out_size;
  if (ok && n->shared) {
    n->run_in = (LiteRtTensorBuffer *)calloc((size_t)(n_in ? n_in : 1), sizeof(LiteRtTensorBuffer));
    n->run_out = (LiteRtTensorBuffer *)calloc((size_t)(n_out ? n_out : 1), sizeof(LiteRtTensorBuffer));
    ok = n->run_in && n->run_out;
  }
  if (!ok || !tj_buffers(n, sig, in_names, n_in, 1, n->in, n->in_size, err, errlen) ||
      !tj_buffers(n, sig, out_names, n_out, 0, n->out, n->out_size, err, errlen)) {
    if (!ok) tj_err(err, errlen, "no memory");
    tj_npu_free(n);
    return NULL;
  }
  return n;
}
#endif

TjNpu *tj_npu_open(const char *model, const char *dispatch_dir, const char *const *in_names, int n_in,
                   const char *const *out_names, int n_out, char *err, size_t errlen) {
#ifndef TJ_HAVE_DLOPEN
  (void)model;
  (void)dispatch_dir;
  (void)in_names;
  (void)n_in;
  (void)out_names;
  (void)n_out;
  tj_err(err, errlen, "the NPU runner needs Android (LiteRT)");
  return NULL;
#else
  TjNpu *n = (TjNpu *)calloc(1, sizeof(TjNpu));
  if (!n) {
    tj_err(err, errlen, "no memory");
    return NULL;
  }
  if (!tj_bind(&n->api, &n->lib, err, errlen)) {
    tj_npu_free(n);
    return NULL;
  }
  LiteRtEnvOption opt;
  memset(&opt, 0, sizeof(opt));
  opt.tag = LITERT_ENV_DISPATCH_DIR;
  opt.value.type = LITERT_ANY_STRING;
  opt.value.str_value = dispatch_dir;
  if (n->api.CreateEnvironment(dispatch_dir ? 1 : 0, dispatch_dir ? &opt : NULL, &n->env) != 0) {
    tj_npu_free(n);
    tj_err(err, errlen, "LiteRtCreateEnvironment failed");
    return NULL;
  }
  return tj_open_model(n, model, in_names, n_in, out_names, n_out, err, errlen);
#endif
}

TjNpuEnv *tj_npu_env_open(const char *dispatch_dir, char *err, size_t errlen) {
#ifndef TJ_HAVE_DLOPEN
  (void)dispatch_dir;
  tj_err(err, errlen, "the NPU runner needs Android (LiteRT)");
  return NULL;
#else
  TjNpuEnv *e = (TjNpuEnv *)calloc(1, sizeof(TjNpuEnv));
  if (!e) {
    tj_err(err, errlen, "no memory");
    return NULL;
  }
  e->refs = 1;
  if (!tj_bind(&e->api, &e->lib, err, errlen)) {
    tj_env_release(e);
    return NULL;
  }
  if (!e->api.GetCompiledModelInputTensorLayout || !e->api.GetCompiledModelOutputTensorLayouts) {
    tj_env_release(e);
    tj_err(err, errlen, "libLiteRt.so has no compiled-model tensor layouts (LiteRT 2.2.0 or later)");
    return NULL;
  }
  /* run_model's GetEnvironment() with --accelerator=npu --dispatch_library_dir=DIR: the accelerators to
   * register (CPU | NPU), then the dispatch library folder */
  LiteRtEnvOption opt[2];
  memset(opt, 0, sizeof(opt));
  opt[0].tag = LITERT_ENV_AUTO_REGISTER_ACCELERATORS;
  opt[0].value.type = LITERT_ANY_INT;
  opt[0].value.int_value = LITERT_HW_CPU | LITERT_HW_NPU;
  opt[1].tag = LITERT_ENV_DISPATCH_DIR;
  opt[1].value.type = LITERT_ANY_STRING;
  opt[1].value.str_value = dispatch_dir;
  if (e->api.CreateEnvironment(dispatch_dir ? 2 : 1, opt, &e->env) != 0) {
    tj_env_release(e);
    tj_err(err, errlen, "LiteRtCreateEnvironment failed");
    return NULL;
  }
  return e;
#endif
}

TjNpu *tj_npu_open_part(TjNpuEnv *e, const char *model, const char *const *in_names, int n_in,
                        const char *const *out_names, int n_out, char *err, size_t errlen) {
#ifndef TJ_HAVE_DLOPEN
  (void)e;
  (void)model;
  (void)in_names;
  (void)n_in;
  (void)out_names;
  (void)n_out;
  tj_err(err, errlen, "the NPU runner needs Android (LiteRT)");
  return NULL;
#else
  if (!e) {
    tj_err(err, errlen, "no shared environment");
    return NULL;
  }
  TjNpu *n = (TjNpu *)calloc(1, sizeof(TjNpu));
  if (!n) {
    tj_err(err, errlen, "no memory");
    return NULL;
  }
  n->api = e->api;
  n->env = e->env;
  n->shared = e;
  e->refs++;
  /* CompiledModel::Create: Options::Build (CreateOptions, hardware CPU | NPU) before CreateModelFromFile */
  if (n->api.CreateOptions(&n->options) != 0 ||
      n->api.SetOptionsHardwareAccelerators(n->options, LITERT_HW_CPU | LITERT_HW_NPU) != 0) {
    tj_npu_free(n);
    tj_err(err, errlen, "LiteRT options failed");
    return NULL;
  }
  return tj_open_model(n, model, in_names, n_in, out_names, n_out, err, errlen);
#endif
}

int tj_npu_inputs(const TjNpu *n) { return n ? n->n_in : 0; }
int tj_npu_outputs(const TjNpu *n) { return n ? n->n_out : 0; }
size_t tj_npu_input_bytes(const TjNpu *n, int i) { return n && i >= 0 && i < n->n_in ? n->in_size[i] : 0; }
size_t tj_npu_output_bytes(const TjNpu *n, int i) { return n && i >= 0 && i < n->n_out ? n->out_size[i] : 0; }

#ifdef TJ_HAVE_DLOPEN
/* Input i: [bytes] of int16 codes (at most the buffer's packed size) into the locked buffer. The callback
 * fills dst; a padded device buffer (the TPU pads some inputs) gets zeros after the tensor's bytes. */
static const char *tj_put_input(TjNpu *n, int i, size_t bytes, void (*fill)(void *ctx, void *dst, size_t bytes), void *ctx) {
  void *dst = NULL;
  if (bytes > n->in_size[i]) return "an NPU input buffer is smaller than its tensor";
  if (n->api.LockTensorBuffer(n->in[i], &dst, LITERT_LOCK_WRITE) != 0 || !dst) return "locking an NPU input failed";
  fill(ctx, dst, bytes);
  if (n->in_size[i] > bytes) memset((char *)dst + bytes, 0, n->in_size[i] - bytes);
  n->api.UnlockTensorBuffer(n->in[i]);
  return NULL;
}

static const char *tj_invoke(TjNpu *n) {
  LiteRtTensorBuffer *in = n->shared ? n->run_in : n->in;
  LiteRtTensorBuffer *out = n->shared ? n->run_out : n->out;
  if (n->api.RunCompiledModel(n->compiled, 0, (size_t)n->n_in, in, (size_t)n->n_out, out) != 0)
    return "LiteRtRunCompiledModel failed";
  return NULL;
}

static const char *tj_get_output(TjNpu *n, int i, size_t bytes, void (*take)(void *ctx, const void *src, size_t bytes), void *ctx) {
  void *src = NULL;
  if (bytes > n->out_size[i] || n->api.LockTensorBuffer(n->out[i], &src, LITERT_LOCK_READ) != 0 || !src)
    return "reading an NPU output failed";
  take(ctx, src, bytes);
  n->api.UnlockTensorBuffer(n->out[i]);
  return NULL;
}

static void tj_copy_in(void *ctx, void *dst, size_t bytes) { memcpy(dst, ctx, bytes); }
static void tj_copy_out(void *ctx, const void *src, size_t bytes) { memcpy(ctx, src, bytes); }
#endif

const char *tj_npu_run(TjNpu *n, const int16_t *const *in, const size_t *in_count, int16_t *const *out,
                       const size_t *out_count) {
  if (!n) return "the NPU runner is closed";
#ifdef TJ_HAVE_DLOPEN
  const char *why = NULL;
  for (int i = 0; i < n->n_in && !why; i++) why = tj_put_input(n, i, in_count[i] * 2, tj_copy_in, (void *)in[i]);
  if (!why) why = tj_invoke(n);
  for (int i = 0; i < n->n_out && !why; i++) why = tj_get_output(n, i, out_count[i] * 2, tj_copy_out, out[i]);
  return why;
#else
  (void)in;
  (void)in_count;
  (void)out;
  (void)out_count;
  return "the NPU runner needs Android (LiteRT)";
#endif
}

/* ------------------------------------------------------------------------------------------------- JNI */
#ifndef TJ_NPU_NO_JNI

static void tj_throw(JNIEnv *env, const char *cls, const char *msg) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass c = (*env)->FindClass(env, cls);
  if (c) (*env)->ThrowNew(env, c, msg);
}

static void tj_unavailable(JNIEnv *env, const char *msg) {
  tj_throw(env, "ai/quranlab/tajweedformer/RecognizerException$BackendUnavailable", msg);
}

/* A Java String[] as UTF-8 C strings (freed by tj_names_free). */
static const char **tj_names(JNIEnv *env, jobjectArray a, int *count) {
  const int n = (*env)->GetArrayLength(env, a);
  const char **s = (const char **)calloc((size_t)(n ? n : 1), sizeof(char *));
  if (!s) return NULL;
  for (int i = 0; i < n; i++) {
    jstring js = (jstring)(*env)->GetObjectArrayElement(env, a, i);
    const char *u = (*env)->GetStringUTFChars(env, js, NULL);
    s[i] = u ? strdup(u) : NULL;
    if (u) (*env)->ReleaseStringUTFChars(env, js, u);
    (*env)->DeleteLocalRef(env, js);
    if (!s[i]) {
      for (int k = 0; k < i; k++) free((void *)s[k]);
      free(s);
      return NULL;
    }
  }
  *count = n;
  return s;
}

static void tj_names_free(const char **s, int n) {
  if (!s) return;
  for (int i = 0; i < n; i++) free((void *)s[i]);
  free(s);
}

typedef TjNpu *(*TjOpenFn)(void *ctx, const char *model, const char *const *in, int n_in, const char *const *out,
                           int n_out, char *err, size_t errlen);

static jlong tj_open_jni(JNIEnv *env, jstring model, jobjectArray inputs, jobjectArray outputs, TjOpenFn open, void *ctx) {
  char err[512] = {0};
  int n_in = 0, n_out = 0;
  const char **in = tj_names(env, inputs, &n_in);
  const char **out = in ? tj_names(env, outputs, &n_out) : NULL;
  const char *path = (*env)->GetStringUTFChars(env, model, NULL);
  TjNpu *n = NULL;
  if (in && out && path) n = open(ctx, path, in, n_in, out, n_out, err, sizeof err);
  else snprintf(err, sizeof err, "no memory");
  if (path) (*env)->ReleaseStringUTFChars(env, model, path);
  tj_names_free(in, n_in);
  tj_names_free(out, n_out);
  if (!n) {
    tj_unavailable(env, err[0] ? err : "no memory");
    return 0;
  }
  return (jlong)(intptr_t)n;
}

static TjNpu *tj_open_own(void *ctx, const char *model, const char *const *in, int n_in, const char *const *out,
                          int n_out, char *err, size_t errlen) {
  return tj_npu_open(model, (const char *)ctx, in, n_in, out, n_out, err, errlen);
}

static TjNpu *tj_open_shared(void *ctx, const char *model, const char *const *in, int n_in, const char *const *out,
                             int n_out, char *err, size_t errlen) {
  return tj_npu_open_part((TjNpuEnv *)ctx, model, in, n_in, out, n_out, err, errlen);
}

/* One model with its own environment. */
JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_open(JNIEnv *env, jclass cls, jstring model,
                                                                         jstring dispatch_dir, jobjectArray inputs,
                                                                         jobjectArray outputs) {
  (void)cls;
  const char *dir = dispatch_dir ? (*env)->GetStringUTFChars(env, dispatch_dir, NULL) : NULL;
  char *d = dir ? strdup(dir) : NULL;
  if (dir) (*env)->ReleaseStringUTFChars(env, dispatch_dir, dir);
  if (dir && !d) {
    tj_unavailable(env, "no memory");
    return 0;
  }
  jlong h = tj_open_jni(env, model, inputs, outputs, tj_open_own, d);
  free(d);
  return h;
}

/* A shared environment for the parts of a chained build; the caller closes its hold with closeEnv. */
JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_openEnv(JNIEnv *env, jclass cls, jstring dispatch_dir) {
  (void)cls;
  char err[512] = {0};
  const char *dir = dispatch_dir ? (*env)->GetStringUTFChars(env, dispatch_dir, NULL) : NULL;
  TjNpuEnv *e = tj_npu_env_open(dir, err, sizeof err);
  if (dir) (*env)->ReleaseStringUTFChars(env, dispatch_dir, dir);
  if (!e) {
    tj_unavailable(env, err[0] ? err : "no memory");
    return 0;
  }
  return (jlong)(intptr_t)e;
}

/* One part of a chained build in the shared environment [e] (which the part holds until it is closed). */
JNIEXPORT jlong JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_openPart(JNIEnv *env, jclass cls, jlong e, jstring model,
                                                                             jobjectArray inputs, jobjectArray outputs) {
  (void)cls;
  if (!e) {
    tj_unavailable(env, "no shared environment");
    return 0;
  }
  return tj_open_jni(env, model, inputs, outputs, tj_open_shared, (void *)(intptr_t)e);
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_closeEnv(JNIEnv *env, jclass cls, jlong e) {
  (void)env;
  (void)cls;
  tj_npu_env_close((TjNpuEnv *)(intptr_t)e);
}

#ifdef TJ_HAVE_DLOPEN
typedef struct {
  JNIEnv *env;
  jshortArray a;
} TjJArray;

static void tj_fill_from_java(void *ctx, void *dst, size_t bytes) {
  TjJArray *j = (TjJArray *)ctx;
  (*j->env)->GetShortArrayRegion(j->env, j->a, 0, (jsize)(bytes / 2), (jshort *)dst);
}

static void tj_take_to_java(void *ctx, const void *src, size_t bytes) {
  TjJArray *j = (TjJArray *)ctx;
  (*j->env)->SetShortArrayRegion(j->env, j->a, 0, (jsize)(bytes / 2), (const jshort *)src);
}
#endif

/* inputs[i] (int16 codes) into the i-th input buffer, run, the i-th output buffer into outputs[i]. */
JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_run(JNIEnv *env, jclass cls, jlong h,
                                                                       jobjectArray inputs, jobjectArray outputs) {
  (void)cls;
  TjNpu *n = (TjNpu *)(intptr_t)h;
  if (!n) {
    tj_throw(env, "java/lang/IllegalStateException", "the NPU runner is closed");
    return;
  }
#ifdef TJ_HAVE_DLOPEN
  for (int i = 0; i < n->n_in; i++) {
    TjJArray j = {env, (jshortArray)(*env)->GetObjectArrayElement(env, inputs, i)};
    const char *why = tj_put_input(n, i, (size_t)(*env)->GetArrayLength(env, j.a) * 2, tj_fill_from_java, &j);
    (*env)->DeleteLocalRef(env, j.a);
    if (why) {
      tj_throw(env, "ai/quranlab/tajweedformer/RecognizerException$EngineFailed", why);
      return;
    }
  }
  const char *why = tj_invoke(n);
  if (why) {
    tj_throw(env, "ai/quranlab/tajweedformer/RecognizerException$EngineFailed", why);
    return;
  }
  for (int i = 0; i < n->n_out; i++) {
    TjJArray j = {env, (jshortArray)(*env)->GetObjectArrayElement(env, outputs, i)};
    why = tj_get_output(n, i, (size_t)(*env)->GetArrayLength(env, j.a) * 2, tj_take_to_java, &j);
    (*env)->DeleteLocalRef(env, j.a);
    if (why) {
      tj_throw(env, "ai/quranlab/tajweedformer/RecognizerException$EngineFailed", why);
      return;
    }
  }
#else
  (void)inputs;
  (void)outputs;
#endif
}

JNIEXPORT void JNICALL Java_ai_quranlab_tajweedformer_npu_LiteRtJni_close(JNIEnv *env, jclass cls, jlong h) {
  (void)env;
  (void)cls;
  tj_npu_free((TjNpu *)(intptr_t)h);
}

#endif /* TJ_NPU_NO_JNI */
