package ai.quranlab.tajweedformer.core

/**
 * native/core_jni.c: the Quran Lab Core (qlcore.h, API version 1) one call to one call. The bridge opens the Core
 * (libqlcore.so in the AAR) at run time; [CoreLibrary] loads both. Handles are the bridge's pointers.
 *
 * Status codes are the Core's (`QLC_OK` 0, negative on failure); [Core] turns them into the SDK's errors.
 */
internal object CoreNative {
    /** Opens the Core at [path] and fills the bridge's table: the Core's API version, or 1 (the library did not load)
     *  or 2 (a function of qlcore.h is missing). */
    @JvmStatic external fun bind(path: String): Int
    @JvmStatic external fun version(): String?
    @JvmStatic external fun notice(): String?

    /** [roots]: the folders of the plain files, the CDN forms, the device forms and the Core's own files. [out]:
     *  status, detail. 0 when the Core refused to start. */
    @JvmStatic external fun create(host: Any, spki: ByteArray, state: ByteArray?, roots: Array<String>, bootId: ByteArray, signals: Int,
                                   flags: Int, out: IntArray): Long
    @JvmStatic external fun destroy(core: Long)
    @JvmStatic external fun detail(core: Long): Int
    @JvmStatic external fun setRoots(core: Long, plain: String, cdn: String, device: String)
    /** [core JSON, install_sig], or null with [out] = status, detail. */
    @JvmStatic external fun leaseAsk(core: Long, challenge: String, build: String, out: IntArray): Array<String>?
    @JvmStatic external fun attestSig(core: Long, challenge: String?, out: IntArray): String?
    /** [times]: exp, refresh_after. */
    @JvmStatic external fun leaseAccept(core: Long, lease: ByteArray, manifest: ByteArray, build: String, times: LongArray): Int
    @JvmStatic external fun refusalAccept(core: Long, body: ByteArray, apiKeyId: String): Int
    @JvmStatic external fun leasePiece(core: Long, piece: ByteArray): Int
    /** 18.4.7.1: qlc_lease_attempt and qlc_lease_grace (out[0]: until). */
    @JvmStatic external fun leaseAttempt(core: Long, outcome: Int): Int
    @JvmStatic external fun leaseGrace(core: Long, out: LongArray): Int
    /** [out]: has_lease, valid, refresh_due, stopped, now, exp, refresh_after. */
    @JvmStatic external fun tick(core: Long, out: LongArray): Int
    /** [out]: day, seconds, previous day, its seconds. */
    @JvmStatic external fun usage(core: Long, out: DoubleArray): Int
    @JvmStatic external fun install(core: Long, manifest: ByteArray, path: String): Int
    @JvmStatic external fun modelOpen(core: Long, manifest: ByteArray, context: String?, threads: Int, decryptThreads: Int, sampleRate: Int,
                                      batch: Int, noPin: Int, out: IntArray): Long
    @JvmStatic external fun modelClose(model: Long)
    @JvmStatic external fun calibration(model: Long): ByteArray?
    @JvmStatic external fun chunkMs(model: Long): Int
    @JvmStatic external fun streamCreate(model: Long, out: IntArray): Long
    @JvmStatic external fun streamDestroy(stream: Long)
    @JvmStatic external fun streamSetHeads(stream: Long, heads: Int): Int
    @JvmStatic external fun streamReset(stream: Long)
    @JvmStatic external fun streamPush(stream: Long, pcm: FloatArray, offset: Int, length: Int): Int
    @JvmStatic external fun streamFinish(stream: Long): Int
    @JvmStatic external fun streamFrames(stream: Long): Int
    /** Every head over 40 ms frames [from, to), in [ai.quranlab.tajweedformer.HeadId] order; null where not computed. */
    @JvmStatic external fun streamHeads(stream: Long, from: Int, to: Int): Array<FloatArray?>?
    @JvmStatic external fun recitedIndex(core: Long, manifest: ByteArray): Int
    @JvmStatic external fun recitedBegin(core: Long, take: Long): Int
    @JvmStatic external fun recitedOpen(core: Long, out: LongArray): Int
    @JvmStatic external fun recitedFeed(core: Long, units: IntArray, n: Int): Int
    @JvmStatic external fun recitedFeedStream(core: Long, stream: Long): Int
    /** [out]: found, surah, first, last, score, day (days since 1970), context, ties, reported. */
    @JvmStatic external fun recitedFinish(core: Long, take: Long, flags: Int, out: IntArray): Int
    /** [out]: day, takes, ayahs, previous day, its takes, its ayahs. */
    @JvmStatic external fun recitedTotals(core: Long, out: LongArray): Int
    /** The journal's finished takes: the count; with [i] below it, [id] and [out] (as [recitedFinish]) of take [i]. */
    @JvmStatic external fun recitedDone(core: Long, i: Int, id: LongArray, out: IntArray): Int
    @JvmStatic external fun recitedReported(core: Long, take: Long): Int

    /** One ayah's expected units and its words' first units, or null with [status] = the Core's status (R3). */
    @JvmStatic external fun referenceExpected(model: Long, surah: Int, ayah: Int, status: IntArray): Array<IntArray>?
    @JvmStatic external fun referenceAyahCount(model: Long, surah: Int): Int
    /** The open model's vocabulary, its build's tokens.txt (R4). */
    @JvmStatic external fun tokenCount(model: Long): Int
    @JvmStatic external fun tokenName(model: Long, id: Int): String?

    /** The learner days in the recited journal (R10). */
    @JvmStatic external fun daysMark(core: Long, utcDay: Long, localDay: Long): Int
    @JvmStatic external fun daysJudged(core: Long, utcDay: Long, judged: Int, correct: Int): Int
    @JvmStatic external fun daysSeal(core: Long, utcDay: Long): Int
    @JvmStatic external fun daysTrendSent(core: Long, week: Long): Int
    /** qlc_days flattened (core_jni.c's order), or null. */
    @JvmStatic external fun daysRead(core: Long): LongArray?

    /** The accelerator from memory (R2): whether the lease allows it (1, 0, or a status). */
    @JvmStatic external fun accelAllowed(core: Long): Int
    /** The backend's name, or null with [status] = status, detail (QLC_D_ACCEL_*, QLC_D_LEASE_NO_ACCEL). Seconds long. */
    @JvmStatic external fun accelPrepare(core: Long, model: Long, kind: String, dispatchDir: String?, status: IntArray): String?
    @JvmStatic external fun accelRelease(model: Long): Int
    @JvmStatic external fun streamCreateOn(model: Long, backend: Int, out: IntArray): Long
    @JvmStatic external fun streamBackend(stream: Long): Int
}

/**
 * Loads the bridge and the Core. On Android both are in the APK: `libtajweedformer_jni.so` by its name and the Core
 * as `libqlcore.so`. On the desktop JVM (the tests) the system properties `tajweedformer.library` and
 * `tajweedformer.core.library` name the two files.
 */
internal object CoreLibrary {
    /** The qlcore.h API this SDK is written against. */
    const val API_VERSION = 1

    /** Why the Core is not available (it is not built for this CPU, or not in the app); null when it loaded. */
    @Volatile var loadError: String? = null
        private set

    val available: Boolean by lazy {
        try {
            val jni = System.getProperty("tajweedformer.library")
            if (jni != null) System.load(jni) else System.loadLibrary("tajweedformer_jni")
            val core = System.getProperty("tajweedformer.core.library") ?: "libqlcore.so"
            when (val v = CoreNative.bind(core)) {
                API_VERSION -> true
                1 -> { loadError = "the Core ($core) did not load"; false }
                2 -> { loadError = "the Core ($core) is not the interface this SDK calls"; false }
                else -> { loadError = "the Core's API version is $v, this SDK calls $API_VERSION"; false }
            }
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "the library did not load"
            false
        }
    }

    /** "<release> <target>" of the Core in the app, or null. */
    val version: String? get() = if (available) CoreNative.version() else null

    /** The notice the Core carries (core/notice.h), or null. */
    val notice: String? get() = if (available) CoreNative.notice() else null
}
