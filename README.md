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
  <img alt="status: preview" src="https://img.shields.io/badge/status-preview-2a3b8f?style=flat-square">
  <img alt="model licence: NPL-1.2" src="https://img.shields.io/badge/model%20licence-NPL--1.2-1c1b19?style=flat-square">
</p>

## What you get

<table>
<tr>
<td width="33%" valign="top">

**Hears the recitation**<br>Streaming recognition of Quranic recitation: audio in, and what the model heard comes out as often as every 160 ms on the CPU engine (320 ms by default, including on the NPU; 480 ms for longer context).

</td>
<td width="33%" valign="top">

**Every output of the model**<br>The sounds it heard with confidence and timing, and every head of the network per chunk (lengthening, gemination, ghunna, qalqalah and more): yours to build on.

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

**Nothing to host**<br>The SDK downloads the right model for each device (56.5 MB) from our CDN in six regions, verifies it and keeps it up to date.

</td>
<td valign="top">

**Tajweed scoring in SDK 2.x**<br>For a known ayah, SDK 2.x also scores the take: letters and vowels, madd held too short or too long, a score for every word. Qalqalah checks are experimental.

</td>
</tr>
</table>

### Tajweed scoring in SDK 2.x

Scoring has four presets. **Strict** almost never flags a correct recitation. **Balanced** is the default. **Sensitive** catches more, and **Very sensitive** catches the most, for a teacher who reviews every flag first. The word level follows your learners: **strict** for huffaz and advanced students, **general** for most apps, **beginner** for children and classes. You set them in the portal and your app picks them up without a new build. Scoring is part of SDK 2.x; SDK 3.0 is inference only, so build new judging on the model's output.

## Why TajweedFormer

- **Made for the Quran.** Trained on Quranic recitation, with its own sound units and heads for what tajweed is about.
- **Feedback in the moment.** Output streams while the learner recites, so your app can respond on the word itself, not after the take.
- **One engine on every platform.** The same engine runs the [Android](https://github.com/Quran-Lab/tajweedformer-android), [iOS and macOS](https://github.com/Quran-Lab/tajweedformer-apple), [web](https://github.com/Quran-Lab/tajweedformer-web) and [Windows and Linux](https://github.com/Quran-Lab/tajweedformer-c) SDKs, all set from one portal.
- **Free, and it stays free.** Quran Lab is a non-profit waqf: the SDKs and models are never sold. The licence, NPL-1.2, asks three things: no paywall on model features, no results presented as rulings, and show the notice.

## Built for AI developers

Copy the setup prompt from [portal.quranlab.ai](https://portal.quranlab.ai) and paste it into your coding assistant. That one prompt sets everything up: the package, your key, and the settings for the learners your app is for. [AGENTS.md](AGENTS.md), beside this README, gives the assistant the complete API, so the code it writes uses names that exist. Prefer to do it by hand? Get a key in the portal and start below.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral() } }

// app/build.gradle.kts
dependencies { implementation("ai.quranlab:tajweedformer:2.0.1") }
```

Maven Central serves 2.0.1 today. 2.0.2 is tagged in this repository and follows there; the quick start below is the same for both.

## Before you start

| | |
| - | - |
| **Key** | An Android key from [portal.quranlab.ai](https://portal.quranlab.ai), for your app's package name. |
| **Devices** | Android 8.0 (API 26) and later, on `arm64-v8a` and `armeabi-v7a`. There is no x86_64 library: run it on a phone or an ARM emulator image, not on an x86_64 emulator. |
| **Permissions** | `RECORD_AUDIO`: declare it in your manifest and ask for it at run time (the quick start does both). The SDK's own manifest brings `INTERNET` and `ACCESS_NETWORK_STATE`; WorkManager, which it uses for background work, brings `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`. |
| **Download** | The first run downloads the model: 56.5 MB, once (73.5 MB on disk), on any network. A phone with a supported NPU fetches its build later, on an unmetered network only. |
| **Sharing** | Recognition needs nothing more. If your app offers recording sharing, uploads need Play Integrity: add `com.google.android.play:integrity` and pass Quran Lab's project number in `CloudOptions`. |
| **Build** | Built and tested with JDK 17, Android Gradle Plugin 8.2 and Kotlin 1.9. |
| **Takes** | A recognizer runs one take at a time. |

## Quick start

From your key to what the model hears from the microphone, as one screen. The manifest needs
`<uses-permission android:name="android.permission.RECORD_AUDIO" />`.

```kotlin
import ai.quranlab.tajweedformer.TajweedHeads
import ai.quranlab.tajweedformer.TajweedRecognizer
import ai.quranlab.tajweedformer.android.Microphone
import ai.quranlab.tajweedformer.android.TajweedFormerAndroid
import ai.quranlab.tajweedformer.cloud.CloudOptions
import ai.quranlab.tajweedformer.cloud.android.QuranLabAndroid
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuickStartActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val heard = TextView(this).also { setContentView(it) }
        QuranLabAndroid.configure(this, CloudOptions(key = "qlk_v1_..."))   // your key from portal.quranlab.ai
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        scope.launch {
            // the first run downloads and verifies the model; the microphone and the recognizer both run at 48 kHz
            val recognizer = TajweedFormerAndroid.client(this@QuickStartActivity)
                .recognizer(TajweedRecognizer.Options(sampleRate = 48_000))
            val take = recognizer.startSession()
            val chunks = mutableListOf<TajweedHeads>()
            launch {   // every 320 ms: what the model heard so far
                take.chunks.collect { chunk ->
                    if (chunk.index < chunks.size) chunks.subList(chunk.index, chunks.size).clear()   // a chunk sent again
                    chunks += chunk.heads
                    heard.text = recognizer.decode(TajweedHeads.concat(chunks)).text
                }
            }
            Microphone.samples(48_000).collect { samples -> take.append(samples) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

This is the app in [`samples/quickstart`](samples/quickstart), which CI compiles. `tf.recognizer()` downloads the
CPU profile on the first call (any network), verifies it, runs the CPU self-test, and serves on the exact CPU
engine; the NPU is promoted in the background after the first session and used from the next one once it is ready
and warm ("CPU first, then the accelerator" below). `recognizer.decode(...)` is what the model heard: each token
with its confidence, runner-up and times; each chunk carries every head of the network you selected. To end the
take, stop collecting the microphone and call `take.finish()`.

In SDK 2.x a take of a known ayah can also be scored: `take.finishAndScore(surah = 1, ayah = 2)` gives verdicts per
site and per word. In a real app call `QuranLabAndroid.configure` once, in `Application.onCreate`.

## More

The full reference (every layer of the API, backends, what a device downloads, platform settings, recording sharing, building and testing) is in [AGENTS.md](AGENTS.md), written for coding assistants and just as readable for you. Guides: [portal.quranlab.ai/docs](https://portal.quranlab.ai/docs).

## Licence

Quran Lab is a waqf: the SDKs and the models are free to use and are never sold.

The source code in this repository and the TajweedFormer model weights are released under the
Quran-Lab No-Profit License 1.2 ([NPL-1.2](LICENSE)): use, copy, modify and build on them freely,
but never charge for them, for access to them, or for any feature they power. The weights are not
stored in this repository; Quran Lab hosts them itself and serves them to apps by
`cdn.quranlab.ai/models` under the same licence. Get access at
[portal.quranlab.ai](https://portal.quranlab.ai).

Third-party components keep their own licences, listed in their folders under `third_party/`.
