# The public API

Every public declaration of the SDK, read from the sources (the library is in Kotlin's explicit API mode: what
is not listed here is internal). One package, `ai.quranlab.tajweedformer`; the Compose pieces are in
`ai.quranlab.tajweedformer.compose`. Written by `scripts/make_api_doc.py`; `PublicContractTest` fails when a public name is missing here.

## Android and the desktop JVM

```kotlin
public class TajweedClient
    public val sharing: TajweedSharing
    public val analytics: TajweedAnalytics
    public suspend fun model(version: String? = null): TajweedModel
    public suspend fun prefetch(unmeteredOnly: Boolean = false, onProgress: ((done: Long, total: Long) -> Unit)? = null): Unit
    public val storage: Map<String, Any>
    public suspend fun removeModels(): Unit
    public suspend fun diagnose(): TajweedDiagnosis
    public fun background()
    public fun foreground()
public class TajweedAnalytics
    public fun reportChecks(rule: String, checked: Int, flagged: Int)
```

```kotlin
public class TajweedClientOptions
    public val appId: String?
    public val appVersion: String?
    public val storage: File?
    public val analytics: Analytics
    public val audience: Audience
    public enum class Analytics
    public enum class Audience
public class TajweedSessionOptions
    public val context: String?
    public val sampleRate: Int
    public val heads: List<String>?
    public val backend: Backend
    public val topK: Int
    public val endOnSilenceMs: Int?
    public val pace: Pace
    public enum class Backend
    public enum class Pace
```

```kotlin
public class TajweedModel
    public val id: String
    public val version: String
    public val build: String
    public val contexts: List<String>
    public val defaultContext: String
    public fun chunkMs(context: String = defaultContext): Int
    public val tokens: TajweedTokens
    public val calibration: TajweedCalibration?
    public val reference: TajweedReference?
    public val sampleClips: List<TajweedClip>
    public val backend: TajweedBackend
    public suspend fun listen(options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession
    public suspend fun listen(from: TajweedClip, options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession
    public suspend fun listen(from: FloatArray, sampleRate: Int, options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession
    public suspend fun session(options: TajweedSessionOptions = TajweedSessionOptions()): TajweedSession
```

```kotlin
public class TajweedSession
    public val chunks: Flow<TajweedChunk>
    public val events: Flow<TajweedEvent>
    public suspend fun push(samples: FloatArray)
    public suspend fun finish(): TajweedHeads
    public fun cancel()
    public val backend: TajweedBackend
    public fun reportRecited(surah: Int, ayahFrom: Int, ayahTo: Int)
```

```kotlin
public class TajweedChunk
    public val index: Int,
    public val startMs: Long,
    public val endMs: Long,
    public val firstFrame: Int,
    public val backend: TajweedBackend,
    public val speech: Boolean,
    public val heads: TajweedHeads,
    public val decoded: TajweedDecoded,
public class TajweedDecoded
    public val tokens: List<TajweedToken>,
    public val frames: List<TajweedFrame>,
    public val settled: Int,
    public val isFinal: Boolean,
    public val text: String
    public val words: List<TajweedWord>
public class TajweedToken
    public val id: Int,
    public val name: String,
    public val firstMs: Long,
    public val peakMs: Long,
    public val lastMs: Long,
    public val confidence: Float,
public class TajweedWord
    public val index: Int,
    public val tokens: List<TajweedToken>,
    public val text: String
    public val firstMs: Long
    public val lastMs: Long
    public val confidence: Float
public class TajweedFrame
    public val index: Int,
    public val top: Int,
    public val topLogProb: Float,
    public val runnerUp: Int,
    public val margin: Float,
    public val topK: List<Int>,
    public val topKLogProbs: List<Float>,
    public val startMs: Long
```

```kotlin
public class TajweedHead
    public val name: String,
    public val kind: Kind,
    public val frameMs: Int,
    public val frames: Int,
    public val classes: Int,
    public val values: FloatArray,
    public enum class Kind
    public val classNames: List<String>
    public operator fun get(frame: Int, cls: Int): Float
public class TajweedHeads
    public val names: List<String>
    public operator fun get(name: String): TajweedHead?
    public fun greedy(name: String = "log_probs"): List<TajweedToken>
    public fun decoded(): TajweedDecoded
```

```kotlin
public sealed class TajweedEvent
    public class SpeechStarted
        public val atMs: Long,
    public class SpeechEnded
        public val atMs: Long,
        public val silenceMs: Long,
    public class Interrupted
        public enum class Cause
    public class Resumed
    public class BackendChanged
        public val backend: TajweedBackend,
        public val fromChunk: Int,
    public class Ended
        public val reason: Reason,
        public val error: TajweedError?
        public enum class Reason
```

```kotlin
public class TajweedBackend
    public val kind: Kind,
    public val name: String,
    public val stage: Stage,
    public val reason: String?
    public enum class Kind
    public enum class Stage
```

```kotlin
public class TajweedTokens
    public val count: Int
    public val blank: Int
    public val wordBar: Int
    public fun name(id: Int): String
    public fun id(name: String): Int?
    public val names: List<String>
public class TajweedClip
    public val name: String,
    public val kind: String,
    public val durationMs: Long,
public class TajweedReference
    public val isDownloaded: Boolean
    public suspend fun fetch()
    public fun units(surah: Int, ayah: Int): IntArray
```

```kotlin
public class TajweedCalibration
    public val json: String
    public val sha256: String
    public val format: String
    public val modelId: String?
    public val tokensSha256: String?
    public val defaultContext: String?
    public val defaultPreset: String?
    public val classes: List<String>
    public val presets: List<String>
    public val contexts: List<String>
    public fun threshold(context: String, siteClass: String, preset: String): Double?
    public fun status(context: String, siteClass: String): String?
    public fun fusionWeight(context: String, siteClass: String): Double?
    public fun value(vararg path: String): Any?
```

```kotlin
public class TajweedSharing
    public val isAvailable: Boolean
    public val isOn: Boolean
    public fun setOn(on: Boolean)
    public fun ask(): Boolean
    public fun feed(samples: FloatArray, sampleRate: Int)
    public val items: List<Map<String, Any>>
    public suspend fun summary(): Map<String, Any?>?
    public suspend fun delete(): Boolean
    public val recoveryCode: String?
    public fun setWording(overrides: Map<String, String>?)
```

```kotlin
public class TajweedDiagnosis
    public val sdkVersion: String,
    public val sdkLatest: String?,
    public val keyAccepted: Boolean?,
    public val keyReason: String?,
    public val config: String,
    public val modelVersion: String?,
    public val modelBuild: String?,
    public val modelVerified: Boolean,
    public val context: String?,
    public val backend: TajweedBackend?,
    public val microphone: String,
    public val storage: Map<String, Any>,
    public val lastError: String?,
    public val droppedChunks: Long,
    public val connection: String,
    public val downloadProtocol: String?
    public fun toMap(): Map<String, Any?>
    public fun toJson(): String
```

```kotlin
public open class TajweedError
    public val code: String,
    public val fix: String
    public val docs: String
```

## Android only

```kotlin
public fun TajweedClient(context: Context, key: String, options: TajweedClientOptions = TajweedClientOptions()): TajweedClient
```

```kotlin
public object Microphone
    public val sampleRate: Int
    public fun samples(): Flow<FloatArray>
```

```kotlin
public class SharingTheme
    public val background: Int?
    public val text: Int?
    public val secondaryText: Int?
    public val accent: Int?
    public val cornerRadiusDp: Float
    public val typeface: Typeface?
    public val titleSizeSp: Float
    public val bodySizeSp: Float
    public val titleTypeface: Typeface?
    public val dark: Boolean?
    public companion object
        public var current: SharingTheme
public class SharingSettingsView
    public fun refresh()
```

## Desktop JVM only

```kotlin
public fun TajweedClient(key: String, storage: File, options: TajweedClientOptions = TajweedClientOptions()): TajweedClient
```

## Jetpack Compose (`ai.quranlab:tajweedformer-compose`)

```kotlin
public class SharingStyle
    public val container: Color
    public val content: Color
    public val accent: Color
    public val shape: Shape?
    public val secondaryContent: Color
    public val cornerRadius: Dp?
    public val titleFont: FontFamily?
    public val bodyFont: FontFamily?
    public val dark: Boolean?
public fun SharingSheetHost(client: TajweedClient, style: SharingStyle = SharingStyle())
public fun SharingSettings(client: TajweedClient, style: SharingStyle = SharingStyle())
```
