/* libtajweedformer_jni (SDK 4.0): the bridge from the SDK's Kotlin to the Quran Lab Core, the closed library that holds
 * the engine, the encrypted model's loader and the lease (quranlab-contract sdk_4.0.0/PROTECTED_MODEL.md 18.5).
 *
 * The Core ships as its own file (libqlcore.so in the AAR, built and stripped by the Core's release tool): its
 * integrity digest is computed over that file as mapped, so it is never linked into this library. This library opens
 * it at run time (dlopen, or LoadLibrary for the desktop JVM's tests) and calls the qlc_ functions of qlcore.h through
 * a table filled with dlsym. It exports JNI_OnLoad only: the natives are registered on CoreNative by name.
 *
 * What the Core asks the platform for (qlc_platform) is answered here or in Kotlin:
 *   - storage by form and decoded path: here, with POSIX calls under four folders the SDK names (plain, CDN form,
 *     device form, the Core's own files); a write goes to "<file>.part" and commit renames it into place;
 *   - the clocks (CLOCK_REALTIME, CLOCK_BOOTTIME and the boot's id the SDK passes) and randomness: here;
 *   - the install key store (ECDH agreement or RSA-OAEP unwrapping of one lease's outer wrap, ECDSA signing), the
 *     saved state, its seal by a device-bound key, the monotonic counter, and a stop's removal: in Kotlin
 *     (CoreHost), called back through JNI.
 * Nothing of a model's plaintext crosses into Kotlin: the calls give status codes, opaque blobs, public data
 * (calibration.json) and the network's outputs. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#include <windows.h>
#include <bcrypt.h>
#define TJ_EXPORT __declspec(dllexport)
#else
#include <dlfcn.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>
#define TJ_EXPORT __attribute__((visibility("default")))
#endif

#include "qlcore.h"

#define CLASS "ai/quranlab/tajweedformer/core/CoreNative"

/* ------------------------------------------------------------------ the Core, opened at run time */
static struct {
  int bound;
  const char *(*version)(void);
  const char *(*notice)(void);
  qlc_core *(*create)(const qlc_platform *, const uint8_t *, size_t, int *);
  void (*destroy)(qlc_core *);
  int (*detail)(const qlc_core *);
  int (*lease_ask)(qlc_core *, const char *, const char *, char *, size_t, char *, size_t);
  int (*attest_sig)(qlc_core *, const char *, char *, size_t);
  int (*lease_accept)(qlc_core *, const uint8_t *, size_t, const char *, size_t, const char *, int64_t *, int64_t *);
  int (*refusal_accept)(qlc_core *, const char *, size_t, const char *);
  int (*lease_piece)(qlc_core *, const uint8_t *, size_t);
  int (*lease_attempt)(qlc_core *, int);
  int (*lease_grace)(qlc_core *, int64_t *);
  int (*tick)(qlc_core *, qlc_lease_status *);
  int (*usage)(qlc_core *, int64_t *, double *, int64_t *, double *);
  int (*install)(qlc_core *, const char *, size_t, const char *);
  qlc_open_options (*open_default_options)(void);
  qlc_model *(*model_open)(qlc_core *, const char *, size_t, const qlc_open_options *, int *);
  void (*model_close)(qlc_model *);
  const void *(*calibration)(const qlc_model *, size_t *);
  int (*model_chunk_ms)(const qlc_model *);
  qlc_stream *(*stream_create)(qlc_model *, int *);
  void (*stream_destroy)(qlc_stream *);
  int (*stream_set_heads)(qlc_stream *, unsigned);
  void (*stream_reset)(qlc_stream *);
  int (*stream_push)(qlc_stream *, const float *, int);
  int (*stream_finish)(qlc_stream *);
  int (*stream_heads)(const qlc_stream *, qlc_heads *);
  int (*recited_index)(qlc_core *, const char *, size_t);
  int (*recited_begin)(qlc_core *, uint64_t);
  int (*recited_open)(qlc_core *, uint64_t *);
  int (*recited_feed)(qlc_core *, const int *, int);
  int (*recited_feed_stream)(qlc_core *, const qlc_stream *);
  int (*recited_finish)(qlc_core *, uint64_t, unsigned, qlc_recited_result *);
  int (*recited_totals)(qlc_core *, int64_t *, int *, int *, int64_t *, int *, int *);
  int (*recited_done)(qlc_core *, int, uint64_t *, qlc_recited_result *);
  int (*recited_reported)(qlc_core *, uint64_t);
  int (*reference_expected)(const qlc_model *, int, int, int *, int, int *, int, int *);
  int (*reference_ayah_count)(const qlc_model *, int);
  int (*model_token_count)(const qlc_model *);
  const char *(*model_token_name)(const qlc_model *, int);
  int (*days_mark)(qlc_core *, int64_t, int64_t);
  int (*days_judged)(qlc_core *, int64_t, int, int);
  int (*days_seal)(qlc_core *, int64_t);
  int (*days_trend_sent)(qlc_core *, int64_t);
  int (*days_read)(qlc_core *, qlc_days *);
  int (*accel_prepare)(qlc_model *, const char *, const char *, char *, size_t);
  int (*accel_release)(qlc_model *);
  int (*accel_allowed)(qlc_core *);
  qlc_stream *(*stream_create_on)(qlc_model *, int, int *);
  int (*stream_backend)(const qlc_stream *);
} Q;

static void *sym(void *lib, const char *name) {
#ifdef _WIN32
  return (void *)GetProcAddress((HMODULE)lib, name);
#else
  return dlsym(lib, name);
#endif
}

/* 0: bound; 1: the library did not load; 2: a function is missing (another Core interface) */
static int bind_core(const char *path) {
  if (Q.bound) return 0;
#ifdef _WIN32
  void *lib = (void *)LoadLibraryA(path);
#else
  void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
#endif
  if (!lib) return 1;
#define B(field, name)                                  \
  do {                                                  \
    *(void **)(&Q.field) = sym(lib, name);              \
    if (!Q.field) return 2;                             \
  } while (0)
  B(version, "qlc_version");
  B(notice, "qlc_notice");
  B(create, "qlc_create");
  B(destroy, "qlc_destroy");
  B(detail, "qlc_detail");
  B(lease_ask, "qlc_lease_ask");
  B(attest_sig, "qlc_attest_sig");
  B(lease_accept, "qlc_lease_accept");
  B(refusal_accept, "qlc_refusal_accept");
  B(lease_piece, "qlc_lease_piece");
  B(lease_attempt, "qlc_lease_attempt");
  B(lease_grace, "qlc_lease_grace");
  B(tick, "qlc_tick");
  B(usage, "qlc_usage");
  B(install, "qlc_install");
  B(open_default_options, "qlc_open_default_options");
  B(model_open, "qlc_model_open");
  B(model_close, "qlc_model_close");
  B(calibration, "qlc_calibration");
  B(model_chunk_ms, "qlc_model_chunk_ms");
  B(stream_create, "qlc_stream_create");
  B(stream_destroy, "qlc_stream_destroy");
  B(stream_set_heads, "qlc_stream_set_heads");
  B(stream_reset, "qlc_stream_reset");
  B(stream_push, "qlc_stream_push");
  B(stream_finish, "qlc_stream_finish");
  B(stream_heads, "qlc_stream_heads");
  B(recited_index, "qlc_recited_index");
  B(recited_begin, "qlc_recited_begin");
  B(recited_open, "qlc_recited_open");
  B(recited_feed, "qlc_recited_feed");
  B(recited_feed_stream, "qlc_recited_feed_stream");
  B(recited_finish, "qlc_recited_finish");
  B(recited_totals, "qlc_recited_totals");
  B(recited_done, "qlc_recited_done");
  B(recited_reported, "qlc_recited_reported");
  B(reference_expected, "qlc_reference_expected");
  B(reference_ayah_count, "qlc_reference_ayah_count");
  B(model_token_count, "qlc_model_token_count");
  B(model_token_name, "qlc_model_token_name");
  B(days_mark, "qlc_days_mark");
  B(days_judged, "qlc_days_judged");
  B(days_seal, "qlc_days_seal");
  B(days_trend_sent, "qlc_days_trend_sent");
  B(days_read, "qlc_days_read");
  B(accel_prepare, "qlc_accel_prepare");
  B(accel_release, "qlc_accel_release");
  B(accel_allowed, "qlc_accel_allowed");
  B(stream_create_on, "qlc_stream_create_on");
  B(stream_backend, "qlc_stream_backend");
#undef B
  Q.bound = 1;
  return 0;
}

/* ------------------------------------------------------------------ the JVM, from any thread the Core calls on */
static JavaVM *g_vm;
#ifndef _WIN32
static pthread_key_t g_detach_key;
static void detach_thread(void *unused) {
  (void)unused;
  if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}
#endif

/* The thread's JNIEnv; a thread the Core started (its decryption pool) is attached once and detached when it ends. */
static JNIEnv *env_now(void) {
  JNIEnv *env = NULL;
  if (!g_vm) return NULL;
  if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
#ifdef __ANDROID__
  if ((*g_vm)->AttachCurrentThreadAsDaemon(g_vm, &env, NULL) != JNI_OK) return NULL;
#else
  if ((*g_vm)->AttachCurrentThreadAsDaemon(g_vm, (void **)&env, NULL) != JNI_OK) return NULL;
#endif
#ifndef _WIN32
  pthread_setspecific(g_detach_key, (void *)1);
#endif
  return env;
}

/* ------------------------------------------------------------------ one Core handle and its platform table */
enum { ROOT_PLAIN = 0, ROOT_CDN = 1, ROOT_DEVICE = 2, ROOT_CORE = 3, ROOTS = 4 };
#define FLAG_RSA 1u         /* the agreement key is RSA-2048 (OAEP): decrypt_rsa instead of agree */
#define FLAG_HOST_CLOCKS 2u /* the tests' clocks: wall and boot time from Kotlin */
#define FLAG_NO_COUNTER 4u  /* no monotonic counter */
#define FLAG_NO_SEAL 8u     /* no device-bound key for the saved state */

typedef struct {
  qlc_platform p;
  qlc_core *core;
  jobject host; /* global ref: CoreHost */
  jmethodID agree, decrypt_rsa, sign, save_state, state_seal, state_open, counter_get, counter_raise, remove_all, wall_ms, boot_ms;
  uint8_t *spki;
  size_t spki_len;
  uint8_t boot_id[32];
  char *roots[ROOTS];
#ifdef _WIN32
  CRITICAL_SECTION roots_lock;
#else
  pthread_mutex_t roots_lock;
#endif
} Host;

static void roots_lock(Host *h) {
#ifdef _WIN32
  EnterCriticalSection(&h->roots_lock);
#else
  pthread_mutex_lock(&h->roots_lock);
#endif
}
static void roots_unlock(Host *h) {
#ifdef _WIN32
  LeaveCriticalSection(&h->roots_lock);
#else
  pthread_mutex_unlock(&h->roots_lock);
#endif
}

/* A decoded path the Core names: relative, '/'-separated, no empty, "." or ".." part, no backslash or colon. */
static int safe_path(const char *p) {
  if (!p || !*p || *p == '/' || strchr(p, '\\') || strchr(p, ':') || strlen(p) > 900) return 0;
  const char *s = p;
  while (*s) {
    const char *e = strchr(s, '/');
    const size_t n = e ? (size_t)(e - s) : strlen(s);
    if (n == 0 || (n == 1 && s[0] == '.') || (n == 2 && s[0] == '.' && s[1] == '.')) return 0;
    if (!e) break;
    s = e + 1;
  }
  return 1;
}

/* "<root of form>/<path><suffix>" into out; 0 on success */
static int full_path(Host *h, int form, const char *path, const char *suffix, char *out, size_t cap) {
  if (form < 0 || form >= ROOTS || !safe_path(path)) return -1;
  roots_lock(h);
  const char *root = h->roots[form];
  int n = root ? snprintf(out, cap, "%s/%s%s", root, path, suffix ? suffix : "") : -1;
  roots_unlock(h);
  return n > 0 && (size_t)n < cap ? 0 : -1;
}

/* the folders of a file's path, made as needed */
static int make_parents(char *path) {
  for (char *s = path + 1; *s; ++s) {
    if (*s != '/') continue;
    *s = 0;
#ifdef _WIN32
    const int r = _mkdir(path);
#else
    const int r = mkdir(path, 0700);
#endif
    *s = '/';
    if (r && errno != EEXIST) return -1;
  }
  return 0;
}

#ifdef _WIN32
#define O_FLAGS_BIN O_BINARY
static int64_t file_size(const char *p) {
  struct _stat64 st;
  return _stat64(p, &st) == 0 ? (int64_t)st.st_size : -1;
}
static int pread_all(int fd, void *buf, size_t len, uint64_t off) {
  if (_lseeki64(fd, (int64_t)off, SEEK_SET) < 0) return -1;
  uint8_t *b = (uint8_t *)buf;
  while (len) {
    const int n = _read(fd, b, (unsigned)(len > (1u << 30) ? (1u << 30) : len));
    if (n <= 0) return -1;
    b += n;
    len -= (size_t)n;
  }
  return 0;
}
static int pwrite_all(int fd, const void *buf, size_t len, uint64_t off) {
  if (_lseeki64(fd, (int64_t)off, SEEK_SET) < 0) return -1;
  const uint8_t *b = (const uint8_t *)buf;
  while (len) {
    const int n = _write(fd, b, (unsigned)(len > (1u << 30) ? (1u << 30) : len));
    if (n <= 0) return -1;
    b += n;
    len -= (size_t)n;
  }
  return 0;
}
#define sys_open _open
#define sys_close _close
static int sys_fsync(int fd) { return _commit(fd); }
static int replace_file(const char *from, const char *to) { return MoveFileExA(from, to, MOVEFILE_REPLACE_EXISTING) ? 0 : -1; }
#else
#define O_FLAGS_BIN 0
static int64_t file_size(const char *p) {
  struct stat st;
  return stat(p, &st) == 0 && S_ISREG(st.st_mode) ? (int64_t)st.st_size : -1;
}
static int pread_all(int fd, void *buf, size_t len, uint64_t off) {
  uint8_t *b = (uint8_t *)buf;
  while (len) {
    const ssize_t n = pread(fd, b, len, (off_t)off);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    b += n;
    len -= (size_t)n;
    off += (uint64_t)n;
  }
  return 0;
}
static int pwrite_all(int fd, const void *buf, size_t len, uint64_t off) {
  const uint8_t *b = (const uint8_t *)buf;
  while (len) {
    const ssize_t n = pwrite(fd, b, len, (off_t)off);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    b += n;
    len -= (size_t)n;
    off += (uint64_t)n;
  }
  return 0;
}
#define sys_open open
#define sys_close close
static int sys_fsync(int fd) { return fsync(fd); }
static int replace_file(const char *from, const char *to) { return rename(from, to); }
#endif

static int64_t cb_size(void *u, int form, const char *path) {
  char f[1100];
  return full_path((Host *)u, form, path, NULL, f, sizeof f) ? -1 : file_size(f);
}

static int cb_read(void *u, int form, const char *path, uint64_t offset, void *buf, size_t len) {
  char f[1100];
  if (full_path((Host *)u, form, path, NULL, f, sizeof f)) return -1;
  const int fd = sys_open(f, O_RDONLY | O_FLAGS_BIN);
  if (fd < 0) return -1;
  const int r = pread_all(fd, buf, len, offset);
  sys_close(fd);
  return r;
}

/* A write goes to "<file>.part"; the first write (offset 0) starts it over. */
static int cb_write(void *u, int form, const char *path, uint64_t offset, const void *buf, size_t len) {
  char f[1100];
  if ((form != QLC_FORM_DEVICE && form != QLC_FORM_CORE) || full_path((Host *)u, form, path, ".part", f, sizeof f)) return -1;
  if (make_parents(f)) return -1;
  const int fd = sys_open(f, O_WRONLY | O_CREAT | O_FLAGS_BIN | (offset == 0 ? O_TRUNC : 0), 0600);
  if (fd < 0) return -1;
  const int r = pwrite_all(fd, buf, len, offset);
  sys_close(fd);
  return r;
}

/* The file is whole: flushed, then renamed over the old one (one atomic step). */
static int cb_commit(void *u, int form, const char *path) {
  char part[1100], f[1100];
  Host *h = (Host *)u;
  if (full_path(h, form, path, ".part", part, sizeof part) || full_path(h, form, path, NULL, f, sizeof f)) return -1;
  const int fd = sys_open(part, O_RDWR | O_FLAGS_BIN);
  if (fd < 0) return -1;
  const int synced = sys_fsync(fd);
  sys_close(fd);
  if (synced) return -1;
  return replace_file(part, f);
}

static int cb_remove(void *u, int form, const char *path) {
  Host *h = (Host *)u;
  if (!path) {
    /* every device-form file and partial write (a verified stop): the SDK removes the models whole */
    JNIEnv *env = env_now();
    if (!env) return -1;
    const jboolean ok = (*env)->CallBooleanMethod(env, h->host, h->remove_all, (jint)form);
    if ((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionClear(env);
      return -1;
    }
    return ok ? 0 : -1;
  }
  char part[1100], f[1100];
  if (full_path(h, form, path, ".part", part, sizeof part) || full_path(h, form, path, NULL, f, sizeof f)) return -1;
  remove(part);
  return remove(f) == 0 || file_size(f) < 0 ? 0 : -1;
}

/* A read-only mapping of a stored file (qlcore.h: never a copy; the Core decrypts the device forms from it). */
#ifdef _WIN32
static int cb_map(void *u, int form, const char *path, const void **data, size_t *len) {
  char f[1100];
  wchar_t w[1100];
  if (full_path((Host *)u, form, path, NULL, f, sizeof f)) return -1;
  if (!MultiByteToWideChar(CP_UTF8, 0, f, -1, w, (int)(sizeof w / sizeof w[0]))) return -1;
  HANDLE fh = CreateFileW(w, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
  if (fh == INVALID_HANDLE_VALUE) return -1;
  LARGE_INTEGER sz;
  if (!GetFileSizeEx(fh, &sz) || sz.QuadPart <= 0) {
    CloseHandle(fh);
    return -1;
  }
  HANDLE mh = CreateFileMappingW(fh, NULL, PAGE_READONLY, 0, 0, NULL);
  CloseHandle(fh);
  if (!mh) return -1;
  void *v = MapViewOfFile(mh, FILE_MAP_READ, 0, 0, 0);
  CloseHandle(mh); /* the view keeps the mapping */
  if (!v) return -1;
  *data = v;
  *len = (size_t)sz.QuadPart;
  return 0;
}
static void cb_unmap(void *u, const void *data, size_t len) {
  (void)u;
  (void)len;
  UnmapViewOfFile(data);
}
#else
static int cb_map(void *u, int form, const char *path, const void **data, size_t *len) {
  char f[1100];
  if (full_path((Host *)u, form, path, NULL, f, sizeof f)) return -1;
  const int fd = open(f, O_RDONLY);
  if (fd < 0) return -1;
  struct stat st;
  if (fstat(fd, &st) || st.st_size <= 0) {
    close(fd);
    return -1;
  }
  void *p = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (p == MAP_FAILED) return -1;
  *data = p;
  *len = (size_t)st.st_size;
  return 0;
}
static void cb_unmap(void *u, const void *data, size_t len) {
  (void)u;
  munmap((void *)data, len);
}
#endif

static int64_t host_long(Host *h, jmethodID m) {
  JNIEnv *env = env_now();
  if (!env) return -1;
  const jlong v = (*env)->CallLongMethod(env, h->host, m);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return -1;
  }
  return (int64_t)v;
}

static int64_t cb_wall_ms(void *u) {
  Host *h = (Host *)u;
  if (h->wall_ms) return host_long(h, h->wall_ms);
#ifdef _WIN32
  FILETIME ft;
  GetSystemTimeAsFileTime(&ft);
  const uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime; /* 100 ns since 1601 */
  return (int64_t)(t / 10000u) - 11644473600000LL;
#else
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
#endif
}

/* The boot clock (time since this boot, deep sleep included) and the boot's id, which the SDK reads once per
 * process (Android: Settings.Global.BOOT_COUNT). */
static int64_t cb_boot_ms(void *u, uint8_t boot_id[32]) {
  Host *h = (Host *)u;
  memcpy(boot_id, h->boot_id, 32);
  if (h->boot_ms) return host_long(h, h->boot_ms);
#ifdef _WIN32
  return (int64_t)GetTickCount64(); /* milliseconds since boot, sleep counted (qlcore.h's rule) */
#else
  struct timespec ts;
#ifdef CLOCK_BOOTTIME
  clock_gettime(CLOCK_BOOTTIME, &ts);
#else
  clock_gettime(CLOCK_MONOTONIC, &ts);
#endif
  return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
#endif
}

static int cb_random(void *u, void *buf, size_t len) {
  (void)u;
#ifdef _WIN32
  return BCryptGenRandom(NULL, (PUCHAR)buf, (ULONG)len, BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0 ? 0 : -1;
#else
  const int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  uint8_t *b = (uint8_t *)buf;
  size_t left = len;
  while (left) {
    const ssize_t n = read(fd, b, left);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) {
      close(fd);
      return -1;
    }
    b += n;
    left -= (size_t)n;
  }
  close(fd);
  return 0;
#endif
}

/* bytes in, bytes out through one CoreHost method: the out bytes' length, or -1 */
static int host_bytes(Host *h, jmethodID m, const uint8_t *in, size_t n, uint8_t *out, size_t cap) {
  JNIEnv *env = env_now();
  if (!env || n > 1u << 20) return -1;
  if ((*env)->PushLocalFrame(env, 8) != 0) {
    (*env)->ExceptionClear(env);
    return -1;
  }
  int r = -1;
  jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
  if (a) {
    (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)in);
    jbyteArray b = (jbyteArray)(*env)->CallObjectMethod(env, h->host, m, a);
    if (!(*env)->ExceptionCheck(env) && b) {
      const jsize k = (*env)->GetArrayLength(env, b);
      if (k >= 0 && (size_t)k <= cap) {
        (*env)->GetByteArrayRegion(env, b, 0, k, (jbyte *)out);
        r = (int)k;
      }
    }
  }
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    r = -1;
  }
  (*env)->PopLocalFrame(env, NULL);
  return r;
}

static int cb_agree(void *u, const uint8_t *eph, size_t n, uint8_t z[32]) {
  Host *h = (Host *)u;
  return host_bytes(h, h->agree, eph, n, z, 32) == 32 ? 0 : -1;
}

static int cb_decrypt_rsa(void *u, const uint8_t *c, size_t n, uint8_t kek[32]) {
  Host *h = (Host *)u;
  return host_bytes(h, h->decrypt_rsa, c, n, kek, 32) == 32 ? 0 : -1;
}

static int cb_sign(void *u, const uint8_t *msg, size_t n, uint8_t *sig, size_t *sig_len) {
  Host *h = (Host *)u;
  const int k = host_bytes(h, h->sign, msg, n, sig, *sig_len < 72 ? *sig_len : 72);
  if (k <= 0) return -1;
  *sig_len = (size_t)k;
  return 0;
}

static void cb_save_state(void *u, const uint8_t *blob, size_t n) {
  Host *h = (Host *)u;
  JNIEnv *env = env_now();
  if (!env || (*env)->PushLocalFrame(env, 4) != 0) return;
  jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
  if (a) {
    (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)blob);
    (*env)->CallVoidMethod(env, h->host, h->save_state, a);
  }
  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
  (*env)->PopLocalFrame(env, NULL);
}

static int cb_state_seal(void *u, const uint8_t *in, size_t n, uint8_t *out, size_t *out_n) {
  Host *h = (Host *)u;
  const int k = host_bytes(h, h->state_seal, in, n, out, *out_n);
  if (k < 0) return -1;
  *out_n = (size_t)k;
  return 0;
}

static int cb_state_open(void *u, const uint8_t *in, size_t n, uint8_t *out, size_t *out_n) {
  Host *h = (Host *)u;
  const int k = host_bytes(h, h->state_open, in, n, out, *out_n);
  if (k < 0) return -1;
  *out_n = (size_t)k;
  return 0;
}

static int cb_counter_get(void *u, uint64_t *v) {
  Host *h = (Host *)u;
  const int64_t x = host_long(h, h->counter_get);
  if (x < 0) return -1;
  *v = (uint64_t)x;
  return 0;
}

static int cb_counter_raise(void *u, uint64_t v) {
  Host *h = (Host *)u;
  JNIEnv *env = env_now();
  if (!env) return -1;
  const jboolean ok = (*env)->CallBooleanMethod(env, h->host, h->counter_raise, (jlong)v);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return -1;
  }
  return ok ? 0 : -1;
}

/* ------------------------------------------------------------------ JNI helpers */
static void throw_state(JNIEnv *env, const char *message) {
  jclass c = (*env)->FindClass(env, "java/lang/IllegalStateException");
  if (c) (*env)->ThrowNew(env, c, message);
}

static Host *host_of(JNIEnv *env, jlong h) {
  if (!h) throw_state(env, "the Core is closed");
  return (Host *)(intptr_t)h;
}

static char *dup_utf(JNIEnv *env, jstring s) {
  if (!s) return NULL;
  const char *c = (*env)->GetStringUTFChars(env, s, NULL);
  if (!c) return NULL;
  char *d = strdup(c);
  (*env)->ReleaseStringUTFChars(env, s, c);
  return d;
}

/* a byte[] as a malloc'd buffer with a terminating zero (the manifest is text); NULL for null */
static uint8_t *dup_bytes(JNIEnv *env, jbyteArray a, size_t *n) {
  *n = 0;
  if (!a) return NULL;
  const jsize k = (*env)->GetArrayLength(env, a);
  uint8_t *b = (uint8_t *)malloc((size_t)k + 1);
  if (!b) return NULL;
  (*env)->GetByteArrayRegion(env, a, 0, k, (jbyte *)b);
  b[k] = 0;
  *n = (size_t)k;
  return b;
}

static void set_ints(JNIEnv *env, jintArray out, const jint *v, int n) {
  if (out && (*env)->GetArrayLength(env, out) >= n) (*env)->SetIntArrayRegion(env, out, 0, n, v);
}

static void set_longs(JNIEnv *env, jlongArray out, const jlong *v, int n) {
  if (out && (*env)->GetArrayLength(env, out) >= n) (*env)->SetLongArrayRegion(env, out, 0, n, v);
}

/* ------------------------------------------------------------------ the natives */
static jint n_bind(JNIEnv *env, jclass c, jstring path) {
  (void)c;
  char *p = dup_utf(env, path);
  if (!p) return 1;
  const int r = bind_core(p);
  free(p);
  return r ? r : QLC_API_VERSION;
}

static jstring n_version(JNIEnv *env, jclass c) {
  (void)c;
  return Q.bound ? (*env)->NewStringUTF(env, Q.version()) : NULL;
}

static jstring n_notice(JNIEnv *env, jclass c) {
  (void)c;
  return Q.bound ? (*env)->NewStringUTF(env, Q.notice()) : NULL;
}

static void free_host(JNIEnv *env, Host *h) {
  if (!h) return;
  if (h->host) (*env)->DeleteGlobalRef(env, h->host);
  for (int i = 0; i < ROOTS; ++i) free(h->roots[i]);
  if (h->spki) memset(h->spki, 0, h->spki_len);
  free(h->spki);
#ifdef _WIN32
  DeleteCriticalSection(&h->roots_lock);
#else
  pthread_mutex_destroy(&h->roots_lock);
#endif
  free(h);
}

/* out: [status, detail] */
static jlong n_create(JNIEnv *env, jclass c, jobject host, jbyteArray spki, jbyteArray state, jobjectArray roots,
                      jbyteArray boot_id, jint signals, jint flags, jintArray out) {
  (void)c;
  jint res[2] = {QLC_E_ARG, 0};
  if (!Q.bound || !host || !spki || !roots || (*env)->GetArrayLength(env, roots) != ROOTS) {
    set_ints(env, out, res, 2);
    return 0;
  }
  Host *h = (Host *)calloc(1, sizeof *h);
  if (!h) {
    res[0] = QLC_E_NOMEM;
    set_ints(env, out, res, 2);
    return 0;
  }
#ifdef _WIN32
  InitializeCriticalSection(&h->roots_lock);
#else
  pthread_mutex_init(&h->roots_lock, NULL);
#endif
  h->host = (*env)->NewGlobalRef(env, host);
  jclass hc = (*env)->GetObjectClass(env, host);
  h->agree = (*env)->GetMethodID(env, hc, "agree", "([B)[B");
  h->decrypt_rsa = (*env)->GetMethodID(env, hc, "decryptRsa", "([B)[B");
  h->sign = (*env)->GetMethodID(env, hc, "sign", "([B)[B");
  h->save_state = (*env)->GetMethodID(env, hc, "saveState", "([B)V");
  h->state_seal = (*env)->GetMethodID(env, hc, "stateSeal", "([B)[B");
  h->state_open = (*env)->GetMethodID(env, hc, "stateOpen", "([B)[B");
  h->counter_get = (*env)->GetMethodID(env, hc, "counterGet", "()J");
  h->counter_raise = (*env)->GetMethodID(env, hc, "counterRaise", "(J)Z");
  h->remove_all = (*env)->GetMethodID(env, hc, "removeAll", "(I)Z");
  if ((flags & FLAG_HOST_CLOCKS) != 0) {
    h->wall_ms = (*env)->GetMethodID(env, hc, "wallMs", "()J");
    h->boot_ms = (*env)->GetMethodID(env, hc, "bootMs", "()J");
  }
  (*env)->DeleteLocalRef(env, hc);
  if ((*env)->ExceptionCheck(env) || !h->agree || !h->decrypt_rsa || !h->sign || !h->save_state || !h->state_seal ||
      !h->state_open || !h->counter_get || !h->counter_raise || !h->remove_all) {
    (*env)->ExceptionClear(env);
    free_host(env, h);
    set_ints(env, out, res, 2);
    return 0;
  }
  for (int i = 0; i < ROOTS; ++i) h->roots[i] = dup_utf(env, (jstring)(*env)->GetObjectArrayElement(env, roots, i));
  h->spki = dup_bytes(env, spki, &h->spki_len);
  if (boot_id && (*env)->GetArrayLength(env, boot_id) >= 32) (*env)->GetByteArrayRegion(env, boot_id, 0, 32, (jbyte *)h->boot_id);

  qlc_platform *p = &h->p;
  p->struct_size = sizeof *p;
  p->user = h;
  p->size = cb_size;
  p->read = cb_read;
  p->write = cb_write;
  p->commit = cb_commit;
  p->remove = cb_remove;
  p->wall_ms = cb_wall_ms;
  p->boot_ms = cb_boot_ms;
  p->random = cb_random;
  if (flags & FLAG_RSA) p->decrypt_rsa = cb_decrypt_rsa;
  else p->agree = cb_agree;
  p->sign = cb_sign;
  p->save_state = cb_save_state;
  p->install_key_spki = h->spki;
  p->install_key_spki_len = h->spki_len;
  p->map = cb_map;
  p->unmap = cb_unmap;
  p->signals = (uint32_t)signals;
  if (!(flags & FLAG_NO_SEAL)) {
    p->state_seal = cb_state_seal;
    p->state_open = cb_state_open;
  }
  if (!(flags & FLAG_NO_COUNTER)) {
    p->counter_get = cb_counter_get;
    p->counter_raise = cb_counter_raise;
  }
  size_t sn = 0;
  uint8_t *st = dup_bytes(env, state, &sn);
  int status = QLC_E_ARG;
  h->core = h->spki ? Q.create(p, st, sn, &status) : NULL;
  if (st) memset(st, 0, sn);
  free(st);
  res[0] = status;
  res[1] = 0;
  set_ints(env, out, res, 2);
  if (!h->core) {
    free_host(env, h);
    return 0;
  }
  return (jlong)(intptr_t)h;
}

static void n_destroy(JNIEnv *env, jclass c, jlong hp) {
  (void)c;
  Host *h = (Host *)(intptr_t)hp;
  if (!h) return;
  Q.destroy(h->core);
  free_host(env, h);
}

static jint n_detail(JNIEnv *env, jclass c, jlong hp) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.detail(h->core) : 0;
}

/* The folders of the build in use: [plain, cdn, device] (the Core's own folder is fixed at create). */
static void n_set_roots(JNIEnv *env, jclass c, jlong hp, jstring plain, jstring cdn, jstring device) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return;
  char *r[3] = {dup_utf(env, plain), dup_utf(env, cdn), dup_utf(env, device)};
  roots_lock(h);
  for (int i = 0; i < 3; ++i) {
    free(h->roots[i]);
    h->roots[i] = r[i];
  }
  roots_unlock(h);
}

/* [core json, install_sig], or null with the status in out[0] */
static jobjectArray n_lease_ask(JNIEnv *env, jclass c, jlong hp, jstring challenge, jstring build, jintArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return NULL;
  char *ch = dup_utf(env, challenge), *b = dup_utf(env, build);
  char json[512], sig[128];
  json[0] = sig[0] = 0;
  const int r = ch && b ? Q.lease_ask(h->core, ch, b, json, sizeof json, sig, sizeof sig) : QLC_E_ARG;
  free(ch);
  free(b);
  jint res[2] = {r, r ? Q.detail(h->core) : 0};
  set_ints(env, out, res, 2);
  if (r) return NULL;
  jclass sc = (*env)->FindClass(env, "java/lang/String");
  jobjectArray a = (*env)->NewObjectArray(env, 2, sc, NULL);
  if (!a) return NULL;
  (*env)->SetObjectArrayElement(env, a, 0, (*env)->NewStringUTF(env, json));
  (*env)->SetObjectArrayElement(env, a, 1, (*env)->NewStringUTF(env, sig));
  return a;
}

static jstring n_attest_sig(JNIEnv *env, jclass c, jlong hp, jstring challenge, jintArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return NULL;
  char *ch = dup_utf(env, challenge);
  char sig[128];
  sig[0] = 0;
  const int r = Q.attest_sig(h->core, ch, sig, sizeof sig);
  free(ch);
  jint res[2] = {r, r ? Q.detail(h->core) : 0};
  set_ints(env, out, res, 2);
  return r ? NULL : (*env)->NewStringUTF(env, sig);
}

/* status; times = [exp, refresh_after] */
static jint n_lease_accept(JNIEnv *env, jclass c, jlong hp, jbyteArray lease, jbyteArray manifest, jstring build, jlongArray times) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  size_t ln = 0, mn = 0;
  uint8_t *l = dup_bytes(env, lease, &ln), *m = dup_bytes(env, manifest, &mn);
  char *b = dup_utf(env, build);
  int64_t exp = 0, refresh = 0;
  const int r = l && m && b ? Q.lease_accept(h->core, l, ln, (const char *)m, mn, b, &exp, &refresh) : QLC_E_ARG;
  if (l) memset(l, 0, ln);
  free(l);
  free(m);
  free(b);
  const jlong t[2] = {exp, refresh};
  set_longs(env, times, t, 2);
  return r;
}

static jint n_refusal_accept(JNIEnv *env, jclass c, jlong hp, jbyteArray body, jstring key_id) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  size_t n = 0;
  uint8_t *b = dup_bytes(env, body, &n);
  char *k = dup_utf(env, key_id);
  const int r = b && k ? Q.refusal_accept(h->core, (const char *)b, n, k) : QLC_E_ARG;
  free(b);
  free(k);
  return r;
}

static jint n_lease_piece(JNIEnv *env, jclass c, jlong hp, jbyteArray piece) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  size_t n = 0;
  uint8_t *b = dup_bytes(env, piece, &n);
  const int r = Q.lease_piece(h->core, b, n);
  free(b);
  return r;
}

/* 18.4.7.1: how a lease attempt that brought no lease ended (QLC_ATTEMPT_UNREACHABLE 1, QLC_ATTEMPT_REFUSED 2) */
static jint n_lease_attempt(JNIEnv *env, jclass c, jlong hp, jint outcome) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.lease_attempt(h->core, (int)outcome) : QLC_E_ARG;
}

/* 18.4.7.1: 1 while the Core opens the lease although Quran Lab cannot be reached, else 0; out[0]: until (the Core's) */
static jint n_lease_grace(JNIEnv *env, jclass c, jlong hp, jlongArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  int64_t until = 0;
  const int r = Q.lease_grace(h->core, &until);
  const jlong t[1] = {until};
  set_longs(env, out, t, 1);
  return r;
}

/* out: [has_lease, valid, refresh_due, stopped, now, exp, refresh_after] */
static jint n_tick(JNIEnv *env, jclass c, jlong hp, jlongArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  qlc_lease_status s;
  memset(&s, 0, sizeof s);
  const int r = Q.tick(h->core, &s);
  const jlong v[7] = {s.has_lease, s.valid, s.refresh_due, s.stopped, s.now, s.exp, s.refresh_after};
  set_longs(env, out, v, 7);
  return r;
}

/* out: [day, seconds, prev_day, prev_seconds] */
static jint n_usage(JNIEnv *env, jclass c, jlong hp, jdoubleArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  int64_t day = 0, prev = 0;
  double s = 0, ps = 0;
  const int r = Q.usage(h->core, &day, &s, &prev, &ps);
  const jdouble v[4] = {(jdouble)day, s, (jdouble)prev, ps};
  if (out && (*env)->GetArrayLength(env, out) >= 4) (*env)->SetDoubleArrayRegion(env, out, 0, 4, v);
  return r;
}

static jint n_install(JNIEnv *env, jclass c, jlong hp, jbyteArray manifest, jstring path) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  size_t mn = 0;
  uint8_t *m = dup_bytes(env, manifest, &mn);
  char *p = dup_utf(env, path);
  const int r = m && p ? Q.install(h->core, (const char *)m, mn, p) : QLC_E_ARG;
  free(m);
  free(p);
  return r;
}

/* out: [status, detail] */
static jlong n_model_open(JNIEnv *env, jclass c, jlong hp, jbyteArray manifest, jstring context, jint threads,
                          jint decrypt_threads, jint sample_rate, jint batch, jint no_pin, jintArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return 0;
  size_t mn = 0;
  uint8_t *m = dup_bytes(env, manifest, &mn);
  char *ctx = dup_utf(env, context);
  qlc_open_options o = Q.open_default_options();
  o.context = ctx;
  o.threads = threads;
  o.decrypt_threads = decrypt_threads;
  o.sample_rate = sample_rate;
  o.batch = batch;
  o.no_pin = no_pin;
  int status = QLC_E_ARG;
  qlc_model *model = m ? Q.model_open(h->core, (const char *)m, mn, &o, &status) : NULL;
  free(m);
  free(ctx);
  jint res[2] = {status, model ? 0 : Q.detail(h->core)};
  set_ints(env, out, res, 2);
  return (jlong)(intptr_t)model;
}

static void n_model_close(JNIEnv *env, jclass c, jlong m) {
  (void)env;
  (void)c;
  if (m) Q.model_close((qlc_model *)(intptr_t)m);
}

static jbyteArray n_calibration(JNIEnv *env, jclass c, jlong m) {
  (void)c;
  if (!m) return NULL;
  size_t n = 0;
  const void *d = Q.calibration((const qlc_model *)(intptr_t)m, &n);
  if (!d || !n) return NULL;
  jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
  if (a) (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)d);
  return a;
}

static jint n_chunk_ms(JNIEnv *env, jclass c, jlong m) {
  (void)env;
  (void)c;
  return m ? Q.model_chunk_ms((const qlc_model *)(intptr_t)m) : -1;
}

static jlong n_stream_create(JNIEnv *env, jclass c, jlong m, jintArray out) {
  (void)c;
  int status = QLC_E_ARG;
  qlc_stream *s = m ? Q.stream_create((qlc_model *)(intptr_t)m, &status) : NULL;
  jint res[2] = {status, 0};
  set_ints(env, out, res, 2);
  return (jlong)(intptr_t)s;
}

static void n_stream_destroy(JNIEnv *env, jclass c, jlong s) {
  (void)env;
  (void)c;
  if (s) Q.stream_destroy((qlc_stream *)(intptr_t)s);
}

static jint n_stream_set_heads(JNIEnv *env, jclass c, jlong s, jint heads) {
  (void)env;
  (void)c;
  return s ? Q.stream_set_heads((qlc_stream *)(intptr_t)s, (unsigned)heads) : QLC_E_ARG;
}

static void n_stream_reset(JNIEnv *env, jclass c, jlong s) {
  (void)env;
  (void)c;
  if (s) Q.stream_reset((qlc_stream *)(intptr_t)s);
}

static jint n_stream_push(JNIEnv *env, jclass c, jlong s, jfloatArray pcm, jint off, jint len) {
  (void)c;
  if (!s || !pcm || off < 0 || len < 0 || off + len > (*env)->GetArrayLength(env, pcm)) return QLC_E_ARG;
  float *buf = (float *)malloc(sizeof(float) * (size_t)(len > 0 ? len : 1));
  if (!buf) return QLC_E_NOMEM;
  (*env)->GetFloatArrayRegion(env, pcm, off, len, buf);
  const int r = Q.stream_push((qlc_stream *)(intptr_t)s, buf, len);
  free(buf);
  return r;
}

static jint n_stream_finish(JNIEnv *env, jclass c, jlong s) {
  (void)env;
  (void)c;
  return s ? Q.stream_finish((qlc_stream *)(intptr_t)s) : QLC_E_ARG;
}

static jint n_stream_frames(JNIEnv *env, jclass c, jlong s) {
  (void)env;
  (void)c;
  qlc_heads h;
  memset(&h, 0, sizeof h);
  if (!s || Q.stream_heads((const qlc_stream *)(intptr_t)s, &h) != QLC_OK) return -1;
  return h.n40;
}

#define HEADS 12
/* Every head over 40 ms frames [from, to) (the 20 ms heads over [2 from, 2 to)), in the order of the SDK's HeadId and
 * of the Core's QLC_HEADS bits; an element is null for a head the stream does not compute. */
static jobjectArray n_stream_heads(JNIEnv *env, jclass c, jlong s, jint from, jint to) {
  (void)c;
  qlc_heads h;
  memset(&h, 0, sizeof h);
  if (!s || Q.stream_heads((const qlc_stream *)(intptr_t)s, &h) != QLC_OK) return NULL;
  if (from < 0 || to < from || to > h.n40 || h.n20 != 2 * h.n40) {
    jclass e = (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException");
    if (e) (*env)->ThrowNew(env, e, "head frames out of range");
    return NULL;
  }
  const float *p[HEADS] = {h.log_probs, h.inter1_log_probs, h.inter4_log_probs, h.id_log_probs, h.timing_log_probs, h.gem,
                           h.attr_tafkheem, h.attr_ghunna, h.attr_qalqalah, h.attr_face, h.boundary, h.conf};
  const int v = h.vocab;
  const int width[HEADS] = {v, v, v, v, v, 2, 3, 5, 3, 4, 1, 1};
  const int per40[HEADS] = {1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2};
  jclass fa = (*env)->FindClass(env, "[F");
  if (!fa) return NULL;
  jobjectArray out = (*env)->NewObjectArray(env, HEADS, fa, NULL);
  if (!out) return NULL;
  for (int i = 0; i < HEADS; i++) {
    if (!p[i]) continue;
    const jsize lo = (jsize)from * per40[i] * width[i];
    const jsize n = (jsize)(to - from) * per40[i] * width[i];
    jfloatArray a = (*env)->NewFloatArray(env, n);
    if (!a) return NULL;
    if (n > 0) (*env)->SetFloatArrayRegion(env, a, 0, n, p[i] + lo);
    (*env)->SetObjectArrayElement(env, out, i, a);
    (*env)->DeleteLocalRef(env, a);
  }
  return out;
}

static jint n_recited_index(JNIEnv *env, jclass c, jlong hp, jbyteArray manifest) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  size_t mn = 0;
  uint8_t *m = dup_bytes(env, manifest, &mn);
  const int r = m ? Q.recited_index(h->core, (const char *)m, mn) : QLC_E_ARG;
  free(m);
  return r;
}

static jint n_recited_begin(JNIEnv *env, jclass c, jlong hp, jlong take) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.recited_begin(h->core, (uint64_t)take) : QLC_E_ARG;
}

static jint n_recited_open(JNIEnv *env, jclass c, jlong hp, jlongArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  uint64_t id = 0;
  const int r = Q.recited_open(h->core, &id);
  const jlong v[1] = {(jlong)id};
  set_longs(env, out, v, 1);
  return r;
}

static jint n_recited_feed(JNIEnv *env, jclass c, jlong hp, jintArray units, jint n) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h || !units || n < 0 || n > (*env)->GetArrayLength(env, units)) return QLC_E_ARG;
  jint *u = (*env)->GetIntArrayElements(env, units, NULL);
  if (!u) return QLC_E_NOMEM;
  const int r = Q.recited_feed(h->core, (const int *)u, n);
  (*env)->ReleaseIntArrayElements(env, units, u, JNI_ABORT);
  return r;
}

static jint n_recited_feed_stream(JNIEnv *env, jclass c, jlong hp, jlong s) {
  (void)c;
  Host *h = host_of(env, hp);
  return h && s ? Q.recited_feed_stream(h->core, (const qlc_stream *)(intptr_t)s) : QLC_E_ARG;
}

#define RESULT_INTS 9
/* A recited result as [found, surah, first, last, score, day, context, ties, reported] (day: days since 1970). */
static void put_result(JNIEnv *env, jintArray out, const qlc_recited_result *r) {
  const jint v[RESULT_INTS] = {r->found, r->surah, r->first, r->last, r->score, (jint)r->day, r->context, r->ties, r->reported};
  set_ints(env, out, v, RESULT_INTS);
}

static jint n_recited_finish(JNIEnv *env, jclass c, jlong hp, jlong take, jint flags, jintArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  qlc_recited_result r;
  memset(&r, 0, sizeof r);
  const int st = Q.recited_finish(h->core, (uint64_t)take, (unsigned)flags, &r);
  put_result(env, out, &r);
  return st;
}

/* The journal's finished takes (qlc_recited_done): the count; with i below it, take i's id and result. */
static jint n_recited_done(JNIEnv *env, jclass c, jlong hp, jint i, jlongArray id, jintArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  qlc_recited_result r;
  memset(&r, 0, sizeof r);
  uint64_t t = 0;
  const int n = Q.recited_done(h->core, i, &t, &r);
  if (n > i && i >= 0) {
    const jlong v[1] = {(jlong)t};
    set_longs(env, id, v, 1);
    put_result(env, out, &r);
  }
  return n;
}

static jint n_recited_reported(JNIEnv *env, jclass c, jlong hp, jlong take) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.recited_reported(h->core, (uint64_t)take) : QLC_E_ARG;
}

/* ---- R3, R4: the reference data and the vocabulary of the open model ---- */
/* One ayah's expected units and its words' first units: {units, word_starts}; null with status[0] < 0. */
static jobjectArray n_reference_expected(JNIEnv *env, jclass c, jlong m, jint surah, jint ayah, jintArray status) {
  (void)c;
  int cap = 2048, wcap = 256;
  for (int round = 0; round < 3; round++) {
    int *u = (int *)malloc(sizeof(int) * (size_t)cap), *w = (int *)malloc(sizeof(int) * (size_t)wcap);
    if (!u || !w) {
      free(u);
      free(w);
      const jint st[1] = {QLC_E_NOMEM};
      set_ints(env, status, st, 1);
      return NULL;
    }
    int nw = 0;
    const int n = m ? Q.reference_expected((const qlc_model *)(intptr_t)m, surah, ayah, u, cap, w, wcap, &nw) : QLC_E_ARG;
    if (n >= 0 && (n > cap || nw > wcap)) {
      cap = n > cap ? n : cap;
      wcap = nw > wcap ? nw : wcap;
      free(u);
      free(w);
      continue;
    }
    const jint st[1] = {n < 0 ? n : QLC_OK};
    set_ints(env, status, st, 1);
    jobjectArray out = NULL;
    if (n >= 0) {
      jclass ia = (*env)->FindClass(env, "[I");
      jintArray ua = ia ? (*env)->NewIntArray(env, n) : NULL;
      jintArray wa = ua ? (*env)->NewIntArray(env, nw) : NULL;
      out = wa ? (*env)->NewObjectArray(env, 2, ia, NULL) : NULL;
      if (out) {
        if (n) (*env)->SetIntArrayRegion(env, ua, 0, n, (const jint *)u);
        if (nw) (*env)->SetIntArrayRegion(env, wa, 0, nw, (const jint *)w);
        (*env)->SetObjectArrayElement(env, out, 0, ua);
        (*env)->SetObjectArrayElement(env, out, 1, wa);
      }
    }
    free(u);
    free(w);
    return out;
  }
  const jint st[1] = {QLC_E_ENGINE};
  set_ints(env, status, st, 1);
  return NULL;
}

static jint n_reference_ayah_count(JNIEnv *env, jclass c, jlong m, jint surah) {
  (void)env;
  (void)c;
  return m ? Q.reference_ayah_count((const qlc_model *)(intptr_t)m, surah) : QLC_E_ARG;
}

static jint n_token_count(JNIEnv *env, jclass c, jlong m) {
  (void)env;
  (void)c;
  return m ? Q.model_token_count((const qlc_model *)(intptr_t)m) : QLC_E_ARG;
}

static jstring n_token_name(JNIEnv *env, jclass c, jlong m, jint id) {
  (void)c;
  const char *n = m ? Q.model_token_name((const qlc_model *)(intptr_t)m, id) : NULL;
  return n ? (*env)->NewStringUTF(env, n) : NULL;
}

/* ---- R10: the learner days, in the Core's journal ---- */
static jint n_days_mark(JNIEnv *env, jclass c, jlong hp, jlong utc, jlong local) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.days_mark(h->core, utc, local) : QLC_E_ARG;
}

static jint n_days_judged(JNIEnv *env, jclass c, jlong hp, jlong utc, jint judged, jint correct) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.days_judged(h->core, utc, judged, correct) : QLC_E_ARG;
}

static jint n_days_seal(JNIEnv *env, jclass c, jlong hp, jlong utc) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.days_seal(h->core, utc) : QLC_E_ARG;
}

static jint n_days_trend_sent(JNIEnv *env, jclass c, jlong hp, jlong week) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.days_trend_sent(h->core, week) : QLC_E_ARG;
}

/* qlc_days flattened: first_day, the six counts, active[40], local[40], streak_day[10], streak[10], sealed[10],
 * week[6], judged[6], correct[6], trend_week; null when the Core refused. */
static jlongArray n_days_read(JNIEnv *env, jclass c, jlong hp) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return NULL;
  qlc_days d;
  memset(&d, 0, sizeof d);
  if (Q.days_read(h->core, &d) != QLC_OK) return NULL;
  jlong v[6 + 2 * QLC_DAYS_KEEP + 3 * QLC_DAYS_STREAKS + 3 * QLC_DAYS_WEEKS + 1];
  int k = 0;
  v[k++] = d.first_day;
  v[k++] = d.n_active;
  v[k++] = d.n_local;
  v[k++] = d.n_streak;
  v[k++] = d.n_sealed;
  v[k++] = d.n_weeks;
  for (int i = 0; i < QLC_DAYS_KEEP; i++) v[k++] = d.active[i];
  for (int i = 0; i < QLC_DAYS_KEEP; i++) v[k++] = d.local[i];
  for (int i = 0; i < QLC_DAYS_STREAKS; i++) v[k++] = d.streak_day[i];
  for (int i = 0; i < QLC_DAYS_STREAKS; i++) v[k++] = d.streak[i];
  for (int i = 0; i < QLC_DAYS_STREAKS; i++) v[k++] = d.sealed[i];
  for (int i = 0; i < QLC_DAYS_WEEKS; i++) v[k++] = d.week[i];
  for (int i = 0; i < QLC_DAYS_WEEKS; i++) v[k++] = d.judged[i];
  for (int i = 0; i < QLC_DAYS_WEEKS; i++) v[k++] = d.correct[i];
  v[k++] = d.trend_week;
  jlongArray a = (*env)->NewLongArray(env, k);
  if (a) (*env)->SetLongArrayRegion(env, a, 0, k, v);
  return a;
}

/* ---- R2: the accelerator program from memory, run by the Core ---- */
static jint n_accel_allowed(JNIEnv *env, jclass c, jlong hp) {
  (void)c;
  Host *h = host_of(env, hp);
  return h ? Q.accel_allowed(h->core) : QLC_E_ARG;
}

/* The backend's name ("npu:<soc>") or null; status: [status, qlc_detail]. Seconds long: never on the audio thread. */
static jstring n_accel_prepare(JNIEnv *env, jclass c, jlong hp, jlong m, jstring kind, jstring dir, jintArray status) {
  (void)c;
  Host *h = host_of(env, hp);
  char *k = dup_utf(env, kind), *d = dir ? dup_utf(env, dir) : NULL;
  char backend[64] = {0};
  const int st = h && m && k ? Q.accel_prepare((qlc_model *)(intptr_t)m, k, d, backend, sizeof backend) : QLC_E_ARG;
  const jint res[2] = {st, st && h ? Q.detail(h->core) : 0};
  set_ints(env, status, res, 2);
  free(k);
  free(d);
  return st == QLC_OK ? (*env)->NewStringUTF(env, backend) : NULL;
}

static jint n_accel_release(JNIEnv *env, jclass c, jlong m) {
  (void)env;
  (void)c;
  return m ? Q.accel_release((qlc_model *)(intptr_t)m) : QLC_E_ARG;
}

static jlong n_stream_create_on(JNIEnv *env, jclass c, jlong m, jint backend, jintArray out) {
  (void)c;
  int status = QLC_E_ARG;
  qlc_stream *s = m ? Q.stream_create_on((qlc_model *)(intptr_t)m, backend, &status) : NULL;
  jint res[2] = {status, 0};
  set_ints(env, out, res, 2);
  return (jlong)(intptr_t)s;
}

static jint n_stream_backend(JNIEnv *env, jclass c, jlong s) {
  (void)env;
  (void)c;
  return s ? Q.stream_backend((const qlc_stream *)(intptr_t)s) : QLC_E_ARG;
}

/* out: [day, takes, ayahs, prev_day, prev_takes, prev_ayahs] */
static jint n_recited_totals(JNIEnv *env, jclass c, jlong hp, jlongArray out) {
  (void)c;
  Host *h = host_of(env, hp);
  if (!h) return QLC_E_ARG;
  int64_t day = 0, prev = 0;
  int t = 0, a = 0, pt = 0, pa = 0;
  const int r = Q.recited_totals(h->core, &day, &t, &a, &prev, &pt, &pa);
  const jlong v[6] = {day, t, a, prev, pt, pa};
  set_longs(env, out, v, 6);
  return r;
}

static const JNINativeMethod METHODS[] = {
    {"bind", "(Ljava/lang/String;)I", (void *)n_bind},
    {"version", "()Ljava/lang/String;", (void *)n_version},
    {"notice", "()Ljava/lang/String;", (void *)n_notice},
    {"create", "(Ljava/lang/Object;[B[B[Ljava/lang/String;[BII[I)J", (void *)n_create},
    {"destroy", "(J)V", (void *)n_destroy},
    {"detail", "(J)I", (void *)n_detail},
    {"setRoots", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", (void *)n_set_roots},
    {"leaseAsk", "(JLjava/lang/String;Ljava/lang/String;[I)[Ljava/lang/String;", (void *)n_lease_ask},
    {"attestSig", "(JLjava/lang/String;[I)Ljava/lang/String;", (void *)n_attest_sig},
    {"leaseAccept", "(J[B[BLjava/lang/String;[J)I", (void *)n_lease_accept},
    {"refusalAccept", "(J[BLjava/lang/String;)I", (void *)n_refusal_accept},
    {"leasePiece", "(J[B)I", (void *)n_lease_piece},
    {"leaseAttempt", "(JI)I", (void *)n_lease_attempt},
    {"leaseGrace", "(J[J)I", (void *)n_lease_grace},
    {"tick", "(J[J)I", (void *)n_tick},
    {"usage", "(J[D)I", (void *)n_usage},
    {"install", "(J[BLjava/lang/String;)I", (void *)n_install},
    {"modelOpen", "(J[BLjava/lang/String;IIIII[I)J", (void *)n_model_open},
    {"modelClose", "(J)V", (void *)n_model_close},
    {"calibration", "(J)[B", (void *)n_calibration},
    {"chunkMs", "(J)I", (void *)n_chunk_ms},
    {"streamCreate", "(J[I)J", (void *)n_stream_create},
    {"streamDestroy", "(J)V", (void *)n_stream_destroy},
    {"streamSetHeads", "(JI)I", (void *)n_stream_set_heads},
    {"streamReset", "(J)V", (void *)n_stream_reset},
    {"streamPush", "(J[FII)I", (void *)n_stream_push},
    {"streamFinish", "(J)I", (void *)n_stream_finish},
    {"streamFrames", "(J)I", (void *)n_stream_frames},
    {"streamHeads", "(JII)[[F", (void *)n_stream_heads},
    {"recitedIndex", "(J[B)I", (void *)n_recited_index},
    {"recitedBegin", "(JJ)I", (void *)n_recited_begin},
    {"recitedOpen", "(J[J)I", (void *)n_recited_open},
    {"recitedFeed", "(J[II)I", (void *)n_recited_feed},
    {"recitedFeedStream", "(JJ)I", (void *)n_recited_feed_stream},
    {"recitedFinish", "(JJI[I)I", (void *)n_recited_finish},
    {"recitedTotals", "(J[J)I", (void *)n_recited_totals},
    {"recitedDone", "(JI[J[I)I", (void *)n_recited_done},
    {"recitedReported", "(JJ)I", (void *)n_recited_reported},
    {"referenceExpected", "(JII[I)[[I", (void *)n_reference_expected},
    {"referenceAyahCount", "(JI)I", (void *)n_reference_ayah_count},
    {"tokenCount", "(J)I", (void *)n_token_count},
    {"tokenName", "(JI)Ljava/lang/String;", (void *)n_token_name},
    {"daysMark", "(JJJ)I", (void *)n_days_mark},
    {"daysJudged", "(JJII)I", (void *)n_days_judged},
    {"daysSeal", "(JJ)I", (void *)n_days_seal},
    {"daysTrendSent", "(JJ)I", (void *)n_days_trend_sent},
    {"daysRead", "(J)[J", (void *)n_days_read},
    {"accelAllowed", "(J)I", (void *)n_accel_allowed},
    {"accelPrepare", "(JJLjava/lang/String;Ljava/lang/String;[I)Ljava/lang/String;", (void *)n_accel_prepare},
    {"accelRelease", "(J)I", (void *)n_accel_release},
    {"streamCreateOn", "(JI[I)J", (void *)n_stream_create_on},
    {"streamBackend", "(J)I", (void *)n_stream_backend},
};

TJ_EXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  g_vm = vm;
#ifndef _WIN32
  if (pthread_key_create(&g_detach_key, detach_thread)) return JNI_ERR;
#endif
  jclass c = (*env)->FindClass(env, CLASS);
  if (!c) return JNI_ERR;
  if ((*env)->RegisterNatives(env, c, METHODS, (jint)(sizeof METHODS / sizeof METHODS[0])) != 0) return JNI_ERR;
  return JNI_VERSION_1_6;
}
