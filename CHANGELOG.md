# Changelog

## 3.0.0

A breaking release. The SDK is inference only: it downloads and verifies the model, runs it on the device and gives
the app the model's output. The scorer, the tajweed settings and the verdict types are gone, and every entry point
has a new name. [docs/migrating-to-3.md](docs/migrating-to-3.md) maps each 2.x call to its 3.0 name and says what
an app that used the verdicts builds itself. The public API is in [docs/api.md](docs/api.md).

### Breaking changes

- No scoring. `finishAndScore`, `TajweedResult` and its verdicts, `TajweedSettings`, presets, word levels and the
  portal's tajweed block are removed. What the scorer read ships as data: `model.calibration` (the calibration as
  shipped, read-only, never applied by the SDK) and `model.reference` (the expected units of every ayah, a separate
  download through `reference.fetch()`).
- Five things to know, with the same names in every Quran Lab SDK: `TajweedClient` (the key, one per process; a
  second is `client_exists`), `TajweedModel`, `TajweedSession` (one take; a Model has one open Session,
  `session_open`), `TajweedHeads`, `TajweedBackend`. `QuranLabAndroid`, `TajweedFormerAndroid`, `TajweedRecognizer`,
  `CloudOptions` and `SdkObserver` are no longer public. One package, `ai.quranlab.tajweedformer`; 23 public types
  where 2.0.3 had 186; the library is in explicit API mode.
- One error type, `TajweedError`, with `code`, `message`, `fix` and `docs`, from the catalogue of 26 codes the SDKs
  share. `runtime_unsupported` is the error on a device the SDK has no native library for; its fix names where the
  SDK runs.
- `server_rejected` says which refusal it was and what to do about that one: for the refusals Quran Lab's server
  names (an account without access yet, a key of the wrong type, a key that is not for this app, a blocked app and
  the others) the message and the fix are that refusal's own, with the server's code in brackets.
- 3.0.0 includes the fix of 2.0.3: a class Android's verifier refused, which crashed every app that did not shrink
  its code at the moment the Client was made. It also has 2.0.3's consumer rules, so an app that shrinks with R8
  builds with no rule of its own (the example app's release build shrinks that way, and CI assembles it). And an app
  whose manifest lost the INTERNET permission no longer crashes in the SDK's background work: the calls that need
  the network say `network_not_permitted`, the 26th code, and the background work waits as it does without a
  connection.
- Models by version and build. The signed manifest carries the model's version; `model.version` is what an app asks
  for (`client.model("4.0.0")`), `model.build` names the exact files. A 2.x build does not open in 3.0
  (`model_incompatible`). The engine is 3.0 (API 4, no scorer).
- `samples/quickstart` is `examples/quickstart`.

### Added

- `model.listen()`: the SDK asks for the microphone, captures at the device's rate and feeds the take. An
  interruption pauses it (`Interrupted`, `Resumed`), a route change opens the capture again, the background ends it
  (`Ended(BACKGROUND)`). `model.listen(clip)` and `model.listen(samples, sampleRate)` run audio the app has through
  the same path, at the speed it was recorded or as fast as the engine takes it; `model.sampleClips` are the
  model's own short recitations, so an example or an emulator shows real output with no microphone.
- `TajweedSessionOptions(endOnSilenceMs = ...)`, 500 to 60000 ms: the take finishes by itself after that much
  silence following speech. `chunk.speech`, and the events `SpeechStarted` and `SpeechEnded`, come from the model's
  own output: a 40 ms frame is speech when `log_probs` or the timing head has a token on it. A held sound is
  therefore speech, however long, and does not end a take.
- The decoded layer on every chunk (`chunk.decoded`: tokens, words, text, frames) for the whole take so far, with
  `settled` (tokens that will not change any more) and `isFinal`.
- `client.prefetch`, `client.storage`, `client.removeModels()`, and `client.diagnose()`: why it does not work, in
  one object that is safe to paste into an issue. `prefetch(unmeteredOnly = true)` waits while the connection is
  metered or absent; `diagnose().connection` says what it is.
- An `x86_64` library in the AAR, for the Android emulator on an Intel or AMD PC and for x86 Chromebooks. There the
  SDK runs its CPU engine only, never an accelerator. The 35 conformance clips are bit-exact on it through the
  library. An app that does not ship to Chromebooks keeps it out of its release build with one `abiFilters` line
  (README).
- `session.reportRecited(surah, ayahFrom, ayahTo)` and `client.analytics.reportChecks(rule, checked, flagged)`:
  the app's own counts for its statistics at sdk.quranlab.ai. Nothing is sent when they are not called.
- The accelerator's golden test compares raw outputs against the build's signed tolerance (`golden_raw`), not
  verdicts.
- The context of a take: the app's (`TajweedSessionOptions(context = ...)`), else the one set for the app at
  sdk.quranlab.ai (`inference.context` of the signed config), else the model's default.

### Bounds

- A take holds at most 20 minutes of audio. At the limit it ends by itself (`Ended(LIMIT)`) with its Heads kept for
  `finish()`; a `push` after it is `session_closed`.
- `chunks` and `events` keep at most 256 chunks and 64 events for a collector that is late, and drop the oldest.
  The last chunk and `Ended` are never the ones dropped; `client.diagnose()` counts dropped chunks.
- A shared recording never holds more than 20 minutes; at most ten seconds of audio wait in memory for its encoder,
  one second of fed audio for a pre-roll, and a take that starts with 30 s or less of room under the cap.

### Recitation sharing

Reached through `client.sharing`. What a learner is asked, and what a recording carries, do not change.

- Takes in a row share one recording (this was 2.0.3, which is released as part of 3.0.0). Shared recordings of a
  real app began or ended in the middle of speech, because the app stops and starts its takes while the learner is
  still reciting. When a take ends, its recording stays open for the join window (`flags.share_join_ms` of the
  signed config, 0 to 10000 ms, 3000 when absent, 0 never joins). A take that starts within it at the same input
  rate, in the foreground, with sharing on and the same contributor, consent version and model, continues the same
  Ogg stream: no new headers, the encoder and the resampler keep their state, segments are cut by the same plan
  counted across the takes. The pause between the takes gets no audio. A recording of two or more takes sends where
  each take starts and the pause before it once, after its last segment (`POST /v1/uploads/{id}/takes`).
- `client.sharing.feed(samples, sampleRate)`: audio the app already captures outside takes. The last second before
  a take goes in front of it (the pre-roll) and up to half a second after it follows it (the tail), so speech is
  not cut at the edges. A fed buffer continues the audio before it when it comes within its own length plus
  500 ms; otherwise what was kept is dropped. Only with sharing on and the learner's yes; the background and
  sharing going off keep nothing. An app that uses `model.listen()` gets no pre-roll and no tail.
- The session JSON gains `take_seq` (this install's count of shared recordings), `gap_ms_prev` (the time since its
  previous recording, rounded to 100 ms, up to an hour) and `decode.model_build`. "Delete what I shared" starts
  the count again.
- The 20 minute cap is exact, in samples. A recording closes on the sample that fills it, as a whole recording, and
  the take's next sample opens a new one (the next `take_seq`, `gap_ms_prev` 0). A take that starts with 30 s or
  less of room is held in memory until it ends: it joins when it fits, else the recording closes without it and
  the take starts a new one with all its audio. (2.0.3's code let a take that joined with more room run past 20
  minutes.)
- With join window 0 a recording still waits for its tail, at most 1.5 s.
- A take list the server refuses with 400 is no longer dropped: it stays pending and is tried again on the
  outbox's backoff until it is accepted, sharing goes off or the recording is seven days old. The issue is
  reported once for the list.

### Telemetry

- Counted per model version, build, context and backend: takes, seconds of audio, of speech and of silence, tokens
  per head, compute seconds. The fields derived from scoring are gone. `reported` lists exactly the app-reported
  fields a day's body carries.
- Accelerator incidents carry the model version, the build and the raw divergence against the tolerance.

## 2.0.3

Two fixes, so that an app on 2.0.x hits neither. Update to 2.0.3: 2.0.0, 2.0.1 and 2.0.2 crash an app at launch
when the app does not shrink its code, and stop its build when it does.

- The SDK crashed the app at its first call (`QuranLabAndroid.configure`) with
  `java.lang.VerifyError: Verifier rejected class ai.quranlab.tajweedformer.cloud.Telemetry ... expected to be within
  a catch-all for an instruction where a monitor is held`. Every Android version is affected (the verifier has had
  this rule in every release), in every debug build and in a release build that does not shrink its code. A release
  build that shrinks with R8 drops the method, because nothing calls it, and starts: that is how the fault stayed
  unseen. No call has to change.
- The cause was one private `inline` function with `synchronized` inside `try/catch`. The copy Kotlin keeps of such
  a function is bytecode the JVM accepts and Android refuses, so no unit test on the JVM could see it. It is a plain
  function now.
- `scripts/check_monitor_handlers.py` applies Android's rule to every class of the AAR and fails the build on a
  method Android would refuse. It runs in CI and before every upload to Maven Central, and CI first proves that it
  still rejects the class 2.0.2 shipped (`scripts/testdata/Telemetry-2.0.2.class`).
- An app that shrinks its code with R8 no longer needs rules of its own. Before, R8 stopped the build with
  "Missing classes detected while running R8" for ten classes of optional libraries the SDK refers to (Play
  Integrity and the Google Play services tasks API) until the app added `-dontwarn` lines itself. The AAR's
  consumer rules now carry exactly those ten lines, and no keep rule:
  `com.google.android.gms.tasks.Task`, `com.google.android.gms.tasks.Tasks`,
  `com.google.android.play.core.integrity.IntegrityManagerFactory`,
  `com.google.android.play.core.integrity.StandardIntegrityManager` and its nested classes
  `PrepareIntegrityTokenRequest`, `PrepareIntegrityTokenRequest$Builder`, `StandardIntegrityToken`,
  `StandardIntegrityTokenProvider`, `StandardIntegrityTokenRequest`, `StandardIntegrityTokenRequest$Builder`.
  CI builds the sample app as a release build with R8 and no rule of its own, so the rules cannot go stale.
- Nothing else changed in 2.0.3.

## 2.0.2

The in-session failover no longer punishes a healthy accelerator (a developer measured two 2.0.1 rules firing on
normal recitation: blank output during held vowels and madd, and a deadline missed by the first chunk after the
accelerator sat idle).

- Blank output is a suspicion: when the NPU writes nothing over audio with sound (4 s of blank output), the same audio is
  checked on the CPU engine in the background while the NPU keeps serving. The CPU blank too: nothing happens
  (`promotions[].blank_confirmed_ok`). The CPU writing tokens: the take switches to the CPU from the first silent
  chunk, with nothing lost, and the incident carries the divergence.
- Behaviour change for apps that read chunks: after a confirmed disagreement, the chunks from the first silent one
  are delivered again with the SAME `index` and `TajweedChunk.replaces = true` (they replace what the NPU wrote
  there). An app that keeps or draws chunks must replace the entries of that index and drop what it drew from that
  index on, not append them. Takes without a disagreement are unchanged.
- A cold start is not a failure: a warm-up when a take starts and on `TajweedFormerClient.appActivated()` for hosts without activities; the first chunk of a take gets
  at least 1 s (max(1000 ms, 10 x p90)); later limits come from warm chunks only.
- A late chunk is served by the CPU and demotes nothing; late chunks in 3 sessions within 24 h, or one confirmed
  disagreement with the CPU, pause the build for 6 h (no new golden test). Off for good only for NaN or out-of-range
  output, a second confirmed disagreement, a golden or CPU mismatch, or a crash. An exception from the NPU keeps the
  2.0.1 rule (tested again a day later, at most three times).
- On the first launch of 2.0.2, builds that 2.0.1 set aside for silence or a late chunk are tested again and
  promoted again.
- Incident reports are kept on the device (7 days) even before `QuranLabAndroid.configure` is called, and sent once Quran Lab is
  configured and online. An app that never configures Quran Lab sends none.
- Staying on 2.0.1: no option keeps the NPU without the automatic rules (`client.accelerator(model)?.forced = Backend.NPU` still applies every failover rule);
  after a demotion for silence or a late chunk, `AcceleratorStore(client.store.root).forget()` before any recognizer is made, at the next launch makes the device test and promote again. Upgrading to
  2.0.2 is the fix.

## 2.0.1

### CPU first, then the NPU

- The first install downloads only the CPU files of the default context, and the first session runs on the
  exact CPU engine; no NPU file is fetched or compiled before it.
- NPU promotion is a persisted, durable pipeline (WorkManager) per model revision, context, SoC, OS build and
  device: download on an unmetered network (preferably a QLPD1 delta rebuilt on the device from the CPU
  weights, SHA-256 checked before LiteRT sees it), load, warm-up, then a golden test of all five self-test clips
  through the NPU and the CPU engine in both directions (tokens, flagged sites, per-word verdicts; the planted
  mistake must be found), within the build's tolerance from the signed manifest. A passing build is always
  promoted; its speed is reported only. Retries after 1, 6 and 24 hours; never for a wrong result, NaN or crash.
- The swap happens only between sessions, to a warm NPU; a promoted device reloads it at the next launch and
  usually starts its first session on it, never waiting.
- In-session failover: an exception, NaN or out-of-range output, blank output over speech or a missed deadline
  switches the session to the CPU at that chunk, rebuilt from the last 5.12 s of audio; the NPU is set aside
  for that revision and OS. The CPU weights are released while the NPU serves (the engine would otherwise hold
  a private copy in its dot-product layout).
- `accel_deny` rules in the signed config keep the NPU off on matching devices; every incident is reported
  (`promotion_failed`, `accelerator_failover`), and the telemetry gains a `promotions` entry.

### Recitation sharing

- The sharing sheet is offered where Quran Lab decides, per app, in the signed config;
  `CloudOptions.contributions` is ignored, and without a signed config nothing is offered. Nothing is recorded
  or sent before the learner answers "Share".
- Asking: the sheet is shown at most three times per install, ever (the count carries over from 2.0.0);
  "Not now" may lead to a later ask within those three, a yes ends asking forever, and so does turning
  sharing off after a yes (the app's settings can still turn it on). A yes persists across copy decks, app
  wording and the upgrade from 2.0.0 (no sheet, no pause), and each session carries the version the learner
  agreed to. Stopping and deleting work whatever the learner agreed to.
- The consent sheet, default or yours: `SharingTheme` (Views) and `SharingStyle` (Compose) theme the SDK's
  sheets; your own UI through `Sharing.prompts` with the SDK's localized strings and `accept` / `decline`; your
  own wording with `Sharing.setWording`, recorded as consent version `custom-<24 hex>` with its SHA-256 sent with
  each session for Quran Lab to review.
- Copy deck 2026-11-09.1 (consent `share-2026-11-09`) in eight languages: no longer says the recited ayah is
  sent (it is not), deletion from Quran Lab's servers is immediate, and the recovery and deletion-failure lines
  are translated.
- Adaptive Opus bitrate on bad networks: 192, 96, 64 or 32 kbit/s, lowered when uploads fail or back up and
  raised again when they succeed, changed between segments of the same Ogg stream
  (`Contribution.segmentBitrates`). A 408 on an upload is now retried.

### Telemetry and the platform client

- Deltas during the day (at launch, every 15 minutes in use, at background), each with a random `report_id`;
  new aggregate counters (launches, ayahs, ayahs per surah, words, tajweed rule checks and flags, sharing
  sheet outcomes, accelerator promotions). Register once per UTC day per install.
- In a debuggable app, one log line when Quran Lab's signed config names a newer SDK.
- `docs/platform-client.md`: what the SDK sends to Quran Lab, when, and what an app can change.

## 2.0.0 (first public release)

- TajweedFormer V4: `TajweedModel` opens a signed `sdk/v4.0` folder (signature, `min_sdk_version`,
  every file's size and SHA-256); `TajweedRecognizer` streams audio through the exact engine and
  keeps every head of the network; `finishAndScore` returns per-site tajweed verdicts with their
  evidence.
- Tajweed settings in three layers (app code, the portal's block, `calibration.json`), identical in
  every TajweedFormer SDK.
- Bit-exact: the 35 conformance clips give the engine's expected outputs, every output of every
  chunk, through this SDK's own API.
- Maven `ai.quranlab:tajweedformer`, package `ai.quranlab.tajweedformer`.
