# Changelog

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
