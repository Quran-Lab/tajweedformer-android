<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/quranlab-mark-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset=".github/assets/quranlab-mark-light.svg">
    <img alt="Quran Lab" src=".github/assets/quranlab-mark-light.svg" width="51" height="48">
  </picture>
</p>

<h1 align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/banner-dark.svg"><img alt="TajweedFormer for Android" src="docs/banner-light.svg" width="100%"></picture></h1>

<p align="center">TajweedFormer V4 for Android: recitation audio in, the model's output out while the learner recites. On the device, offline, and free.</p>

<p align="center">
  <img alt="platform: Android, JVM" src="https://img.shields.io/badge/platform-Android%20%7C%20JVM-1c1b19?style=flat-square">
  <img alt="bit-exact: 35 of 35 parity clips" src="https://img.shields.io/badge/bit--exact-35%2F35%20parity%20clips-2a3b8f?style=flat-square">
  <img alt="version: 4.0.3" src="https://img.shields.io/badge/version-4.0.3-2a3b8f?style=flat-square">
  <img alt="model licence: NPL-1.2" src="https://img.shields.io/badge/model%20licence-NPL--1.2-1c1b19?style=flat-square">
</p>

## What you get

<table>
<tr>
<td width="33%" valign="top">

**Hears the recitation**<br>Streaming recognition of Quranic recitation: audio in, and what the model heard comes out as often as every 160 ms (320 ms by default, 480 ms for longer context).

</td>
<td width="33%" valign="top">

**Every output of the model**<br>The sounds it heard with confidence and timing, and every head of the network per chunk (lengthening, gemination, ghunna, qalqalah and more): yours to build on.

</td>
<td width="33%" valign="top">

**Native on Android**<br>Kotlin for Android 8.0+ and the desktop JVM, over the Quran Lab Core. It runs the exact CPU engine on every phone.

</td>
</tr>
<tr>
<td valign="top">

**Private by default**<br>Offline, on the device. A recording leaves it only when the learner agrees to share it.

</td>
<td valign="top">

**Nothing to host**<br>The SDK downloads the right model for each device (55.5 MB) from Quran Lab's servers, verifies it and keeps it up to date.

</td>
<td valign="top">

**Data for your own judge**<br>The SDK scores nothing. The model's thresholds come with it as read-only data, and the expected units of each ayah where the served build carries them, so your app can judge a recitation its own way.

</td>
</tr>
</table>

## Why TajweedFormer

- **Made for the Quran.** Trained on Quranic recitation, with its own sound units and heads for what tajweed is about.
- **Feedback in the moment.** Output streams while the learner recites, so your app can respond on the word itself, not after the take.
- **One engine on every platform.** The same engine runs the [Android](https://github.com/Quran-Lab/tajweedformer-android), [iOS and macOS](https://github.com/Quran-Lab/tajweedformer-apple), [web](https://github.com/Quran-Lab/tajweedformer-web) and [Windows and Linux](https://github.com/Quran-Lab/tajweedformer-c) SDKs, all set from one portal.
- **Free, and it stays free.** Quran Lab is a non-profit waqf: the SDKs and models are never sold. The licence, NPL-1.2, and Quran Lab's access terms ask two things: no paywall on model features, and no output presented as an authoritative religious ruling. A line telling learners that automatic feedback can be wrong is encouraged, not required.

## Built for AI developers

Copy the setup prompt from [sdk.quranlab.ai](https://sdk.quranlab.ai/go/gh-tajweedformer-android) and paste it into your coding assistant. That one prompt sets everything up: the package, your key, and the first calls. [AGENTS.md](AGENTS.md), beside this README, gives the assistant the complete API, so the code it writes uses names that exist. Prefer to do it by hand? Get a key in the portal and start below.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral() } }

// app/build.gradle.kts
dependencies { implementation("ai.quranlab:tajweedformer:4.0.3") }
```



The AAR carries the Quran Lab Core (`libqlcore.so`) and depends on LiteRT 2.2.0, kept for NPU builds; at 4.0's launch
every phone runs the Core's CPU engine. Then add your app's signing certificates on your Android key's page at
[sdk.quranlab.ai](https://sdk.quranlab.ai/go/gh-tajweedformer-android) ("Before you start").

## Before you start

| | |
| - | - |
| **Key** | An Android key from [sdk.quranlab.ai](https://sdk.quranlab.ai/go/gh-tajweedformer-android), for your app's package name. |
| **Signing certificates** | On your Android key's page, under "Signing certificates (SHA-256)", add the app signing key certificate and the upload key certificate (Play Console: Test and release, App integrity, App signing). Until you do, your installs are not hardware-verified for your app. |
| **Devices** | Android 8.0 (API 26) or later. Phones and tablets are `arm64-v8a` and `armeabi-v7a`. `x86_64` is for the Android emulator on an Intel or AMD PC and for x86 Chromebooks: there the SDK runs its CPU engine only, never an accelerator. It needs a development key: Quran Lab leases the `x86_64` Core to development keys only. On another ABI the SDK fails with `runtime_unsupported`. |
| **Permissions** | `RECORD_AUDIO`: the SDK's manifest declares it and `model.listen()` asks for it at run time. The SDK's manifest also brings `INTERNET` and `ACCESS_NETWORK_STATE`; WorkManager, which it uses for background work, brings `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`. |
| **Download** | The first `client.model()` or `client.prefetch()` downloads the model, encrypted: 55.5 MB, once (68.4 MB on disk), on any network, and needs a connection. At 4.0's launch every phone runs the Core's CPU engine: Quran Lab serves no NPU build until one is faster than the CPU engine. |
| **Offline** | The first run needs a connection; after that the model works offline, and the SDK keeps itself up to date in the background when the phone is online. `needs_connection` means the SDK needs to reach Quran Lab to continue. A Model that is open keeps running. |
| **Model** | The model runs only inside the Quran Lab Core, in the AAR: its files are encrypted from Quran Lab's server to the device, and no plain model file is ever written. When Quran Lab refuses the key or the app, the model's files are removed. |
| **Analytics** | Anonymous counts and durations (never audio, text, names or device identifiers) and reports of the failures your learners hit are always sent; you find them at sdk.quranlab.ai under your app, Problems included. |
| **Build** | Built and tested with JDK 17, Android Gradle Plugin 8.2 and Kotlin 1.9. |
| **Takes** | A Model runs one take (one Session) at a time; a take holds at most 20 minutes of audio. |

## Quick start

```kotlin
suspend fun quickStart(context: Context, heard: TextView) {
    val client = TajweedClient(context, "qlk_v1_...")       // your key from sdk.quranlab.ai; one Client per process
    val model = client.model()                              // the first call downloads and verifies the model
    val session = model.listen(TajweedSessionOptions(endOnSilenceMs = 1500))   // asks for the microphone itself
    session.chunks.collect { chunk -> heard.text = chunk.decoded.text }       // what the model heard so far
}
```

These are the four calls of every Quran Lab SDK: Client, model, listen, read. The code is
[`examples/quickstart`](examples/quickstart)'s `QuickStart.kt`, which CI builds. The app beside it,
[`ExampleActivity.kt`](examples/quickstart/src/main/kotlin/ai/quranlab/tajweedformer/example/ExampleActivity.kt), is
the smallest screen around those calls, in one file: the model loading with its download progress, "Play sample" (a
sample clip, no microphone), "Recite", the tokens as they come (tentative ones dimmed) and "Diagnose". Its
application id is `ai.quranlab.tajweedformer.example`: a key works for the first app that used it, so run the example
with a key made for that identifier ([its README](examples/quickstart/README.md) says how). Nothing else is needed:

- **The microphone.** `model.listen()` asks for `android.permission.RECORD_AUDIO` when it has to (the SDK's manifest
  declares it; a refusal is the error `microphone_denied`), captures at the device's own rate and carries that rate
  to the Session. No sample rate appears in your code.
- **The end of a take.** With `endOnSilenceMs` (500 to 60000) the take finishes by itself after that much silence
  following speech: the last chunk has `decoded.isFinal`, `chunks` completes and `session.finish()` returns the
  take's Heads. Silence is where the model writes nothing, on `log_probs` and on its timing head, so a held sound
  is speech, however long, and does not end the take. In a noisy room a take may not end on silence, so keep a
  Stop button (`session.finish()`; the example has one); the 20 minute limit ends a take in any case. Values under
  1000 can end a take in a breath pause between two words; the quick start uses 1500, and the minimum is 500.
  Without it the take runs until `finish()` or `cancel()`.
- **Background.** A `listen` take ends when the app leaves the foreground (`Ended(BACKGROUND)`); it is not paused.
  Start a new one when the app comes back: the example does it in `onStart` with one line.
- **Your own audio.** `model.session()` and `session.push(samples)` replace `listen()`; everything else is the
  same. `TajweedSessionOptions(sampleRate = ...)` names the rate of what you push (default 16000).

## More

Moving an app from 3.x: [docs/migrating-to-4.md](docs/migrating-to-4.md). SDK 3.x stops receiving the V4 model on 15 January 2027, 00:00 UTC. The full reference (every layer of the API, backends, what a device downloads, platform settings, recording sharing, building and testing) is in [AGENTS.md](AGENTS.md), written for coding assistants and just as readable for you. Guides: [sdk.quranlab.ai/docs](https://sdk.quranlab.ai/go/gh-tajweedformer-android?to=/docs).

## Problems and wishes

Something does not work, something is missing, or you want to tell us how it went:
[open an issue](https://github.com/Quran-Lab/tajweedformer-android/issues/new/choose). There is a form for a bug, one for a
wish and one for a word of feedback. Issues are public, so leave out keys and anything that came from a learner.
A security problem does not go into an issue: use "Report a vulnerability" on the
[Security tab](https://github.com/Quran-Lab/tajweedformer-android/security). A coding assistant can file an issue for you; it
shows you the text first.

## Licence

Quran Lab is a waqf: the SDKs and the models are free to use and are never sold.

The source code in this repository and the TajweedFormer model weights are released under the
Quran-Lab No-Profit License 1.2 ([NPL-1.2](LICENSE)): use, copy, modify and build on them freely,
but never charge for them, for access to them, or for any feature they power. The weights are not
stored in this repository; Quran Lab hosts them itself and serves them to apps by
`cdn.quranlab.ai/models` under the same licence. Get access at
[sdk.quranlab.ai](https://sdk.quranlab.ai/go/gh-tajweedformer-android).

Third-party components keep their own licences, listed in their folders under `third_party/`.
