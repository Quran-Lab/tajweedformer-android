# Public names

Every public type and function of the Android SDK, read from the sources by `scripts/make_api_doc.py` (the
signatures are in [docs/api.md](docs/api.md)). Package `ai.quranlab.tajweedformer` unless the row names a
sub-package; a nested type is written `Outer.Inner`. Anything not listed is internal: the library is in Kotlin's
explicit API mode, and the Quran Lab Core inside it has no public Kotlin name at all.

## Android and the desktop JVM

| Type | Kind | Functions | Properties |
|---|---|---|---|
| `RecitedOutcome` | class |  | `correct`, `mistakes`, `rules` |
| `RecitedOutcome.Companion` | companion object | `correct`, `incorrect` |  |
| `TajweedAnalytics` | class | `reportChecks` |  |
| `TajweedBackend` | class |  | `kind`, `name`, `stage`, `reason` |
| `TajweedBackend.Kind` | enum class |  |  |
| `TajweedBackend.Stage` | enum class |  |  |
| `TajweedCalibration` | class | `threshold`, `status`, `fusionWeight`, `value` | `json`, `sha256`, `format`, `modelId`, `tokensSha256`, `defaultContext`, `defaultPreset`, `classes`, `presets`, `contexts` |
| `TajweedChunk` | class |  | `index`, `startMs`, `endMs`, `firstFrame`, `backend`, `speech`, `heads`, `decoded` |
| `TajweedClient` | class | `model`, `prefetch`, `removeModels`, `diagnose`, `background`, `foreground` | `sharing`, `analytics`, `storage` |
| `TajweedClient.Companion` | companion object |  | `current` |
| `TajweedClientOptions` | class |  | `appId`, `appVersion`, `storage`, `appBuild` |
| `TajweedClip` | class |  | `name`, `kind`, `durationMs` |
| `TajweedDecoded` | class |  | `tokens`, `frames`, `settled`, `isFinal`, `text`, `words` |
| `TajweedDiagnosis` | class | `toMap`, `toJson` | `sdkVersion`, `sdkLatest`, `keyAccepted`, `keyReason`, `config`, `modelVersion`, `modelBuild`, `modelVerified`, `context`, `backend`, `microphone`, `storage`, `lastError`, `droppedChunks`, `connection`, `downloadProtocol`, `protectedModel`, `endpoints` |
| `TajweedError` | class |  | `code`, `fix`, `docs`, `errorCause` |
| `TajweedErrorCause` | data class |  | `stage`, `reason`, `httpStatus`, `errorName`, `errorCode`, `needs` |
| `TajweedEvent` | sealed class |  |  |
| `TajweedEvent.BackendChanged` | class |  | `backend`, `fromChunk` |
| `TajweedEvent.Ended` | class |  | `reason`, `error` |
| `TajweedEvent.Ended.Reason` | enum class |  |  |
| `TajweedEvent.Interrupted` | class |  |  |
| `TajweedEvent.Interrupted.Cause` | enum class |  |  |
| `TajweedEvent.Resumed` | class |  |  |
| `TajweedEvent.SpeechEnded` | class |  | `atMs`, `silenceMs` |
| `TajweedEvent.SpeechStarted` | class |  | `atMs` |
| `TajweedFrame` | class |  | `index`, `top`, `topLogProb`, `runnerUp`, `margin`, `topK`, `topKLogProbs`, `startMs` |
| `TajweedHead` | class | `get` | `name`, `kind`, `frameMs`, `frames`, `classes`, `values`, `classNames` |
| `TajweedHead.Kind` | enum class |  |  |
| `TajweedHeads` | class | `get`, `greedy`, `decoded` | `names` |
| `TajweedModel` | class | `chunkMs`, `listen`, `session` | `id`, `version`, `build`, `contexts`, `defaultContext`, `tokens`, `calibration`, `reference`, `sampleClips`, `backend` |
| `TajweedReference` | class | `fetch`, `units` | `isDownloaded` |
| `TajweedSession` | class | `push`, `finish`, `cancel`, `reportRecited` | `chunks`, `events`, `backend` |
| `TajweedSessionOptions` | class |  | `context`, `sampleRate`, `heads`, `backend`, `topK`, `endOnSilenceMs`, `pace` |
| `TajweedSessionOptions.Backend` | enum class |  |  |
| `TajweedSessionOptions.Pace` | enum class |  |  |
| `TajweedSharing` | class | `setOn`, `ask`, `feed`, `summary`, `delete`, `setWording` | `isAvailable`, `isOn`, `items`, `recoveryCode` |
| `TajweedToken` | class |  | `id`, `name`, `firstMs`, `peakMs`, `lastMs`, `confidence` |
| `TajweedTokens` | class | `name`, `id` | `count`, `blank`, `wordBar`, `names` |
| `TajweedWord` | class |  | `index`, `tokens`, `text`, `firstMs`, `lastMs`, `confidence` |

## Android only

| Type | Kind | Functions | Properties |
|---|---|---|---|
| `Microphone` | object | `samples` | `sampleRate` |
| `SharingSettingsView` | class | `refresh` |  |
| `SharingTheme` | class |  | `background`, `text`, `secondaryText`, `accent`, `cornerRadiusDp`, `typeface`, `titleSizeSp`, `bodySizeSp`, `titleTypeface`, `dark` |
| `SharingTheme.Companion` | companion object |  | `current` |
| `TajweedClientAndroid.kt` | top-level functions | `TajweedClient` |  |

## Desktop JVM only

| Type | Kind | Functions | Properties |
|---|---|---|---|
| `TajweedClientJvm.kt` | top-level functions | `TajweedClient` |  |

## Jetpack Compose (`ai.quranlab:tajweedformer-compose`)

| Type | Kind | Functions | Properties |
|---|---|---|---|
| `SharingCompose.kt` (compose) | top-level functions | `SharingSheetHost`, `SharingSettings` |  |
| `SharingStyle` (compose) | class |  | `container`, `content`, `accent`, `shape`, `secondaryContent`, `cornerRadius`, `titleFont`, `bodyFont`, `dark` |
