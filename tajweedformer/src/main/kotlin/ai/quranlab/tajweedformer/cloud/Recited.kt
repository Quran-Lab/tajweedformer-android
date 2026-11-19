package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.RecitedReport
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.RecitedFeed
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The recited-ayah index, loaded into the Core where it is used and not at the model's open (Stage B, 11-18, the
 * Alcatel 1: loading it at every open cost 240 ms of open, 270 ms of first result and 1.4 MB, while only
 * `qlc_recited_finish` reads it, at a counted take's end and at the recovery of a take a kill cut short). [prepare]
 * at each open names the build whose index may be loaded (null: none); [ensure] loads it at most once per [prepare],
 * a failed load is tried again at the next [ensure].
 */
internal class RecitedIndexLoader<B : Any>(private val load: (B) -> Unit) {
    private val lock = Any()
    private var build: B? = null
    private var loaded = false

    fun prepare(b: B?) = synchronized(lock) {
        build = b
        loaded = false
    }

    /** Whether the index of the prepared build is loaded now (loading it if it is not). */
    fun ensure(): Boolean = synchronized(lock) {
        if (loaded) return true
        val b = build ?: return false
        try {
            load(b)
            loaded = true
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Which ayahs each finished take recited, counted once into the day's anonymous totals (SPEC 17.1, 17.5, 17.6), with
 * the app's `reportRecited` as the fallback. The detector and its journal of the take's units are the Core's
 * (qlc_recited_*); this class runs a take's life around them and keeps the SDK's own small journal
 * (`<storage>/core/recited.json`) so a kill at any point neither loses a take nor counts it twice:
 *
 * 1. A take starts: its id (a counter, never sent) and its UTC day go into the journal, then the Core begins it.
 * 2. While it runs, after each engine call, the Core is fed the take's greedy units (off the audio thread); the Core
 *    writes its journal at begin, at each tick that has new units (the SDK ticks every 10 s while a take runs), and
 *    at finish.
 * 3. The take ends with its Heads: the Core finishes it (its result and day's totals committed in one write), and the
 *    take waits in the journal with the result for the app's report, until the next take starts, the app leaves or
 *    30 s pass; then [Telemetry.recordTake] counts it (measured, else the app's report, else nothing) and the journal
 *    lets it go. The day's aggregate records the take's id in the same write as its count, so a take is never counted
 *    twice.
 * 4. At the next start, a take the journal still holds is finalised: the Core finishes a take that was open (the units
 *    journaled before the kill; `recovered`), or gives back the stored result of one it finished.
 *
 * Nothing is counted with analytics off (the journal is deleted), with `flags.recited` false (only the app's reports
 * count), or before the index is on the device (the app's reports count). No ayah number reaches the app or a log.
 */
internal class RecitedCount(
    private val dir: File,
    private val telemetry: Telemetry,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Analytics on: the signed config's `flags.analytics` (SDK 4.0: no app option). */
    private val analytics: () -> Boolean = { true },
    /** `flags.recited` (absent or true: the detector runs). */
    private val detector: () -> Boolean = { true },
    /** `flags.recited_twins` (absent or true: a copy in another surah is not a rival on a continuation). */
    private val twins: () -> Boolean = { true },
    /** The Core with the index loaded, or null. */
    private val core: () -> Core?,
    /** Loads the index into the Core where it is needed (a counted take's end, the recovery of a cut take), once per
     *  model open; false when it could not be loaded (the take then counts as not found). */
    private val ensureIndex: () -> Boolean = { true },
) : RecitedFeed {
    private val file = File(dir, "recited.json")
    private val lock = Any()
    private var next = 1L
    private var open: TakeRecord? = null
    private val pending = ArrayList<TakeRecord>()
    /** The take begun in the Core (its id), so the feed reaches only that take. */
    @Volatile private var begun: Long? = null
    private var session: Any? = null
    /** The open take is this process's (a take left by an earlier process is finalised by [recover]). */
    private var openedHere = false
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tajweedformer-recited").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var foldLater: ScheduledFuture<*>? = null
    @Volatile var indexReady = false

    init {
        load()
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        try {
            if (!file.isFile) return
            val o = Json.parse(file.readText()) as Map<String, Any?>
            next = (o["next"] as? Double)?.toLong() ?: 1L
            open = TakeRecord.of(o["open"])
            (o["pending"] as? List<Any?>)?.mapNotNullTo(pending) { TakeRecord.of(it) }
        } catch (_: Exception) {
            // a journal that cannot be read is dropped whole and counted (17.6)
            file.delete()
            telemetry.recitedDropped(1)
        }
    }

    private fun save() {
        try {
            dir.mkdirs()
            val tmp = File(dir, "recited.json.tmp")
            java.io.FileOutputStream(tmp).use { o ->
                o.write(Json.write(linkedMapOf("next" to next, "open" to open?.json(), "pending" to pending.map { it.json() })).toByteArray(Charsets.UTF_8))
                o.fd.sync()
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    private fun flags(): Int = if (twins()) 0 else Core.RECITED_NO_CROSS_SURAH

    private val today: Long get() = clock() / 86_400_000L

    /** A take started on [s] (a TajweedSession). The take before it is counted now. */
    fun takeStarted(s: Any) {
        flush()
        synchronized(lock) {
            if (!analytics()) {
                clearLocked()
                return
            }
            val id = next++
            open = TakeRecord(id, today)
            session = s
            openedHere = true
            begun = null
            save()
            val c = if (detector() && indexReady) core() else null
            if (c != null) {
                try {
                    if (!c.recitedBegin(id)) begun = id
                } catch (_: Exception) {
                }
            }
        }
    }

    /** RecitedFeed: after an engine call of the take (the engine's thread): the take's greedy units so far. */
    override fun fed(core: Core, stream: Long) {
        val id = begun ?: return
        if (open?.id != id) return
        try { core.recitedFeedStream(stream) } catch (_: Exception) {}
    }

    /** The app reported the take's range (and its judgement): held with the take until it is counted. */
    fun report(s: Any, r: RecitedReport) = synchronized(lock) {
        if (!analytics()) return
        val o = open
        if (o != null && session === s) {
            open = o.copy(report = r)
            save()
            return
        }
        // the take ended and waits to be counted: a report after the end counts only when the take had none (SPEC 6.3:
        // the first report after the end counts; one after a report made during the take does nothing)
        val i = pending.indexOfLast { true }
        if (i >= 0 && session === s && pending[i].report == null) {
            pending[i] = pending[i].copy(report = r)
            save()
        }
    }

    /** The take on [s] ended: [finished] with its Heads and audio (a finished take), else it counts nothing. */
    fun takeEnded(s: Any, finished: Boolean) {
        synchronized(lock) {
            val o = open ?: return
            if (session !== s) return
            open = null
            openedHere = false
            val id = begun
            begun = null
            if (!finished) {
                save()
                return
            }
            var t = o
            val c = if (id == o.id && ensureIndex()) core() else null
            if (c != null) {
                t = try {
                    o.withResult(c.recitedFinish(o.id, flags()))
                } catch (_: Exception) {
                    o.copy(found = false)
                }
            } else {
                t = o.copy(found = false)
            }
            pending.add(t)
            save()
            foldLater?.cancel(false)
            foldLater = try { timer.schedule({ flush() }, FOLD_AFTER_MS, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        }
    }

    /** Every finished take waiting is counted now (the next take started, the app left, the Client closes). */
    fun flush() {
        synchronized(lock) {
            if (pending.isEmpty()) return
            val done = ArrayList<TakeRecord>()
            for (t in pending) if (!analytics() || telemetry.recordTake(t) || clock() / 86_400_000L - t.day > 7) done.add(t)
            pending.removeAll(done.toSet())
            save()
            // the Core's journal marks each take counted (qlc_recited_reported): a restart never counts it again
            if (done.isNotEmpty()) markReported(done)
        }
    }

    /**
     * After a restart, once the index is loaded (the model is open): a take the journal holds open was cut by a kill
     * and is finalised from the units the Core journaled (`recovered`); a finished one waiting is counted. Without the
     * Core's journal for it, the app's report applies, else nothing.
     */
    fun recover() {
        synchronized(lock) {
            val o = open ?: return@synchronized
            if (openedHere) return@synchronized // the take of this process
            open = null
            if (clock() / 86_400_000L - o.day > 7 || o.day - clock() / 86_400_000L > 1) {
                save()
                telemetry.recitedDropped(1)
                return@synchronized
            }
            val c = if (detector() && indexReady && ensureIndex()) core() else null
            var t = o.copy(found = false, recovered = true)
            if (c != null) {
                try {
                    val openInCore = c.recitedOpen()
                    if (openInCore == o.id || c.recitedBegin(o.id)) {
                        t = o.withResult(c.recitedFinish(o.id, flags())).copy(recovered = true)
                    }
                } catch (_: Exception) {
                }
            }
            pending.add(t)
            save()
        }
        recoverDone()
        flush()
    }

    /**
     * The takes the Core finished whose result this journal lost (a kill between the Core's finish and this journal's
     * write): the Core remembers its last 64 finished takes (qlc_recited_done, R5); one this install made, not marked
     * reported and not waiting here, is counted from the Core's result once (`recovered`).
     */
    private fun recoverDone() {
        val c = (if (detector() && indexReady) core() else null) ?: return
        val done = try { c.recitedDone() } catch (_: Exception) { return }
        synchronized(lock) {
            val known = pending.map { it.id }.toSet() + listOfNotNull(open?.id)
            for ((id, r) in done) {
                if (r.reported || id in known || id >= next) continue
                val day = if (r.day > 0) r.day else today
                if (today - day > 7) continue
                pending.add(TakeRecord(id, day, null, found = r.found, recovered = true).withResult(r))
            }
            save()
        }
    }

    private fun markReported(takes: List<TakeRecord>) {
        val c = try { core() } catch (_: Exception) { null } ?: return
        for (t in takes) if (t.found != null) try { c.recitedReported(t.id) } catch (_: Exception) {}
    }

    /** Analytics off: nothing is journaled and the journal goes (17.6). */
    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        open = null
        pending.clear()
        begun = null
        file.delete()
    }

    fun close() {
        flush()
        timer.shutdown()
    }

    companion object {
        /** A finished take waits this long for the app's report before it is counted. */
        const val FOLD_AFTER_MS = 30_000L
    }
}
