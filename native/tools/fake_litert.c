/* A stand-in libLiteRt.so for checking litert_npu.c and the harness on a Linux host (no NPU): the C API calls
 * the runner makes, over "models" that are text files
 *
 *   in <name> <int16 count> [pad bytes]      signature inputs, in signature order
 *   out <name> <int16 count> [pad bytes]     signature outputs, in signature order
 *
 * Run: output j, element e = ((sum over signature inputs i of (i + 1) * in_i[e % n_i]) * 31 + 7 j + e) mod 65536,
 * as int16 (a host check computes the same in Python). A buffer can be padded (packed size = 2 n + pad); Run
 * fails if an input's padding is not zero. The environment checks its options and fails the process if it is
 * destroyed while a compiled model made in it is still alive; FAKE_LITERT_LOG names a file that gets one line
 * per environment, options and model call.
 *
 * Replay (FAKE_LITERT_REPLAY=DIR): a model whose file name F has DIR/F/index.txt answers from recordings instead.
 * Each line is "<key> <blob>", the key over the inputs' int16 bytes concatenated in signature order (padding
 * excluded): their SHA-256 in hex, or "crc32:<zlib CRC-32, 8 hex>:<byte count>" (cheap enough that a slow phone's
 * self-test can time the stand-in against its CPU). The blob DIR/F/<blob> holds every output's bytes in signature
 * order. An input set that was not recorded fails the run (status 3), so a replayed run shows the runner fed the
 * recorded bytes.
 *
 * Failure (FAKE_LITERT_FAIL="<substring of the model path>:<k>"): the k-th run (from 0) of a matching model,
 * counted from when this value was first seen, fails (status 4). Read at every run.
 *
 *   gcc -shared -fPIC -O2 -o libLiteRt.so fake_litert.c */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  int type;
  union {
    _Bool b;
    int64_t i;
    double r;
    const char *s;
    const void *p;
  };
} Any;
typedef struct {
  int tag;
  Any value;
} EnvOption;

typedef struct {
  int live; /* compiled models alive */
  int auto_register;
  char dispatch[512];
} Env;
typedef struct {
  char hash[65];
  char blob[64];
} Rec;
typedef struct {
  int n_in, n_out;
  char in[1024][160], out[1024][160];
  size_t in_n[1024], out_n[1024], in_pad[1024], out_pad[1024];
  char path[1024];
  char replay_dir[1024];
  Rec *recs;
  int n_recs;
} Model;

/* SHA-256 (FIPS 180-4) */
typedef struct {
  uint32_t h[8];
  uint64_t len;
  unsigned char buf[64];
  size_t n;
} Sha;
static const uint32_t K256[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
    0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
    0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))
static void sha_block(Sha *s, const unsigned char *p) {
  uint32_t w[64], a, b, c, d, e, f, g, h;
  for (int i = 0; i < 16; i++) w[i] = (uint32_t)p[4 * i] << 24 | (uint32_t)p[4 * i + 1] << 16 | (uint32_t)p[4 * i + 2] << 8 | p[4 * i + 3];
  for (int i = 16; i < 64; i++) {
    uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
    uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3], e = s->h[4], f = s->h[5], g = s->h[6], h = s->h[7];
  for (int i = 0; i < 64; i++) {
    uint32_t t1 = h + (ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + w[i];
    uint32_t t2 = (ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
    h = g, g = f, f = e, e = d + t1, d = c, c = b, b = a, a = t1 + t2;
  }
  s->h[0] += a, s->h[1] += b, s->h[2] += c, s->h[3] += d, s->h[4] += e, s->h[5] += f, s->h[6] += g, s->h[7] += h;
}
static void sha_init(Sha *s) {
  static const uint32_t h0[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
  memcpy(s->h, h0, sizeof h0);
  s->len = 0;
  s->n = 0;
}
static void sha_add(Sha *s, const unsigned char *p, size_t n) {
  s->len += n;
  while (n) {
    size_t k = 64 - s->n < n ? 64 - s->n : n;
    memcpy(s->buf + s->n, p, k);
    s->n += k, p += k, n -= k;
    if (s->n == 64) sha_block(s, s->buf), s->n = 0;
  }
}
static void sha_hex(Sha *s, char *out) {
  uint64_t bits = s->len * 8;
  unsigned char pad = 0x80, z = 0, l[8];
  sha_add(s, &pad, 1);
  while (s->n != 56) sha_add(s, &z, 1);
  for (int i = 0; i < 8; i++) l[i] = (unsigned char)(bits >> (56 - 8 * i));
  sha_add(s, l, 8);
  for (int i = 0; i < 8; i++) sprintf(out + 8 * i, "%08x", (unsigned)s->h[i]);
}
typedef struct {
  Env *env;
  Model *m;
} Compiled;
typedef struct {
  size_t bytes;
} Req;
typedef struct {
  unsigned char *d;
  size_t bytes;
} Buf;
typedef struct {
  Model *m;
  int is_in;
  int k;
} Tensor;

static FILE *lg(void) {
  static FILE *f;
  static int tried;
  if (!tried) {
    tried = 1;
    const char *p = getenv("FAKE_LITERT_LOG");
    if (p) f = fopen(p, "a");
  }
  return f;
}
#define LOG(...) do { FILE *f_ = lg(); if (f_) { fprintf(f_, __VA_ARGS__); fputc('\n', f_); fflush(f_); } } while (0)

int LiteRtCreateEnvironment(int n, const EnvOption *o, Env **out) {
  Env *e = calloc(1, sizeof(Env));
  e->auto_register = -1;
  for (int i = 0; i < n; i++) {
    if (o[i].tag == 24 && o[i].value.type == 2) e->auto_register = (int)o[i].value.i;
    else if (o[i].tag == 1 && o[i].value.type == 8) snprintf(e->dispatch, sizeof e->dispatch, "%s", o[i].value.s);
    else return 1;
  }
  LOG("env create %p options %d auto_register %d dispatch %s", (void *)e, n, e->auto_register, e->dispatch);
  *out = e;
  return 0;
}
void LiteRtDestroyEnvironment(Env *e) {
  LOG("env destroy %p live %d", (void *)e, e->live);
  if (e->live) {
    fprintf(stderr, "fake LiteRT: environment destroyed with %d compiled models alive\n", e->live);
    abort();
  }
  free(e);
}
int LiteRtCreateModelFromFile(Env *e, const char *path, Model **out) {
  (void)e;
  FILE *f = fopen(path, "r");
  if (!f) return 1;
  Model *m = calloc(1, sizeof(Model));
  char line[512], kind[8], name[160];
  unsigned long n, pad;
  while (fgets(line, sizeof line, f)) {
    pad = 0;
    int got = sscanf(line, "%7s %159s %lu %lu", kind, name, &n, &pad);
    if (got < 3) continue;
    if (!strcmp(kind, "in")) strcpy(m->in[m->n_in], name), m->in_n[m->n_in] = n, m->in_pad[m->n_in++] = pad;
    else strcpy(m->out[m->n_out], name), m->out_n[m->n_out] = n, m->out_pad[m->n_out++] = pad;
  }
  fclose(f);
  snprintf(m->path, sizeof m->path, "%s", path);
  const char *rd = getenv("FAKE_LITERT_REPLAY");
  if (rd && *rd) {
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    snprintf(m->replay_dir, sizeof m->replay_dir, "%s/%s", rd, base);
    char idx[1200];
    snprintf(idx, sizeof idx, "%s/index.txt", m->replay_dir);
    FILE *x = fopen(idx, "r");
    if (x) {
      int cap = 64;
      m->recs = malloc(sizeof(Rec) * cap);
      while (fscanf(x, "%64s %63s", m->recs[m->n_recs].hash, m->recs[m->n_recs].blob) == 2)
        if (++m->n_recs == cap) m->recs = realloc(m->recs, sizeof(Rec) * (cap *= 2));
      fclose(x);
    } else {
      m->replay_dir[0] = 0;
    }
  }
  LOG("model %s: %d in, %d out, %d recordings", path, m->n_in, m->n_out, m->n_recs);
  *out = m;
  return 0;
}
void LiteRtDestroyModel(Model *m) {
  free(m->recs);
  free(m);
}
int LiteRtCreateOptions(int **o) {
  *o = calloc(1, sizeof(int));
  LOG("options create %p", (void *)*o);
  return 0;
}
int LiteRtSetOptionsHardwareAccelerators(int *o, int hw) {
  *o = hw;
  LOG("options %p hw %d", (void *)o, hw);
  return 0;
}
void LiteRtDestroyOptions(int *o) {
  LOG("options destroy %p", (void *)o);
  free(o);
}
int LiteRtCreateCompiledModel(Env *e, Model *m, int *o, Compiled **out) {
  if (!o || *o != 5) return 1; /* CPU | NPU */
  Compiled *c = calloc(1, sizeof(Compiled));
  c->env = e;
  c->m = m;
  e->live++;
  *out = c;
  return 0;
}
void LiteRtDestroyCompiledModel(Compiled *c) {
  c->env->live--;
  free(c);
}
int LiteRtGetModelSignature(Model *m, size_t i, Model **s) {
  if (i) return 1;
  *s = m;
  return 0;
}
int LiteRtGetNumSignatureInputs(Model *s, size_t *n) { *n = (size_t)s->n_in; return 0; }
int LiteRtGetNumSignatureOutputs(Model *s, size_t *n) { *n = (size_t)s->n_out; return 0; }
int LiteRtGetSignatureInputName(Model *s, size_t k, const char **n) { *n = s->in[k]; return 0; }
int LiteRtGetSignatureOutputName(Model *s, size_t k, const char **n) { *n = s->out[k]; return 0; }
static Tensor *tensor(Model *m, int is_in, size_t k) {
  Tensor *t = calloc(1, sizeof(Tensor)); /* leaked: a test library */
  t->m = m;
  t->is_in = is_in;
  t->k = (int)k;
  return t;
}
int LiteRtGetSignatureInputTensorByIndex(Model *s, size_t k, Tensor **t) { *t = tensor(s, 1, k); return 0; }
int LiteRtGetSignatureOutputTensorByIndex(Model *s, size_t k, Tensor **t) { *t = tensor(s, 0, k); return 0; }
int LiteRtGetRankedTensorType(Tensor *t, unsigned char *type) {
  memset(type, 0, 72);
  type[0] = 9; /* int16 */
  uint32_t rank = 1;
  int32_t n = (int32_t)(t->is_in ? t->m->in_n[t->k] : t->m->out_n[t->k]);
  memcpy(type + 4, &rank, 4);
  memcpy(type + 8, &n, 4);
  return 0;
}
static int layout(Model *m, int is_in, size_t k, unsigned char *l) {
  memset(l, 0, 68);
  uint32_t rank = 1 | (1u << 7); /* rank 1, has_strides: tells this layout from the model's */
  int32_t n = (int32_t)(is_in ? m->in_n[k] : m->out_n[k]);
  memcpy(l, &rank, 4);
  memcpy(l + 4, &n, 4);
  return 0;
}
int LiteRtGetCompiledModelInputTensorLayout(Compiled *c, size_t s, size_t k, unsigned char *l) {
  (void)s;
  return layout(c->m, 1, k, l);
}
int LiteRtGetCompiledModelOutputTensorLayouts(Compiled *c, size_t s, size_t n, unsigned char *l, _Bool upd) {
  (void)s;
  (void)upd;
  if ((int)n != c->m->n_out) return 1;
  for (size_t k = 0; k < n; k++) layout(c->m, 0, k, l + 68 * k);
  return 0;
}
static int reqs(Model *m, int is_in, size_t k, Req **r) {
  *r = calloc(1, sizeof(Req));
  (*r)->bytes = is_in ? m->in_n[k] * 2 + m->in_pad[k] : m->out_n[k] * 2 + m->out_pad[k];
  return 0;
}
int LiteRtGetCompiledModelInputBufferRequirements(Compiled *c, size_t s, size_t k, Req **r) { (void)s; return reqs(c->m, 1, k, r); }
int LiteRtGetCompiledModelOutputBufferRequirements(Compiled *c, size_t s, size_t k, Req **r) { (void)s; return reqs(c->m, 0, k, r); }
int LiteRtCreateManagedTensorBufferFromRequirements(Env *e, const unsigned char *type, Req *r, Buf **b) {
  (void)e;
  (void)type;
  *b = calloc(1, sizeof(Buf));
  (*b)->bytes = r->bytes;
  (*b)->d = calloc(1, r->bytes ? r->bytes : 1);
  return 0;
}
int LiteRtLockTensorBuffer(Buf *b, void **p, int mode) { (void)mode; *p = b->d; return 0; }
int LiteRtUnlockTensorBuffer(Buf *b) { (void)b; return 0; }
int LiteRtGetTensorBufferPackedSize(Buf *b, size_t *n) { *n = b->bytes; return 0; }
void LiteRtDestroyTensorBuffer(Buf *b) { free(b->d); free(b); }
int LiteRtRunCompiledModel(Compiled *c, size_t s, size_t n_in, Buf **in, size_t n_out, Buf **out) {
  Model *m = c->m;
  if (s || (int)n_in != m->n_in || (int)n_out != m->n_out) return 1;
  for (int i = 0; i < m->n_in; i++)
    for (size_t k = m->in_n[i] * 2; k < in[i]->bytes; k++)
      if (in[i]->d[k]) return 2; /* padding must be zero */
  {
    static char last[256];
    static long seen;
    const char *f = getenv("FAKE_LITERT_FAIL");
    if (!f) f = "";
    if (strcmp(f, last) != 0) snprintf(last, sizeof last, "%s", f), seen = 0;
    const char *colon = strrchr(f, ':');
    if (colon && colon > f) {
      char sub[256];
      snprintf(sub, sizeof sub, "%.*s", (int)(colon - f), f);
      if (strstr(m->path, sub) && seen++ == atol(colon + 1)) {
        LOG("FAIL injected %s", m->path);
        return 4;
      }
    }
  }
  if (m->replay_dir[0]) {
    char hex[65];
    if (m->n_recs && !strncmp(m->recs[0].hash, "crc32:", 6)) {
      /* the cheap key (a phone's self-test times the stand-in): zlib CRC-32 of the same bytes + their length */
      static uint32_t table[256];
      if (!table[1])
        for (uint32_t i = 0; i < 256; i++) {
          uint32_t c = i;
          for (int k = 0; k < 8; k++) c = c & 1 ? 0xedb88320u ^ (c >> 1) : c >> 1;
          table[i] = c;
        }
      uint32_t crc = 0xffffffffu;
      size_t len = 0;
      for (int i = 0; i < m->n_in; i++) {
        const unsigned char *p = in[i]->d;
        for (size_t k = 0, n = m->in_n[i] * 2; k < n; k++) crc = table[(crc ^ p[k]) & 0xff] ^ (crc >> 8);
        len += m->in_n[i] * 2;
      }
      snprintf(hex, sizeof hex, "crc32:%08x:%zu", (unsigned)(crc ^ 0xffffffffu), len);
    } else {
      Sha sh;
      sha_init(&sh);
      for (int i = 0; i < m->n_in; i++) sha_add(&sh, in[i]->d, m->in_n[i] * 2);
      sha_hex(&sh, hex);
    }
    for (int r = 0; r < m->n_recs; r++) {
      if (strcmp(m->recs[r].hash, hex) != 0) continue;
      char p[1200];
      snprintf(p, sizeof p, "%s/%s", m->replay_dir, m->recs[r].blob);
      FILE *b = fopen(p, "rb");
      if (!b) return 5;
      int ok = 1;
      for (int j = 0; j < m->n_out && ok; j++) ok = fread(out[j]->d, 2, m->out_n[j], b) == m->out_n[j];
      ok = ok && fgetc(b) == EOF;
      fclose(b);
      LOG("replay %s %s", m->path, ok ? "hit" : "BAD BLOB");
      return ok ? 0 : 5;
    }
    LOG("replay MISS %s %s", m->path, hex);
    return 3;
  }
  for (int j = 0; j < m->n_out; j++) {
    int16_t *o = (int16_t *)out[j]->d;
    for (size_t e = 0; e < m->out_n[j]; e++) {
      int64_t acc = 0;
      for (int i = 0; i < m->n_in; i++) acc += (int64_t)(i + 1) * ((const int16_t *)in[i]->d)[e % m->in_n[i]];
      o[e] = (int16_t)(uint16_t)(((uint64_t)(acc * 31 + 7 * j + (int64_t)e)) & 0xffff);
    }
  }
  return 0;
}
