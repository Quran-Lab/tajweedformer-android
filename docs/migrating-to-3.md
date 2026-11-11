# Migrating from 2.x to 3.0

3.0.0 is a breaking release. The SDK is inference only: it downloads and verifies the model, runs it on the
device and gives your app the model's output. It no longer judges a recitation. Every 2.x entry point has a new
name, there is one package (`ai.quranlab.tajweedformer`), and the public surface is 23 types where 2.x had 186.

If your app only showed what the model heard, the change is a few lines (section 1). If it used the verdicts
(`finishAndScore`, `TajweedResult`, `TajweedSettings`), read section 3: what the scorer did is now your app's code,
and the data it read still ships.

## 1. The calls

| 2.x | 3.0 |
| --- | --- |
| `QuranLabAndroid.configure(context, CloudOptions(key = ...))` | `val client = TajweedClient(context, key)`. One Client per process; a second one is the error `client_exists`. |
| `TajweedFormerAndroid.client(context).recognizer(TajweedRecognizer.Options(...))` | `val model = client.model()`. It downloads on the first call, verifies, opens and warms. `client.model("4.0.0")` asks for one version. |
| `recognizer.startSession()` | `model.session(options)` when your app pushes audio; `model.listen(options)` when the SDK captures the microphone. |
| `Microphone.samples(48_000).collect { take.append(it) }` and the permission code around it | `model.listen()`: it asks for `RECORD_AUDIO`, captures at the device's rate and carries the rate to the Session. `Microphone.samples()` is still there for apps that only want samples. |
| `take.append(samples)` | `session.push(samples)` |
| `take.chunks` (a Flow of `TajweedChunk` with `heads`) | `session.chunks` (a Flow of `TajweedChunk` with `heads`, `decoded`, `speech`) |
| `recognizer.decode(TajweedHeads.concat(chunks)).text` | `chunk.decoded.text`: every chunk carries the decoded layer of the whole take so far. |
| `take.finish()` | `session.finish()`, which returns the take's `TajweedHeads`; `session.cancel()` keeps nothing. |
| `TajweedRecognizer.Options(sampleRate, context, heads, allHeads)` | `TajweedSessionOptions(sampleRate, context, heads = listOf("all"), topK, endOnSilenceMs, backend, pace)` |
| `RecognizerException` subclasses | One type, `TajweedError`, with `code`, `message`, `fix`, `docs`. The codes are the same in every Quran Lab SDK. |
| `TajweedFormer.events.add(SdkObserver)` | `session.events` (speech started and ended, interruptions, a backend change, the end), `model.backend`, `client.diagnose()`. |
| `QuranLab.sharing`, `QuranLabCloud.background()` | `client.sharing`, `client.background()` (automatic on Android). |
| `CloudOptions(analytics, audience, ...)` | `TajweedClientOptions(appVersion, storage, analytics, audience)` |

The quick start is now four calls: Client, model, listen, read. See the README.

## 2. What is new and what changed in behaviour

- A take ends by itself with `endOnSilenceMs`, when the app leaves the foreground (`Ended(BACKGROUND)`; start a
  new take when it returns), when a clip runs out, and at 20 minutes of audio (`Ended(LIMIT)`).
- `chunk.decoded.settled`: the first `settled` tokens will not change any more. Draw them once.
- `chunks` and `events` keep at most 256 chunks and 64 events for a collector that is late; the oldest are
  dropped. Nothing is lost for an app that reads: every chunk has the whole take decoded, and `finish()` returns
  every Head.
- Models have a version (`model.version`, what you ask for) and a build (`model.build`, the exact files).
- The context (response time) of a take is `TajweedSessionOptions(context = ...)`, else the one set for the app at
  sdk.quranlab.ai, else the model's default. The portal's tajweed settings no longer reach the SDK.
- `model.calibration` and `model.reference` are data (section 3).
- A model build made for 2.x does not open in 3.0 (`model_incompatible`), and a 3.0 build does not open in 2.x.

## 3. An app that used the verdicts

2.x: `take.finishAndScore(surah, ayah)` returned a `TajweedResult` (sites, madd, letters, words) under
`TajweedSettings` (presets, per-class overrides, word levels), with the portal's tajweed block and the
calibration behind it. All of that is gone from the SDK. What the scorer read is still there:

| The scorer read | 3.0 gives |
| --- | --- |
| The take's outputs | `session.finish()`: `TajweedHeads` with `log_probs`, `inter1_log_probs`, `timing_log_probs`, `gem`, `attr_qalqalah`, `conf` (the default selection), each `[frames, classes]` float32. |
| The expected units of the ayah | `model.reference`: `fetch()` once (a separate download, verified), then `units(surah, ayah)`: the unit ids of a correct recitation, with the word bar (`model.tokens.wordBar`) between words. |
| The vocabulary | `model.tokens` (`name(id)`, `id(name)`, `blank`, `wordBar`). The calibration names the vocabulary it belongs to (`tokensSha256`). |
| Thresholds, fusion weights, taus | `model.calibration`: `calibration.json` exactly as shipped (`json`, `sha256`), with `threshold(context, class, preset)`, `fusionWeight(context, class)`, `status(context, class)` and `value(...)` for the rest. |

What your app builds:

1. **Site evidence.** For the expected sequence and for each single-unit alternative (a substitution, a deletion,
   an insertion at one position), the full-sequence CTC forward log-likelihood on `log_probs` and on
   `inter1_log_probs` (prepared as `contexts.<ctx>.fusion.inter1_prep` says: the word bar merged into blank). The
   LLR of a site is log P(best alternative) minus log P(expected), per head; fuse them with the class's weight
   (`llr = a * llr(log_probs) + (1 - a) * llr(inter1_log_probs)`), clip to the range the calibration gives, and
   flag the site when the fused LLR is above `sites.<class>.thresholds.<preset>`. A class whose `status` is `off`
   is not judged.
2. **Madd.** Viterbi spans on `timing_log_probs`: the ratio of a long vowel's held span to the median short-vowel
   span of the take, against `contexts.<ctx>.madd.cuts` per madd class.
3. **Qalqalah and gemination.** The mean of `attr_qalqalah[:, 1] + attr_qalqalah[:, 2]` over the 12 frames after
   the letter's end against `qalqalah.tau`; the mean of `gem[:, 1]` over the letter's span against
   `gemination.tau`.
4. **Words.** The largest clipped fused LLR over every single-unit edit inside the word, against the word level
   of `contexts.<ctx>.word`.
5. **Presets and overrides,** and how learners choose them: your settings, your UI.
6. **Your statistics.** Two calls let the app's own counts appear at sdk.quranlab.ai; nothing is sent if you never
   call them:

   ```kotlin
   session.reportRecited(surah = 1, ayahFrom = 2, ayahTo = 2)
   client.analytics.reportChecks("vowel_sub", checked = 31, flagged = 1)
   ```

## 4. Staying on 2.x

SDK 2.0.2 keeps working until Quran Lab retires SDK 2.x, which happens when 3.0 is released or shortly after;
there is no long overlap, so plan the move to 3.0 now.
