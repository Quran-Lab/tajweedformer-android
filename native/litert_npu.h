/* The JNI-free core of litert_npu.c (the NPU runner over LiteRT's C API), shared by the JNI bridge
 * (ai.quranlab.tajweedformer.npu.LiteRtJni) and the device check harness (tools/litert_npu_harness.c).
 * Every tensor is int16, named as in the model's signature; buffers are listed in the caller's name order. */
#ifndef TJ_LITERT_NPU_H
#define TJ_LITERT_NPU_H

#include <stddef.h>
#include <stdint.h>

typedef struct TjNpu TjNpu;       /* one opened model */
typedef struct TjNpuEnv TjNpuEnv; /* a LiteRT environment shared by the parts of a chained build */

/* One model with its own environment (the vendor dispatch library from dispatch_dir, may be NULL).
 * NULL on failure, with the reason in err. */
TjNpu *tj_npu_open(const char *model, const char *dispatch_dir, const char *const *in_names, int n_in,
                   const char *const *out_names, int n_out, char *err, size_t errlen);

/* A shared environment for a chained build, set up as the board runner did (accelerators CPU | NPU,
 * dispatch_dir). The caller holds one reference: release it with tj_npu_env_close (open parts keep the
 * environment alive until they are freed). */
TjNpuEnv *tj_npu_env_open(const char *dispatch_dir, char *err, size_t errlen);
void tj_npu_env_close(TjNpuEnv *e);

/* One part of a chained build in the shared environment e (the part holds a reference to e). */
TjNpu *tj_npu_open_part(TjNpuEnv *e, const char *model, const char *const *in_names, int n_in,
                        const char *const *out_names, int n_out, char *err, size_t errlen);

int tj_npu_inputs(const TjNpu *n);
int tj_npu_outputs(const TjNpu *n);
/* The device buffer's packed bytes (at least the tensor's 2 * element count). */
size_t tj_npu_input_bytes(const TjNpu *n, int i);
size_t tj_npu_output_bytes(const TjNpu *n, int i);

/* in[i] (in_count[i] int16 codes) into input i, run, output i into out[i] (out_count[i] codes).
 * NULL on success, else the reason (a static string). */
const char *tj_npu_run(TjNpu *n, const int16_t *const *in, const size_t *in_count, int16_t *const *out,
                       const size_t *out_count);

void tj_npu_free(TjNpu *n);

#endif
