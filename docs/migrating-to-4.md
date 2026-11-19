# Migrating from 3.x to 4.0

4.0.0 keeps every 3.x call but two options (`analytics` and `audience`, below). What changes is underneath: the model
is protected, runs inside the Quran Lab Core, and opens under a lease for this install. Most apps change the version in
their build file, delete the analytics and audience arguments if they set them, and add their signing certificates at
sdk.quranlab.ai.

**When to move.** SDK 3.x stops receiving the V4 model on 15 January 2027, 00:00 UTC. New model versions, from V4.1
on, are published only encrypted, for SDK 4.0: an app on 3.x never gets them.

## 1. What an app may notice

| 3.x | 4.0 |
| --- | --- |
| `TajweedClientOptions(analytics = Analytics.OFF)` turned the anonymous analytics off. | Removed, with `TajweedClientOptions.Analytics`, so code that sets it no longer compiles: delete the argument. Anonymous analytics are always on, because they are daily counts and durations only, never audio, text, names or device identifiers. Only Quran Lab can switch them off, from its signed config. |
| `TajweedClientOptions(audience = Audience.CHILDREN)` chose another sharing sheet. | Removed, with `TajweedClientOptions.Audience`, so code that sets it no longer compiles: delete the argument. Every app shows the same sharing sheet. Complying with children's privacy law is the app's and the app store's, through the app's privacy policy and its store declarations. |
| The model folder held plain files (`.tflite`, `.bin`) an app could read. | The files are encrypted for this install. Only `calibration.json` and the sample clips are plain. Nothing in the SDK hands out a model file or its path. The first start after the upgrade downloads the model once more, so it needs a connection once. |
| `client.model()` worked offline once downloaded, forever. | It works offline while the lease is valid: 7 days for an attested install, 24 hours otherwise, renewed in the background whenever the phone is online. See section 2. A Model already open keeps running. |
| The first `client.model()` downloaded and opened. | It also makes the install's keys in the Android Keystore and attests them, which takes longer on the first call only (how long on a given phone: `InstallKeyDeviceTest` logs it). |
| The NPU took over after its test on supported phones. | At 4.0's launch every phone runs the Core's CPU engine: Quran Lab serves no NPU program until one is faster than the CPU engine, so an app needs no dispatch library for now. NPU support returns when a faster build ships; the README's "The engines, in the Core" says how it is used then. |
| `reference.fetch()` downloaded the reference units. | They come with the model (about 1 MB, encrypted) and the Core reads them; `fetch()` is a no-op. |
| A server failure could be `needs_connection`, and a file that did not match after its download `download_failed`. | `needs_connection` only when Quran Lab gave no answer at all; a mismatched file is `checksum_mismatch`. See section 3. |

Do not post-process `libqlcore.so` beyond what Android's build does by default: the Core checks its own code, and a
changed Core gets no lease. Keep `minSdk` at 26 or above (the SDK's own). The x86_64 build of the Core is for the
emulator and development keys only: with a production key an emulator gets `server_rejected`.

## 2. The protected model and the lease

- **Encrypted end to end.** The model downloads encrypted, the Core installs it encrypted again for this install, and
  it is decrypted only in the Core's memory when it opens. No plaintext model file is ever written.
- **A lease per install.** The model opens under a lease Quran Lab signs for this install: 7 days when the phone's
  hardware attests the install for your app (or Play Integrity does, on a phone without usable hardware attestation),
  24 hours otherwise. The SDK renews it in the background when it is due, and a valid lease never waits for a renewal.
  A phone with neither, or a Play outage, gets 24-hour leases: never a lock-out. In an emergency Quran Lab may give
  longer leases; the SDK holds every lease to its own end.
- **Add your signing certificates.** On your Android key's page at sdk.quranlab.ai, under "Signing certificates
  (SHA-256)", add the app signing key certificate and the upload key certificate (Play Console: Test and release, App
  integrity, App signing). Until you do, your installs get 24-hour leases. Once Google Play
  recognizes your app, its app signing certificate counts as declared, and builds signed with your upload key
  (internal testing, sideloaded builds) are then refused unless you add the upload key certificate too.
- **Testing on cloud device labs.** Some labs re-sign your APK, so its signing digest differs from yours. Add the
  lab's signing digest to a development key, never to the production one.
- **Phones with an unlocked bootloader** are refused a lease, by design. The error says which check failed.
- **When Quran Lab cannot be reached.** If the lease has ended and Quran Lab cannot be reached (no answer, a server
  error, or "too many requests"), the model keeps opening under that lease for up to 24 hours more: at most 8 days
  after a 7-day lease was given, 48 hours after a 24-hour one. A refusal from Quran Lab ends this at once. When Quran
  Lab answers that its lease service is briefly unavailable (503 with a `Retry-After`), the SDK asks again after that
  time; such an answer is never a refusal. A model open
  past the end of the lease first asks Quran Lab for a new one and waits at most 10 seconds, and not at all when the
  phone is offline. Each such open is reported to Quran Lab; your app gets no error. After the 24 hours the model opens
  only with a new lease: until then `client.model()` is `needs_connection` when Quran Lab gave no answer, and
  `download_failed` when it answered with a server error.
- **A Model already open keeps running** until the app closes it, past the end of its lease and of those 24 hours too.
- **The clock is the Core's.** A changed device clock never lengthens a lease, and never locks a learner out for long.
- **Refusals.** When Quran Lab stops a key or an app, its answer on the lease route is signed. Only when the Core
  verifies it are the lease and the model's files removed, as in 3.x. Any other refusal there removes nothing and is
  `server_rejected`.
- **The honest limit.** A model that runs on a device can be taken by someone who controls that device and has the
  skill and the time. The goal of 4.0 is that a key, an AI agent, a copied folder or a script gets nothing usable, and
  that taking the model needs an expert, weeks, and a new effort for every release.

## 3. Error codes and error reports

The catalogue is the same 26 codes, so a `when` over them compiles as before. What changes is which code a failure
gets: an answer from a server is never `needs_connection`.

| What happened | Code |
| --- | --- |
| A request the call needs got no answer at all (offline, DNS, a refused connection) and nothing of the model is on the phone yet | `needs_connection` |
| The lease and its 24 hours above have ended, and Quran Lab gave no answer | `needs_connection` |
| Quran Lab answered with a failure: a server error, a 429, a redirect, a config that does not verify with none stored, a read that timed out | `download_failed`; the message names what answered ("the server answered 503"). Call again later |
| The connection went while part of the model was already downloaded | `download_failed`; the download resumes at the next call |
| A downloaded file does not match the signed manifest | `checksum_mismatch` |
| The phone's storage is full, or the SDK's folder cannot be written | `storage_full` or `storage_failed`, at once |
| Quran Lab refused the key or the app | `invalid_key`, or `server_rejected` with the reason, as in 3.x; `sdk_too_old` when the key needs a newer SDK |

**Error reports** are new in 4.0. Every `TajweedError` your app gets, except the codes that only say the API was used
wrongly (`invalid_argument`, `client_exists`, `session_open`, `session_closed`, `cancelled`), and every failure the SDK
goes on from without telling your app (for example a renewal of the lease that failed), is sent to Quran Lab as one
small report: where it failed, why, the HTTP status, the exception's class, how often that day, and the phone's model
code, memory tier, free-space bucket and ABI. Never a message, a path, a URL or an identifier. One report per kind of
failure per day, with its count; at most 16 a day and 32 waiting, kept 7 days. Your app's page at sdk.quranlab.ai
lists them under Problems, with what to do. No option turns them off; Quran Lab can, from its signed config. For
Google Play's data-safety form they are diagnostics, like the issue reports (the README's "Data safety" table).

## 4. Recited ayahs and the app's judgement

The SDK now places each take in the Quran by itself and counts the recited ayahs. `reportRecited` stays and is the
fallback: the SDK uses it only for a take it could not place, so a take is never counted twice. Keep calling it if you
already do.

If your app judges the recitation, say so with the report. The SDK counts it as your app's judgement and never
judges anything itself:

```kotlin
session.reportRecited(67, 1, 5, outcome = RecitedOutcome.correct())
session.reportRecited(67, 1, 5,
    outcome = RecitedOutcome.correct(),
    perAyah = mapOf(3 to RecitedOutcome.incorrect(mistakes = 2, rules = listOf("madd"))))
```

`perAyah` wins over `outcome` for the ayahs it names; an ayah outside the range is `invalid_argument`. From Java,
the three-argument form is still there (`@JvmOverloads`).

## 5. Flutter and other apps that make the Client late

In 3.1.5 a Client made after the first activity had resumed (Flutter makes it then) never learnt which activity was
in front: the sharing sheet could not show, and `listen()` was `microphone_denied` without asking. 4.0 seeds the
activity in front when the Client is made, so nothing is needed in the app. A sharing ask is now counted only once
its sheet is on screen.

## 6. Sharing

- **The learner's choice.** Every learner is asked, under the same ask rules in every app, and the choice is theirs:
  they can say "Not now", stop, or delete what they shared at any time. No app can turn the ask off or be exempted
  from it. The signed config's `flags.contributions` stays Quran Lab's incident switch for every app at once, not a
  per-app decision.
- **Asked later and less often.** Never in the install's first 24 hours or before its third finished take, after a
  take once the app session holds a minute of recitation, and a re-ask is a small card 14 days and 7 takes after the
  first ask (30 days and 10 takes after the second). `client.sharing.ask()` keeps the app's moment but follows the same
  rules (the minute aside): it shows nothing at launch, during a take, twice in a day, or before the next ask is due,
  and returns false then. An app that called `ask()` at launch to ask on the first day no longer can.
- **Takes in a row.** A shared recording now stays open for up to 60 seconds after a take (3 seconds unless Quran
  Lab's config sets it), so a learner who pauses between turns gives one recording. Nothing to change.
- **What a shared recording carries.** Its description now names the phone's model (for example `samsung SM-S921B`).
  When Quran Lab's config turns it on, a take captured at a rate other than 48 kHz is shared as lossless FLAC at its
  own rate.

## 7. Settings

`TajweedClientOptions(unmeteredOnly = true)` makes the SDK's own background downloads (the recited-ayah index, an
update of a build) wait for Wi-Fi or Ethernet. `client.model()` and `prefetch()` keep their own argument.

## 8. What stays the same

Every other call, the Heads and the decoded layer, events, one Client per process, the sharing sheet and its theming,
the settings rows, the learner's earlier answer with the consent version they agreed to, and the 26 error codes. Quran
Lab encourages, but does not require, one line where your app shows feedback built on the model, for example:
"Automatic tajweed feedback can be wrong. It does not replace a qualified teacher."
