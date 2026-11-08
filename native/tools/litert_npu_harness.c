/* Device check for the SDK's NPU runner: native/litert_npu.c's own code (compiled with -DTJ_NPU_NO_JNI, no JVM)
 * runs a one-model or chained NPU build over a stream pack and writes every head, so a board's output can be
 * compared byte for byte with the bench runner's (LiteRT run_model --graph=... --stream_dir=..., which verified the
 * builds).
 *
 *   litert_npu_harness --graph=GRAPH.txt --pack=PACK --out=OUT [--dispatch_dir=DIR] [--chunks=N]
 *
 * GRAPH.txt (written from the build's io.json; names in io.json's order, as LiteRtRunner passes them):
 *   geom <window_frames> <shift_frames> <mask_chunk_sets>
 *   part <model file, relative to GRAPH.txt's folder>      one per model, in run order
 *   in <name> <int16 count>                               that part's inputs
 *   out <name> <int16 count>                              that part's outputs
 *   head <name>                                           graph outputs to write per clip
 * One part = a one-model build: tj_npu_open (its own environment, the SDK's one-model path). Several parts = a
 * chained build: ONE tj_npu_env_open environment and tj_npu_open_part per part (the SDK's chained path).
 *
 * PACK (the stream pack layout run_model reads): clips.txt (<clip> <chunks> <frames>), x/<clip>.raw (int16
 * [frames, bins]), masks/<set>/<name>.raw, consts/<name>.raw (optional), states.txt (<in> <out> <ratio>).
 *
 * The loop is the SDK's (NpuStreamer + NpuChainRunner): per clip states start at zero; chunk c feeds
 * x rows [c * shift, c * shift + window), mask set min(c, sets - 1), the constants and the states; the parts run
 * in order over one name-keyed pool (a part input must be in the pool with exactly its element count, else the
 * run stops: the SDK refuses where run_model would feed zeros); after the chunk every <out> state becomes <in>,
 * copied when the ratio is 1, else clamp(nearbyint(q * ratio)).
 * OUT/<clip>.raw = the logits of every chunk, OUT/<clip>.<head>.raw the other heads (run_model's
 * ZF_STREAM_OUTS layout), OUT/harness.log the buffer sizes and the time per chunk. */
#define _POSIX_C_SOURCE 200809L
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../litert_npu.h"

#define MAXP 32
#define MAXT 1024

typedef struct {
  char *name;
  int16_t *v;
  size_t n;
} Tensor;

typedef struct {
  char file[2304];
  int n_in, n_out;
  char *in[MAXT], *out[MAXT];
  size_t in_n[MAXT], out_n[MAXT];
  TjNpu *h;
} Part;

static Part parts[MAXP];
static int n_parts;
static char *heads[MAXT];
static int n_heads;
static int win, shift, maskc;
static Tensor pool[4 * MAXT];
static int n_pool;
static FILE *logf_;

static void die(const char *a, const char *b) {
  fprintf(stderr, "FAIL %s %s\n", a, b ? b : "");
  if (logf_) fprintf(logf_, "FAIL %s %s\n", a, b ? b : ""), fclose(logf_);
  exit(1);
}

static void *read_file(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  void *b = malloc(n > 0 ? (size_t)n : 1);
  if (!b || fread(b, 1, (size_t)n, f) != (size_t)n) die("read", path);
  fclose(f);
  *len = (size_t)n;
  return b;
}

static Tensor *find(const char *name) {
  for (int i = 0; i < n_pool; i++)
    if (strcmp(pool[i].name, name) == 0) return &pool[i];
  return NULL;
}

/* name := copy of v[0..n) */
static void put(const char *name, const int16_t *v, size_t n) {
  Tensor *t = find(name);
  if (!t) {
    if (n_pool >= 4 * MAXT) die("pool full", name);
    t = &pool[n_pool++];
    t->name = strdup(name);
    t->v = NULL;
    t->n = 0;
  }
  if (t->n != n) {
    free(t->v);
    t->v = (int16_t *)malloc(n ? n * 2 : 2);
    t->n = n;
  }
  memcpy(t->v, v, n * 2);
}

static void put_file(const char *name, const char *path, size_t want) {
  size_t len = 0;
  int16_t *b = (int16_t *)read_file(path, &len);
  if (!b) die("missing", path);
  if (len != want * 2) die("size", path);
  put(name, b, want);
  free(b);
}

static size_t count_of(const char *name) {
  for (int p = 0; p < n_parts; p++)
    for (int i = 0; i < parts[p].n_in; i++)
      if (strcmp(parts[p].in[i], name) == 0) return parts[p].in_n[i];
  return 0;
}

/* made by a part (an intermediate such as zf_keep2_r), not a graph input */
static int produced(const char *name) {
  for (int p = 0; p < n_parts; p++)
    for (int i = 0; i < parts[p].n_out; i++)
      if (strcmp(parts[p].out[i], name) == 0) return 1;
  return 0;
}

static double now_us(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1e6 + ts.tv_nsec / 1e3;
}

static void read_graph(const char *path) {
  char dir[1024];
  snprintf(dir, sizeof dir, "%s", path);
  char *slash = strrchr(dir, '/');
  if (slash) *slash = 0; else snprintf(dir, sizeof dir, ".");
  FILE *f = fopen(path, "r");
  if (!f) die("graph", path);
  char kind[16], name[1024];
  unsigned long n;
  while (fscanf(f, "%15s", kind) == 1) {
    if (!strcmp(kind, "geom")) {
      if (fscanf(f, "%d %d %d", &win, &shift, &maskc) != 3) die("graph", "geom");
    } else if (!strcmp(kind, "part")) {
      if (fscanf(f, "%1023s", name) != 1 || n_parts >= MAXP) die("graph", "part");
      snprintf(parts[n_parts++].file, sizeof parts[0].file, "%s/%s", dir, name);
    } else if (!strcmp(kind, "in") || !strcmp(kind, "out")) {
      if (fscanf(f, "%1023s %lu", name, &n) != 2 || !n_parts) die("graph", kind);
      Part *p = &parts[n_parts - 1];
      if (kind[0] == 'i') p->in[p->n_in] = strdup(name), p->in_n[p->n_in++] = n;
      else p->out[p->n_out] = strdup(name), p->out_n[p->n_out++] = n;
    } else if (!strcmp(kind, "head")) {
      if (fscanf(f, "%1023s", name) != 1) die("graph", "head");
      heads[n_heads++] = strdup(name);
    } else {
      die("graph line", kind);
    }
  }
  fclose(f);
  if (!n_parts || win <= 0 || shift <= 0 || maskc <= 0) die("graph", "incomplete");
}

int main(int argc, char **argv) {
  const char *graph = NULL, *pack = NULL, *out = NULL, *dispatch = NULL;
  int max_chunks = 1 << 30;
  for (int i = 1; i < argc; i++) {
    if (!strncmp(argv[i], "--graph=", 8)) graph = argv[i] + 8;
    else if (!strncmp(argv[i], "--pack=", 7)) pack = argv[i] + 7;
    else if (!strncmp(argv[i], "--out=", 6)) out = argv[i] + 6;
    else if (!strncmp(argv[i], "--dispatch_dir=", 15)) dispatch = argv[i] + 15;
    else if (!strncmp(argv[i], "--chunks=", 9)) max_chunks = atoi(argv[i] + 9);
    else die("argument", argv[i]);
  }
  if (!graph || !pack || !out) die("usage", "--graph= --pack= --out= [--dispatch_dir=] [--chunks=]");
  char path[2048];
  snprintf(path, sizeof path, "%s/harness.log", out);
  logf_ = fopen(path, "w");
  if (!logf_) die("log", path);
  read_graph(graph);

  /* open: one model on its own environment, a chain on one shared environment */
  char err[512] = {0};
  TjNpuEnv *env = NULL;
  if (n_parts > 1) {
    env = tj_npu_env_open(dispatch, err, sizeof err);
    if (!env) die("env", err);
  }
  for (int p = 0; p < n_parts; p++) {
    Part *q = &parts[p];
    q->h = n_parts > 1
               ? tj_npu_open_part(env, q->file, (const char *const *)q->in, q->n_in, (const char *const *)q->out, q->n_out, err, sizeof err)
               : tj_npu_open(q->file, dispatch, (const char *const *)q->in, q->n_in, (const char *const *)q->out, q->n_out, err, sizeof err);
    if (!q->h) die(q->file, err);
    int padded = 0;
    for (int i = 0; i < q->n_in; i++)
      if (tj_npu_input_bytes(q->h, i) != q->in_n[i] * 2) {
        padded++;
        fprintf(logf_, "part %d input %s: buffer %zu bytes, tensor %zu\n", p, q->in[i], tj_npu_input_bytes(q->h, i), q->in_n[i] * 2);
      }
    for (int i = 0; i < q->n_out; i++)
      if (tj_npu_output_bytes(q->h, i) != q->out_n[i] * 2) {
        padded++;
        fprintf(logf_, "part %d output %s: buffer %zu bytes, tensor %zu\n", p, q->out[i], tj_npu_output_bytes(q->h, i), q->out_n[i] * 2);
      }
    fprintf(logf_, "opened part %d %s: %d in, %d out, %d buffers padded\n", p, q->file, q->n_in, q->n_out, padded);
  }
  if (env) tj_npu_env_close(env); /* the parts hold it */
  fflush(logf_);

  /* the pack */
  snprintf(path, sizeof path, "%s/states.txt", pack);
  FILE *sf = fopen(path, "r");
  if (!sf) die("missing", path);
  char st_in[MAXT][256], st_out[MAXT][256];
  double st_r[MAXT];
  int n_st = 0;
  while (n_st < MAXT && fscanf(sf, "%255s %255s %lf", st_in[n_st], st_out[n_st], &st_r[n_st]) == 3) n_st++;
  fclose(sf);
  snprintf(path, sizeof path, "%s/clips.txt", pack);
  FILE *cf = fopen(path, "r");
  if (!cf) die("missing", path);
  char clip[256];
  int chunks, frames;
  double total = 0, best = 1e18;
  long runs = 0;
  while (fscanf(cf, "%255s %d %d", clip, &chunks, &frames) == 3) {
    for (int i = 0; i < n_pool; i++) free(pool[i].name), free(pool[i].v);
    n_pool = 0;
    /* constants: every part input with a consts/ file */
    for (int p = 0; p < n_parts; p++)
      for (int i = 0; i < parts[p].n_in; i++) {
        snprintf(path, sizeof path, "%s/consts/%s.raw", pack, parts[p].in[i]);
        FILE *t = produced(parts[p].in[i]) ? NULL : fopen(path, "rb");
        if (t) fclose(t), put_file(parts[p].in[i], path, parts[p].in_n[i]);
      }
    /* states at zero */
    for (int s = 0; s < n_st; s++) {
      size_t n = count_of(st_in[s]);
      if (!n) die("state not an input", st_in[s]);
      int16_t *z = (int16_t *)calloc(n, 2);
      put(st_in[s], z, n);
      free(z);
    }
    size_t xlen = 0;
    snprintf(path, sizeof path, "%s/x/%s.raw", pack, clip);
    int16_t *x = (int16_t *)read_file(path, &xlen);
    if (!x) die("missing", path);
    const size_t xn = count_of("x"), bins = xn / (size_t)win;
    if (!xn || xlen != (size_t)frames * bins * 2) die("x size", clip);
    FILE *hf[MAXT];
    for (int k = 0; k < n_heads; k++) {
      if (!strcmp(heads[k], "logits")) snprintf(path, sizeof path, "%s/%s.raw", out, clip);
      else snprintf(path, sizeof path, "%s/%s.%s.raw", out, clip, heads[k]);
      hf[k] = fopen(path, "wb");
      if (!hf[k]) die("write", path);
    }
    const int nc = chunks < max_chunks ? chunks : max_chunks;
    for (int c = 0; c < nc; c++) {
      put("x", x + (size_t)c * (size_t)shift * bins, xn);
      const int set = c < maskc - 1 ? c : maskc - 1;
      for (int p = 0; p < n_parts; p++)
        for (int i = 0; i < parts[p].n_in; i++)
          if ((!strncmp(parts[p].in[i], "zf_keep", 7) || !strncmp(parts[p].in[i], "zf_fill", 7)) && !produced(parts[p].in[i])) {
            snprintf(path, sizeof path, "%s/masks/%d/%s.raw", pack, set, parts[p].in[i]);
            put_file(parts[p].in[i], path, parts[p].in_n[i]);
          }
      double t0 = now_us();
      for (int p = 0; p < n_parts; p++) {
        Part *q = &parts[p];
        const int16_t *in[MAXT];
        size_t in_n[MAXT];
        int16_t *o[MAXT];
        for (int i = 0; i < q->n_in; i++) {
          Tensor *t = find(q->in[i]);
          if (!t || t->n != q->in_n[i]) die("no tensor (or wrong size) for input", q->in[i]);
          in[i] = t->v;
          in_n[i] = q->in_n[i];
        }
        for (int i = 0; i < q->n_out; i++) o[i] = (int16_t *)malloc(q->out_n[i] * 2);
        const char *why = tj_npu_run(q->h, in, in_n, o, q->out_n);
        if (why) die(q->file, why);
        for (int i = 0; i < q->n_out; i++) put(q->out[i], o[i], q->out_n[i]), free(o[i]);
      }
      double dt = now_us() - t0;
      total += dt;
      if (dt < best) best = dt;
      runs++;
      for (int k = 0; k < n_heads; k++) {
        Tensor *t = find(heads[k]);
        if (!t) die("no head", heads[k]);
        fwrite(t->v, 2, t->n, hf[k]);
      }
      for (int s = 0; s < n_st; s++) {
        Tensor *t = find(st_out[s]);
        if (!t) die("no state output", st_out[s]);
        int16_t *v = (int16_t *)malloc(t->n * 2);
        for (size_t k = 0; k < t->n; k++) {
          if (st_r[s] == 1.0) { v[k] = t->v[k]; continue; }
          double q = nearbyint((double)t->v[k] * st_r[s]);
          v[k] = (int16_t)(q < -32768.0 ? -32768.0 : q > 32767.0 ? 32767.0 : q);
        }
        put(st_in[s], v, t->n);
        free(v);
      }
    }
    for (int k = 0; k < n_heads; k++) fclose(hf[k]);
    free(x);
    fprintf(logf_, "clip %s %d chunks\n", clip, nc);
    fflush(logf_);
  }
  fclose(cf);
  fprintf(logf_, "Harness: %ld chunks, mean %.0f us, fastest %.0f us (all parts)\n", runs, runs ? total / runs : 0, best);
  for (int p = 0; p < n_parts; p++) tj_npu_free(parts[p].h);
  fprintf(logf_, "HARNESS DONE\n");
  fclose(logf_);
  return 0;
}
