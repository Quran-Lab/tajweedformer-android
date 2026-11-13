# Changelog

## 3.1.1

The sharing sheet has a new design: the one Quran Lab wrote for all of its SDKs, so that the sheet looks the same in
an Android, an Apple and a web app. No word, no copy deck and no consent rule changed.

- **What a learner sees.** On a phone the consent sheet is a bottom sheet that opens tall, with a grabber; on a wide
  screen it is a centred card at most 480 dp wide. At its top is the name "Quran Lab" beside a small waveform
  emblem, then the title and the text. What is shared, who receives it and how to stop are the three rows of one
  card, each with its icon, placed before the hadith so that they come first. The hadith follows in a card of its
  own, then the line about mistakes; the guardian variant ends with the parent's or guardian's switch. "Share my
  recitations" and "Not now" are always in view, in a bar that does not scroll, one above the other and the same
  size and shape: "Share" filled with the accent, "Not now" filled with a tint of it. The text scrolls under the
  bar and fades out above it, and never shows behind the grabber. Light or dark follows the system's night mode.
  The text follows the system's font size: at the largest sizes the text scrolls, and the buttons grow and keep
  their labels whole, in two lines at most (a label that would need a third line makes both labels a little
  smaller). Arabic and Urdu sheets are laid out right to left, because the deck says so, whatever the device's own
  direction and whether or not the app declares right to left support. The sheet is drawn behind the system bars:
  its top is just below the status bar and its buttons end above the navigation bar. The thank-you, "What is
  shared", the deletion's dialogs and the settings rows take the same colours, corners and type.
- **What a developer sees.** No call changes: the SDK shows its sheet as before, and `SharingSheetHost(client)` in
  Compose. The sheet sets its own type (sizes in sp, which follow the font scale, and its own weights) and no longer
  takes a size, a weight or a colour from the app's theme or typography, except the accent. Running text never
  uses the accent and keeps 4.5 to 1. The accent carries the two button labels, the icons and the emblem's glyph,
  which need 3 to 1 against what is behind them, or 4.5 to 1 when the device asks for more contrast (high contrast
  text, and from Android 14 a contrast level above standard): the "Share" label is white when white has that
  against the accent, else near-black; "Not now" and the emblem's glyph stay in the accent when it has that against
  their tint, the icons when it has it against the card, else they take the text colour. So an app whose accent is
  a blue like `#007AFF` gets a white label and blue marks, and the stricter result when the learner turns on high
  contrast text. The tint behind "Not now" and the emblem comes from the accent. In Compose the sheet opens in the
  same kind of window as the Views dialog, a platform dialog drawn behind the system bars, and is still composed as
  part of the app's composition, so the app's `MaterialTheme` and other composition locals reach it.
- **Changed, for an app that themes the sheet or relies on its defaults.** The accent is still the app's:
  `SharingTheme.accent`, else the app theme's `android:colorAccent` when the app's theme or a library theme it uses
  sets it; in Compose `SharingStyle.accent`, else the app's `MaterialTheme` primary colour. What changed is the case
  without one: the framework's own `colorAccent` (an app theme that sets none) and Material's baseline purple (an
  app without a colour scheme) are no longer taken for the app's, and the sheet uses Quran Lab's green, `#0E6B58`
  (`#62D2B6` in the dark), where 3.1.0 used those or `#1B6E4F`. The defaults of `SharingTheme` are the design's:
  `cornerRadiusDp` 28 (was 20), `titleSizeSp` 26 (was 20), `bodySizeSp` 15.5 (was 15); an app that passes its own
  values keeps them, and the cards and buttons scale their corners by the same factor. The sheet's background,
  text and secondary text default to the design's colours, light or dark, where 3.1.0 took the activity theme's
  (Compose: the `MaterialTheme` colour scheme's).
- **What a theme still changes.** `SharingTheme` and `SharingStyle` keep every field and every field still works:
  background, text, secondary text, accent, corner radius and fonts; `SharingTheme`'s title and body sizes scale the
  title and the rest of the text. New in both, with a default: `dark` (null follows the system's night mode, or a
  dark background that the theme gives; true or false fixes it). A theme cannot change the order of the sheet, hide
  one of its lines or remove the name "Quran Lab".
- **What did not change.** The sheet shows the same copy deck, `2026-11-12.1`, every line of it whole, in the same
  eight languages. The consent version and when the sheet is shown are as in 3.1.0, and so is what each way of
  ending it records, in the Views dialog and in Compose: an ask is counted when the sheet is shown; "Share" records
  a yes; "Not now" records a no; closing the sheet without a button, by the back gesture or a tap beside it as in
  3.1.0 or by a drag down from its top, which is new, records a no, as closing 3.1.0's sheet did; a sheet that
  disappears with its screen (a configuration change that recreates the activity, the app's process ending, the
  Compose host leaving the screen) records nothing, and the next completed session asks again, within the same three
  showings; a sheet left while the app is in the background is still there when the learner comes back. The name at
  the top is a constant of the SDK, written in Latin letters in every language, not a string of the deck.
- **Tests.** The Compose module's unit tests, which CI runs, draw both of the SDK's drawings of the sheet, the Views
  dialog and the Compose sheet, with Android's own renderer on the JVM (Paparazzi, a test dependency of that module
  only: it is in neither AAR nor POM), and check that every line of the deck is drawn whole and nothing else, that
  the name is in every language's sheet whatever the theme, that both buttons have the same size in every language
  at 100 % and 200 % text on a 320 dp wide phone, that the guardian's share button is disabled until the switch is
  on, that the direction comes from the deck on a left to right and on a right to left device, the accent's
  contrast rule, that nothing of the text shows behind the grabber, and that the two drawings put every line in the
  same place within 3 pixels. `SheetDesignTest` checks the colours, the contrast rule and the type scale against
  the design's tables, and `SheetExitTest` what each way of ending the sheet records, against the sharing state and
  its stored file. Both sheets also ran on a phone, an Alcatel 1 with Android 11, from the Compose module's device
  test APK (`SheetHostActivity`, started from a PC with adb; not run by CI), with its three-button navigation,
  light and dark, in English and Arabic: each way of ending them, with the system's own key and touch events,
  reached the ending that 3.1.0 recorded for it. Gesture navigation did not run on a phone: this one, an Android Go
  phone, does not switch to it.

## 3.1.0

A model comes from Quran Lab's server, through the Client, and from nowhere else. The same change in the Android,
Apple, web and C SDKs.

- **Removed: opening a model folder the app supplies.** A model comes from Quran Lab's server through the Client.
  `TajweedModel.open(folder)` is gone, and an app that calls it does not compile. Why: the model is Quran Lab's to
  deliver, verify and update, and an app that carries its own copy cannot be told of a newer or a withdrawn build.
  What replaces it: `client.model()`, and `client.prefetch()` to download ahead. **Migration:** replace
  `TajweedModel.open(folder)` with `TajweedClient(context, key).model()` (on the desktop JVM
  `TajweedClient(key, storage).model()`) and take the model folder out of the app.
- **The Client opens only a model it downloaded itself.** It keeps a record of each download, bound to this device
  (Android: an HMAC key in the Android Keystore, which never leaves it; desktop JVM: a key derived from the machine's
  id and the user name, or a key file in the storage folder on a machine without an id). A model folder in the SDK's
  storage without that record, shipped in the app and copied into place or copied from another device, is deleted
  and the model is downloaded. **An app that updates from an earlier SDK downloads the model once more**, because
  the model it holds has no record; without a connection at that first start `client.model()` fails with
  `needs_connection`. After that a model runs without a network, as before. When the key store fails (locked,
  unavailable), nothing is opened, deleted or downloaded, and the call fails with `storage_failed`.
- **Changed: a refused Client stops running the model and removes it.** When Quran Lab refuses the key or the app (a
  revoked key, a paused or blocked app), the SDK stops running the model and removes the downloaded model from the
  device. Once the key is accepted again the model is downloaded again. The refusals that do it: a key that was
  revoked or that Quran Lab never issued; `app_not_allowed`, `origin_not_allowed`, `key_type_mismatch`;
  `app_blocked`, which is an app blocked by Quran Lab or paused by its developer; `developer_not_approved`.
  `client.model()` and `prefetch` fail with that refusal (`invalid_key`, or `server_rejected` with the refusal's
  message and fix), also at the next start without a network, until a later answer accepts the key. The SDK removes
  every model build it holds, what `removeModels()` removes, in the call that received the refusal; a file the system
  does not let go of then is removed when the open Model is closed or at the next start. Before, a model on the
  device kept running. A missing connection, a timeout, a server error, a network Quran Lab does not serve and any
  answer that names no such reason never stop or remove a model, and a Model that is already open keeps running until
  the app closes it. `sdk_too_old` does what it did. `diagnose()` names the refusal. What the SDK cannot remove: a
  cache that a phone maker's accelerator runtime keeps by itself of a program the SDK loaded into it.
- **Changed: model files are requested with the install's token.** Every request for the model (its manifest, each
  file, each resumed range) carries the app token the SDK already uses for its other calls, next to the key, and
  never follows a redirect. Two refusals of such a request have their own message and fix under `server_rejected`:
  `model_attestation_required` and `model_not_allowed`; Quran Lab's daily limit for model downloads is
  `download_failed` and is not retried in a loop. A refusal of the key while `model.reference.fetch()` downloads is
  that refusal, not `reference_missing`.
- **Changed: the SDK depends on Google's Play Integrity library**, `com.google.android.play:integrity:1.4.0` (an
  `implementation` dependency of the AAR, so an app gets it through the SDK; with the ten libraries it brings it adds
  33,594 bytes to the example app's release APK shrunk with R8, and 34,420 bytes to its debug APK). Quran Lab can
  ask, before it sends the model or takes a shared recording, for proof that the request comes from the app as Google
  Play delivered it, on a real device. The SDK asks Play for an integrity token only when Quran Lab's signed config
  gives it Quran Lab's Cloud project number; without it the SDK asks Play for nothing and nothing changes for an
  install. Where Play services are missing or Play cannot answer, it continues with an unattested token. **Removed:
  the manifest entry `ai.quranlab.tajweedformer.PLAY_INTEGRITY_PROJECT`**: the project number comes from Quran Lab's
  signed config, and an app that set the entry can delete it (it is not read).
- **Changed: the sharing sheet and "What is shared" now say that no human ever listens to your recordings: only
  software learns from them.** A learner who already agreed is not asked again, and their recordings keep the
  consent version they agreed to; a learner asked from now on agrees to `share-2026-11-12` (copy deck
  `2026-11-12.1`, the same in every Quran Lab SDK). The sentence is part of a line every sheet already shows, the
  guardian variant included.
- **Changed: the error catalogue** (the shared `errors.json`): the fix sentence of `model_missing`, the two reasons
  above, and the words of `app_blocked`.
- Tests: the shared vector `model_gate.json` (32 answers of the API and of the model host: which stop and remove a
  model, remembered across a start and cleared by an accepting answer, and the error the app gets), each with a model
  from a real download on the device and without one: a stop leaves no model file and no record and the next accepted
  call downloads every file again, an answer that is not a stop removes nothing; a stop keeps the remembered refusal,
  the device key, the sharing state and an unsent recording; a removal that fails is done at the next start; files
  put back by hand are removed at a start that holds a refusal; a refusal of a model request that an answer of the
  API does not clear; a storage folder filled in advance is not opened and is replaced by a download; a record made
  with another device's key and a record of another build are not valid; a download, then a start with no request for
  a file; an interrupted download resumes; the record is written before the first request; the two ways the key store
  can fail; the headers of a manifest, a file and a range request; the token's renewal; no redirect; Play Integrity
  with and without the project number and where Play fails. These run against the stub servers. The Android
  Keystore's key of the install record ran on a phone, an Alcatel 1 with Android 11, in `InstallKeyDeviceTest` (key
  made, records written and read back after a relaunch, a record of another key refused, a deleted key read as no
  key), which CI does not run; the call to Play ran on no device.

## 3.0.1

One fix: sharing turned off, or what was shared deleted, while a recording is being uploaded, or just before the
app's process ends.

When a learner turns sharing off, or deletes what they shared, the SDK ends the upload in progress and deletes
the recordings it has not sent yet. Since 2.0.0 that did not hold for what was in flight at that moment. A test
written after 3.0.0 was released showed it, in every one of 20 runs of each situation:

- A segment that was being sent when the learner said no was sent to its end. The SDK could not end a request
  that was in flight: not a segment, not the start of an upload, not a request for upload addresses, not a take
  list.
- With a recording still open (a recording stays open between takes since 2.0.3), the uploader went on to the
  recording's next segment after the call had returned, and the open recording's folder was still on the device
  when the call returned.
- "Delete what I shared" asked the server before it stopped the uploads: an upload that began in between sent
  recordings under the contributor that was being deleted.
- A take list that had not been sent stayed on the device. On a desktop JVM on Windows the segment that was being
  sent stayed too, until the next upload.
- When the app's process ended between the write of the learner's no and the delete of the queue (a kill, a crash,
  a battery that ran out), nothing on the device said which recordings were from before the no. They stayed on the
  device while sharing was off, and when the learner turned sharing on again they were uploaded: nine of nine in
  the test, in every one of 20 runs. "Delete what I shared" wrote its no last, after the device's deletes, so the
  same end of the process left sharing on.

In production the sharing sheet has been on for iOS and macOS only.

Fixed, by the five rules every Quran Lab SDK holds from 3.0.1, and tested with the situations the SDKs share
(`vectors30/sharing_off.json`, 20 runs of each, in `SharingOffTest`):

- From the moment `client.sharing.setOn(false)` or `client.sharing.delete()` returns, the SDK hands nothing more
  to the system and has ended the request that was in flight, whichever it is. What the system had already taken
  into its send buffer may still go out. Measured on a phone (Android 11): when sharing is turned off while a
  segment is being sent, the SDK ends the request within about 100 ms and hands over nothing more; what the
  system had already taken into its send buffer still goes out, up to 2,220,032 bytes in the test, which is more
  than one segment. So the segment being sent at that moment can still arrive whole; nothing after it is sent.
  (The same measure on a desktop JVM, over a loopback connection: up to 245,760 bytes on Windows and 4,268,032
  bytes on Linux.)
- The call returns within 3 seconds, also when a request hangs. It never waits for the network.
- Nothing unsent is left on the device: no recording, no segment, no state of the queue, no unsent take list;
  also after the request that was in flight has come back.
- Turning sharing on again works at once: the next recording is uploaded, and the one that was in flight is gone.
- "Delete what I shared" stops the uploads before it asks the server. Accepted, it turns sharing off, as before.
  A deletion the server did not take changes nothing: sharing stays on, nothing on the device is deleted, and the
  uploads go on.
- A recording made under an earlier consent is never uploaded, whatever happened in between: off and on again, the
  process ending between the no and the delete of the queue, any number of recordings (`SharingRestartTest`, the
  shared restart situations, nine recordings, 20 runs of each). The device keeps a count of the learner's changes
  of mind, written in the same write as the answer, and every recording carries the count it was begun under, on
  the device only and never in what is uploaded. A recording with another count, and every unsent recording while
  the learner's yes is not in force, is deleted at the next launch and before every upload pass. The signed
  config's pause is not the learner's no: it keeps the queue, as before.
- "Delete what I shared" writes the no to the device before it asks the server and writes the earlier state back
  when the server does not take the deletion; while the server is asked nothing changes for the learner or the
  app. A process that ends during the ask finds sharing off at its next launch and deletes the queue. A launch
  after a process that ended in the middle of an accepted deletion finishes it before anything else: the queue,
  the status of what was shared, the contributor. When the device cannot store the no (a full disk), the server
  is asked all the same; an accepted deletion then turns sharing off in the running app, deletes the queue, and
  the no is stored as soon as the device can.
- One case stays as it is and is said here: when the process ends after the server took the deletion and before
  the device heard its answer, the device cannot know. Sharing is off at the next launch and no earlier recording
  is uploaded; the status of what was shared stays listed, and when sharing comes on again the first new
  recording is refused by the server once, after which the SDK takes a new contributor and the next recording is
  uploaded.
- A second case stays as it is: the no was stored, the server refuses the deletion, the write that puts the
  earlier state back fails, and the process ends before a retry succeeds. Sharing is off at the next launch and
  nothing is ever uploaded from the queue; the learner turns sharing on again with the switch and is not asked
  again.

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
