# TajweedFormer for Android: reference for coding assistants

Everything an assistant needs to integrate this SDK correctly: use only the names, calls and settings shown here or in the installed version's sources. People: the short version is in [README.md](README.md).

## What you get

<table>
<tr>
<td width="33%" valign="top">

**Hears every word**<br>Streaming recognition of Quranic recitation while the learner recites. Results arrive as often as every 160 ms on the CPU engine (320 ms by default, including on the NPU; 480 ms for longer context).

</td>
<td width="33%" valign="top">

**Points to the word to fix**<br>Wrong letters and vowels, madd held too short or too long, and a score for every word. Qalqalah checks are experimental.

</td>
<td width="33%" valign="top">

**Native on Android**<br>Kotlin for Android 8.0+ and the desktop JVM. It runs on the CPU on every phone, and on the NPU where this phone's build passes its self-test.

</td>
</tr>
<tr>
<td valign="top">

**Private by default**<br>Offline, on the device. A recording leaves it only when the learner agrees to share it.

</td>
<td valign="top">

**Nothing to host**<br>The SDK downloads the right model for each device (about 57 MB) from our CDN in six regions, verifies it and keeps it up to date.

</td>
<td valign="top">

**Tuned in the portal**<br>Four presets and three word levels. Change them at any time; your app picks them up without a new build.

</td>
</tr>
</table>

### Strict or gentle: you choose

**Strict** almost never flags a correct recitation. **Balanced** is the default. **Sensitive** catches more, and **Very sensitive** catches the most, for a teacher who reviews every flag first. The word level follows your learners: **strict** for huffaz and advanced students, **general** for most apps, **beginner** for children and classes.

## Why TajweedFormer

- **Made for the Quran.** Your app names the ayah; TajweedFormer checks the recitation against it, sound by sound, and gives every word its own verdict.
- **Feedback in the moment.** Results stream while the learner recites, so your app can respond on the word itself, not after the take.
- **One engine on every platform.** The same engine runs the [Android](https://github.com/Quran-Lab/tajweedformer-android), [iOS and macOS](https://github.com/Quran-Lab/tajweedformer-apple), [web](https://github.com/Quran-Lab/tajweedformer-web) and [Windows and Linux](https://github.com/Quran-Lab/tajweedformer-c) SDKs, all set from one portal.
- **Free, and it stays free.** Quran Lab is a non-profit waqf: the SDKs and models are never sold. The licence, NPL-1.2, asks three things: no paywall on model features, no results presented as rulings, and show the notice.

## Built for AI developers

Copy the setup prompt from [portal.quranlab.ai](https://portal.quranlab.ai) and paste it into your coding assistant. That one prompt sets everything up: the package, your key, and the settings for the learners your app is for. [AGENTS.md](AGENTS.md), beside this README, gives the assistant the complete API, so the code it writes uses names that exist. Prefer to do it by hand? Get a key in the portal and start below.

## At a glance

- **What it is:** the Kotlin SDK 2.0.0 for the Quran Lab TajweedFormer engine (Maven
  `ai.quranlab:tajweedformer`, package `ai.quranlab.tajweedformer`): a JNI bridge over
  [tajweedformer-engine](https://github.com/Quran-Lab/tajweedformer-engine) (pinned as the `engine/`
  submodule) and an API that mirrors the Swift SDK. For the V4 model (`sdk/v4.0`): signed model
  folders downloaded by profile, a `TajweedRecognizer` that streams every head of the network on the
  CPU (or the NPU), the decoded transcript with confidences, and tajweed verdicts per site and per
  word under calibrated presets that the app and the portal can tune. V4 only; v3.2 stays on 1.x.
- **Who it is for:** Android apps (AAR, minSdk 26, arm64-v8a and armeabi-v7a); the same Kotlin
  sources also run on the desktop JVM.
- **Status:** preview. V4 streams through the exact engine (tajweedformer-engine b6436d7,
  `zf_tajweed.h` API 2): 35 of 35 parity clips bit-identical at c16_l64 on all 12 outputs of every
  chunk through this SDK's `TajweedRecognizer`, from a model folder downloaded by `ModelStore` (desktop
  JVM, Windows x86-64, and the Alcatel 1, armeabi-v7a, Android 11). The scorer gives the reference
  scorer's verdicts on all 48 golden clips at both presets. The NPU backend is built and its host side
  tested on the JVM; its native runner ran on the Exynos 2500 and 2600 boards (every head byte-identical
  to the bench runner); the backend has not run inside an app on an NPU phone.

## The TajweedFormer SDKs

One model, one engine, the same bits on every platform. Each SDK compiles the same engine source
(Quran-Lab/tajweedformer-engine, pinned as a submodule), so no platform can drift from the others.

| Repository | What it is | Platforms | Install |
| - | - | - | - |
| [tajweedformer-android](https://github.com/Quran-Lab/tajweedformer-android) | Kotlin SDK (AAR) | Android 8.0+, desktop JVM | Maven `ai.quranlab:tajweedformer:2.0.0` |
| [tajweedformer-apple](https://github.com/Quran-Lab/tajweedformer-apple) | Swift SDK: Neural Engine (CoreML) with the exact CPU engine as fallback | iOS 15+, macOS 12+ | SwiftPM, product `TajweedFormer` |
| [tajweedformer-web](https://github.com/Quran-Lab/tajweedformer-web) | WebAssembly SDK with SIMD128 | Browsers, Node | npm `@quran-lab/tajweedformer` |
| [tajweedformer-c](https://github.com/Quran-Lab/tajweedformer-c) | C API and server runner with x86 int8 kernels (AVX2, AVX-VNNI, AVX-512 VNNI) | Windows, Linux (x86-64) | CMake (`add_subdirectory`) |
| [tajweedformer-engine](https://github.com/Quran-Lab/tajweedformer-engine) | The engine: exact C runtime, tajweed scorer, conformance suite | everything above | Git submodule |

The model is Quran-Lab/tajweedformer-v4 (`sdk/v4.0`), hosted by Quran Lab and served by
`cdn.quranlab.ai/models` to approved app keys (request access at
[portal.quranlab.ai/access](https://portal.quranlab.ai/access)). SDK 2.0.0 runs V4 only.
Every release's binaries (the AAR and POM, the XCFramework zips, the npm package, the native archives) are
also on `https://cdn.quranlab.ai/sdk/<product>/<version>/`, with a `SHA256SUMS` file, served from six regions.
[portal.quranlab.ai](https://portal.quranlab.ai) is where you create an app, get its developer key,
choose which model revision your users get, and set the tajweed checks (presets, per-class
thresholds) your users see, without shipping a new build.

## Quick start

```sh
git clone --recurse-submodules https://github.com/Quran-Lab/tajweedformer-android
cd tajweedformer-android
./gradlew :tajweedformer:assembleRelease     # tajweedformer/build/outputs/aar/tajweedformer-release.aar
```

Depend on `ai.quranlab:tajweedformer:2.0.0` (the AAR, or the `:tajweedformer` project).

### Ten lines

```kotlin
import ai.quranlab.tajweedformer.*
import ai.quranlab.tajweedformer.android.TajweedFormerAndroid

val tf = TajweedFormerAndroid.client(context, headers = { mapOf("x-qlab-key" to BuildConfig.QLAB_KEY) })
val tj = tf.recognizer(TajweedRecognizer.Options(word = WordOption.level(WordLevel.GENERAL)))  // 56.5 MB once, self-test, CPU or NPU
tj.remoteConfig = portalConfig.tajweedJson                  // optional: the portal's tajweed block
val session = tj.startSession()
launch { session.chunks.collect { c -> draw(c.heads, c.decode(tj.model.vocabulary)) } }  // raw + decoded, per 320 ms
session.append(samples)                                     // 16 kHz mono float, as it arrives
val result = session.finishAndScore(surah = 1, ayah = 2)    // tajweed layer: sites, madd, words
result.flaggedWords.forEach { w -> show(w.word, w.score, w.best?.expected, w.best?.alternative) }
```

`tf.recognizer()` downloads the CPU profile on the first call (any network), verifies it, runs the
CPU self-test, and uses the NPU only when this phone's build is on disk, loads, and passes its own
self-test against the CPU (NPU files are fetched in the background on an unmetered network after
the CPU self-test passed). `tj.backendChoice` says which backend runs and why.

### The three layers (one concept set with the Swift, web and native SDKs)

- **Raw** (`TajweedChunk.heads`, `TajweedHeads`): every head per chunk as float arrays,
  `[frames, width]` row major, each typed (`TajweedHead.frameMs` 40 or 20, `width`, `kind`
  log-probs / probs / logit, `classNames(vocabulary)`). Opt in per head with
  `Options(heads = setOf(...))` or `allHeads = true`; the default is what the scorer reads plus `conf`.
- **Decoded** (`chunk.decode(vocabulary)`, `tj.decode(heads)`): per 40 ms CTC frame the top-1 and
  runner-up token with log-probs and margin; the greedy transcript with per-token `conf` (sigmoid of
  the conf head over the token's frames), `margin` and a display `confidence`; words split at the
  `|` token; times in ms (frame k = 40 k ms of the take).
- **Tajweed** (`TajweedResult`): per-site verdicts (class, expected, best alternative, per-head and
  fused LLR, threshold, flagged, frames), madd counts with ratio and duration, qalqalah and
  gemination evidence, and per-word verdicts (`words`): the engine's readout (zf_tajweed.h API 3,
  calibration.json `word.readout`), the largest clipped fused LLR over every single-unit edit inside the
  word, flagged when `score >= tau`, with the best edit as evidence. Word levels come from
  calibration.json's per-context `word` block (c16_l64: strict 7.0224, general 5.5368, beginner 3.9759,
  default general, custom 2.0 to 12.0); P2.5's table (strict 6.5, general 4.93, beginner 4.0, floor 2.0)
  is used only without a block, and a site-based approximation only with an engine before API 3.
  Precedence everywhere: app code > portal > calibration.

### Observing the SDK

`TajweedFormer.events.add(observer)` with an `SdkObserver`: session start and end (audio seconds,
compute seconds, RTF, chunks, backend), the audio exactly as pushed (`onAudio`, for opt-in recording
sharing), backend choice and every fallback with a reason code, download progress and results,
self-test results, handled errors. The SDK's cloud module (telemetry, sharing) is built on it.

## How it works

```mermaid
flowchart LR
  hf[("cdn.quranlab.ai/models<br/>zipformer_p-arabic-v3")] -- model files --> assets["App assets<br/>or a directory"]
  cdn[("cdn.quranlab.ai")] -. not yet .-> assets
  mic(["Audio, 8 to 48 kHz"]) --> kotlin["tajweedformer-android<br/>Kotlin API"]
  assets --> kotlin
  kotlin -- JNI --> engine["tajweedformer-engine<br/>exact C engine"]
  engine --> out(["Phonemes as<br/>a Flow of events"])
  classDef here fill:#2a3b8f,stroke:#2a3b8f,color:#ffffff
  class kotlin here
```

Each recognizer owns one thread; every engine call it makes runs there, in order. Audio goes to the
engine unchanged through `native/zf_jni.c`, and the engine resamples, computes the fbank, runs the
model one 480 ms chunk at a time and decodes the phonemes. The model download and update services
of the Swift SDK (CDN, signed manifests, staged rollouts from `portal.quranlab.ai`) are not in the
Android SDK yet; apps ship the model files themselves.

<!-- exactness:begin -->
## Exactness

Bit-exact means the same thing in every Quran Lab TajweedFormer repository. For the same input, the
engine gives the same phoneme tokens and the same value, to the last bit, for every log-probability
of every chunk, as ONNX Runtime's ARM int8 reference running the published v3.2 model. It is checked
on the 35 parity clips of the conformance set (the `conformance-data-2` release of tajweedformer-engine):
the tokens, the chunk count and an FNV-1a fingerprint of every chunk's log-probs are compared with
the expected files, and one bit of difference on one clip is a failure. Paths that cannot meet this
(the Neural Engine, NPUs, fp16 anywhere) are called approximate, and they are measured against the
exact engine, never the other way round.
<!-- exactness:end -->

### How this repository proves it

The wrapper adds nothing that could move a bit: it builds the engine in its release configuration
(`ZF_RELEASE`, `ZF_QUANT_DIVIDE`, no floating point contraction), passes samples through unchanged,
and resamples with the engine's own resampler. `V4ConformanceTest` downloads the layout v2 folder
through `ModelStore`, streams the 35 parity clips' 16 kHz audio through `TajweedRecognizer` in 320,
1234, 16000 and 4321-sample pieces at c16_l64, and compares the chunk count, the greedy tokens, the
cumulative FNV-1a of log_probs and the FNV-1a of all 12 outputs of every chunk (7,824 chunk
outputs) with the engine's `v4_c16_l64_audio_expected.jsonl`: 35 of 35 on the desktop JVM, and
35 of 35 on the Alcatel 1, armeabi-v7a, Android 11 (`DeviceEndToEndTest`, after a ModelStore download over adb reverse with a cut and a Range resume). The 1.x history (v3.2 and v3.1 passes) is in
docs/engineering-notes.md.

## Performance

Through this SDK on the Alcatel 1 only a debug native build has run so far (RTF 0.78, not representative). The engine it wraps, at the same source,
measured on Android (from the pinned engine's README):

| Device | Configuration | Per 480 ms chunk | Measured |
| - | - | - | - |
| Alcatel 1 (MT6739, 4x Cortex-A53, armeabi-v7a) | exact engine, ORT-free runner, 4 threads | 78.1 to 79.1 ms, RTF 0.163 to 0.165 | 2026-09-24 |
| Alcatel 1 | exact engine, under ONNX Runtime | 80.8 to 81.2 ms, RTF 0.168 to 0.169 | 2026-09-24 |

Dates are those of the tajweedformer-engine commit that recorded the figure.

## Usage

### Tajweed settings (V4)

Three layers decide what is checked and how strictly, and every SDK resolves them the same way
(the shared settings vectors are this repository's tests):

1. **App code** (`TajweedOptions`): a global `preset`, per-class `overrides` (a preset or a fused-LLR
   threshold in -20..20), `featurePresets` for madd, qalqalah and gemination, `checks` on or off, and
   the `context`. An invalid value throws `RecognizerException.InvalidAppOption` (code
   `invalid_app_option`): app code is never silently dropped.
2. **The portal** (`portal.quranlab.ai`), as the `tajweed` block of the signed `/v1/config`:
   `RemoteTajweed.parseJson` (or `fromConfigJson` for the whole document) never throws; an invalid
   field is dropped and listed in `dropped`, and a block whose `v` is not 1 is ignored whole.
3. **calibration.json** in the model folder: the default context and preset, each class's status
   and thresholds per preset (and per backend, `int8` for the exact engine, `fp32` for CoreML, NPUs
   and servers).

Per class, an override (app, then portal) wins over a global preset (app, then portal), which wins
over calibration's default. Checks follow app, then portal, then calibration's status: `validated`
is on by default, `experimental` off. A check marked `off` is reported only when the app turns it on
in a **debug build** of the host app (on Android its `FLAG_DEBUGGABLE`, `TajweedAndroid.isDebuggable`;
on the JVM the explicit `debug` flag); the portal can never turn one on, and every refused attempt
is listed in `refused`. `TajweedSettings.resolve(...)` (or `recognizer.settings`) gives the result,
and `toJson()` the same JSON every SDK gives.

The shipped contexts are whatever calibration.json lists (the folder has one engine folder per
context); nothing in the SDK assumes a fixed set.

### Model download and verification

`ModelStore` keeps the model in app storage: it fetches the folder's signed `manifest.json` (format
3, Ed25519 over the same text as every 2.0.0 SDK and the publisher's `make_manifest3.py`, checked
against Quran Lab's key) and downloads only the profile in use: the android CPU profile (shared
weights, front end, tokens, calibration, expected table, and the chosen context's graph, data and
frames: 56.5 MB of gzip for c16_l64, about 0.9 MB more per extra context), and for the NPU only the
files of this SoC and context (model, I/O contract, mask table; a chained build such as the Exynos
2500's has its parts, role `npu-part`, in place of the one model). NPU builds ship at c16_l64 only;
`c8_l64` (160 ms chunks, 64 left frames) has none, runs on the exact CPU engine and ships in the next V4.0
revision (about 0.65 MB more). Downloads resume with HTTP Range into `<path>.part` (If-Range
with the server's ETag); the stored bytes' size and SHA-256 are checked, gzip files are inflated and
the decoded file's size and SHA-256 checked, then moved into place atomically; any mismatch deletes
both. The app key and other headers come from a `HeaderProvider`; an `HttpTransport` replaces
`HttpURLConnection` if an app wants OkHttp or Cronet. A file outside the profile is never downloaded
or opened.

**Backends.** `Backend.CPU` is the exact engine: bit-exact on every device, always there, and the
fallback. `Backend.NPU` runs the SoC's LiteRT AOT build (Google Tensor, Exynos 2600; the Exynos 2500's build is
a chain of 9 models that `NpuChainRunner` runs in order over one name-keyed tensor pool) with int16 I/O
through LiteRT's C API (`libLiteRt.so` from `com.google.ai.edge.litert:litert:2.2.0` and the vendor
dispatch library, both added by the app; the SDK itself does not depend on LiteRT). The host side
(the engine's own resampler and fbank, windows, state requantisation, the mask table, log-softmax or
softmax per head) is in `ai.quranlab.tajweedformer.npu`.

**CPU first, the NPU as a verified upgrade** (`ai.quranlab.tajweedformer.accel`). The exact
CPU engine serves from the first session. `AcceleratorController` prepares the NPU in the background
(WorkManager: idle, charging, unmetered; the LiteRT load on a low-priority thread with a timeout), then
runs the shipped self-test set (`selftest/` in the model folder: the three Basmala must-decode clips and
Husary_112001) and promotes it only if tokens, site flags and word flags equal the CPU engine's expected
files, nothing is NaN or inf, per-chunk p90 <= 0.5 x chunk_ms and the median is below the CPU's. A new
session then runs on the NPU; a session in progress never changes engine. The verdict is kept per model
revision, OS build (`Build.FINGERPRINT`), device and SoC, so an OS update tests again. A crash sentinel
(fsync'd before every load and first run) disables the NPU for that revision and OS build if the process
died there. A per-chunk watchdog (slower than real time, NaN or inf, an all-blank take, an exception) sends
the next takes to the CPU; serious thermal state, memory warnings and the portal's denylist prefer the
CPU. `client.accelerator(model)` gives the state (`cpu`, `preparing`, `ready`, `disabled(reason)`) as a
`StateFlow`, listeners, `awaitReady()` and `forced` for debugging; nothing is needed by default. **The
NPU path has not run inside an app on an NPU phone yet** (its native runner, through
`native/tools/litert_npu_harness.c`, ran on the Exynos 2500 and 2600 boards: every head byte-identical to the
bench runner).

**Threading.** Each recognizer owns one thread, and every engine call it makes runs there, in the
order the calls were made. The public calls are safe from any thread or coroutine. A recognizer runs
one session at a time (`SessionActive` otherwise).

SDK 2.0.0 hosts TajweedFormer V4 only; v3.2 stays on the 1.x SDKs.

## Build from source

The build needs the Android SDK with `platforms;android-34`, `build-tools;34.0.0`,
`ndk;30.0.16248370` and `cmake;3.31.6`, and a JDK 17 or newer. `local.properties` names the SDK
(`sdk.dir=/path/to/android-sdk`). With the command line tools only:

```sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;30.0.16248370" "cmake;3.31.6"
./gradlew :tajweedformer:assembleRelease
```

`scripts/build_host.sh` builds the JNI library for the desktop JVM (macOS, Linux). The library
carries the V4 binding (`native/tj_jni.c`) when the engine checkout has its `zf_tajweed` target.

## Verify it yourself

```sh
./gradlew :tajweedformer-jvm:test                      # desktop JVM, host build of the JNI library
./gradlew :tajweedformer-jvm:test -PnoNative           # no host build (default on Windows); engine tests skip
./gradlew :tajweedformer:assembleDebugAndroidTest      # the device test APK (DeviceEndToEndTest)
```

With the host library (`ZF_ENGINE_DIR=<engine> scripts/build_host.sh`; on Windows Git Bash, MinGW
GCC and Ninja on `PATH`) named by `TAJWEEDFORMER_LIBRARY`, the staged model folder
(`TJ_V4_TRANSPORT_DIR`, the transport folder with a format 3 manifest), and the engine's clips
(`ZF_V4_AUDIO_DIR`, `ZF_V4_CONFORMANCE_DIR`), the JVM tests also run `V4ConformanceTest` (a
`ModelStore` download of the folder through a local server, then the 35 clips through
`TajweedRecognizer`, every output of every chunk against `v4_c16_l64_audio_expected.jsonl`),
`BackendFallbackTest` (backend choice and fallback with the real front end and the staged NPU
tables), `ScorerVectorsTest` (with `TJ_SCORER_TABLE`) and `EngineSettingsTest`. The rest needs no
engine: format 3 manifests and signatures, profiles, SoC ids, downloads against a local Range / ETag
server, the NPU host loop, the decoded layer, word scores and the settings vectors.

Recitation sharing's tests (`SharingTest`, `SharingCodecTest`, `SharingVectorsTest`) encode real Ogg Opus,
so they need the host build of `libtajweedformer_opus` (libopus 1.6.1 from `native/opus`). Without it
they are SKIPPED, not failed. To run them:

```sh
scripts/opus/build_opus_host.sh            # Linux, macOS; Windows: Git Bash with MinGW GCC, CMake, Ninja on PATH
./gradlew :tajweedformer-jvm:test          # finds build/host-opus/ by itself
```

`TAJWEEDFORMER_OPUS_LIBRARY=<path to the .so / .dylib / .dll>` points the tests at another build, and
`TJ_REQUIRE_OPUS=1` makes a missing library a failure instead of a skip.

On a phone: serve the model folder, the clips and the conformance file with
`python scripts/serve_model.py /m=<sdk_v4.0 transport folder> /audio=<engine>/data/audio /conformance=<engine>/conformance`,
`adb reverse tcp:8765 tcp:8765`, install the test APK and run
`adb shell am instrument -w -e key <manifest key hex> ai.quranlab.tajweedformer.test/androidx.test.runner.AndroidJUnitRunner`.

## Layout

```
native/                     tj_jni.c over zf_tajweed.h (V4) and the engine's front end; litert_npu.c (NPU runner)
tajweedformer/              the Android library (AAR), ai.quranlab:tajweedformer
  src/main/kotlin           the API, plain JVM Kotlin: the same sources on Android and the JVM
    .../npu                 the NPU backend's host side and runner
  src/main/resources        the self-test clip
  src/android/kotlin        Android only: client, SoC, network, microphone, debug flag
  src/androidTest           DeviceEndToEndTest
tajweedformer-jvm/          those sources on the desktop JVM, with the tests
scripts/                    host builds, the scorer table, the device test's server
engine/                     tajweedformer-engine, pinned
```

## Status and roadmap

Done (2.0.0, unreleased): V4 only; manifest format 3 with download profiles, resumable verified
downloads and the unmetered-only NPU policy; the exact CPU engine and the NPU backend with
self-tests and fallback; the raw, decoded and tajweed layers with per-word scores; observer hooks for
the cloud module; V4 bit-exact through the SDK on the desktop JVM and on the Alcatel 1.

Next: the NPU backend on Pixel 8 to 10, an Exynos 2600 and an Exynos 2500 phone; calibration.json's `word` block
from CAL; latency through the SDK on more devices; CI for the engine tests.

## Download size

The SDK downloads only its device's profile of the model folder (`sdk/v4.0`, manifest format 3), gzip on the
wire, resumable, every file checked against the signed manifest. Default context `c16_l64`:

| Profile | What is downloaded | Size (gzip) [on disk] |
| - | - | - |
| Any Android phone (CPU, always) | tokens, calibration, expected table, the shared `layers.zfw` and `frontend.zfw`, the c16_l64 graph and its frames | **56.5 MB** [73.5 MB] |
| + Exynos 2500 NPU (lazy, Wi-Fi, after the CPU self-test) | `npu/exynos2500/c16_l64` (9 chained parts) | +61.5 MB |
| + Exynos 2600 NPU (lazy, Wi-Fi, after the CPU self-test) | `npu/exynos2600/c16_l64` | +61.7 MB |
| + Google Tensor G3 / G4 / G5 NPU (lazy, Wi-Fi) | `npu/tensor_gN/c16_l64` | +120.8 / 120.8 / 121.0 MB |
| Another context later (CPU) | `c24_l72` or `c24_l256` graph and frames | +0.85 MB each |

NPU builds ship only for chips checked on real hardware, and only for the default context `c16_l64`; every
other phone and context runs the CPU engine, which is always there: any NPU load or self-test failure falls
back to it.

## Licence

Quran Lab is a waqf: the SDKs and the models are free to use and are never sold.

The source code in this repository and the TajweedFormer model weights are released under the
Quran-Lab No-Profit License 1.2 ([NPL-1.2](LICENSE)): use, copy, modify and build on them freely,
but never charge for them, for access to them, or for any feature they power. The weights are not
stored in this repository; Quran Lab hosts them itself and serves them to apps by
`cdn.quranlab.ai/models` under the same licence. Request access at
[portal.quranlab.ai/access](https://portal.quranlab.ai/access).

Third-party components keep their own licences, listed in their folders under `third_party/`.
