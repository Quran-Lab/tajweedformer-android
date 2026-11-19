package ai.quranlab.tajweedformer.sharing

/**
 * When SDK 4.0 asks a learner to share: a direct port of the
 * reference `tools/share_ask.py` (`resolve_config`, `local_day`, `normalize`, `decide`), pure and integer only, replayed
 * against `vectors/share_ask.json` by ShareAskVectorsTest. [ConsentState] keeps the stored part and this app session's
 * part, and [Sharing] sends the events at their hooks and acts on the action.
 *
 * The rule in plain words (20.1): the first ask is the full sheet, never within 24 hours of the install or before the
 * 3rd completed session, only at the end of a good session (a take ended normally, and 60 s of speech in this app
 * session); after "Not now" the next ask needs both 14 days and 7 completed sessions since the last ask, the one after
 * it 30 days and 10; one ask per app session and per calendar day; a re-ask is a non-blocking card whose action opens
 * the full sheet; three asks ever; a yes and sharing turned off end asking. Quran Lab's signed `share_ask` can only
 * make it rarer.
 */
internal object ShareAsk {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val MINUTE_MS = 60_000L
    /** Quran Lab's permanent rule (11-10): three asks per install, ever. No config raises it. */
    const val HARD_MAX_ASKS = 3
    /** 20.6: an ask decided at a take's end is presented after this long with no new take. */
    const val SETTLE_MS = 3_000L
    /** 20.2: the app back in the foreground after this long in the background is a new app session (a launch). */
    const val NEW_APP_SESSION_MS = 30 * 60_000L

    val CONSENTS = listOf("unknown", "granted", "declined", "final")

    /** 20.3: the signed config's `share_ask` as the SDK applies it. */
    data class Config(
        val firstMinSessions: Int = 3,
        val firstMinHours: Int = 24,
        val reaskDays: List<Int> = listOf(14, 30),
        val reaskSessions: List<Int> = listOf(7, 10),
        val goodSessionMinS: Int = 60,
        val maxAsks: Int = 3,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf("first_min_sessions" to firstMinSessions, "first_min_hours" to firstMinHours,
            "reask_days" to reaskDays, "reask_sessions" to reaskSessions, "good_session_min_s" to goodSessionMinS, "max_asks" to maxAsks)
    }

    /** The ask in hand: its kind (`sheet`, `card`), its number among the three, and whether it went on screen. */
    data class Ask(val kind: String, val number: Int, val shown: Boolean)

    /** This app session's part (20.4), kept in memory only. */
    data class App(val takes: Int = 0, val recitedMs: Long = 0, val asked: Boolean = false, val takeOpen: Boolean = false, val ask: Ask? = null)

    /** The stored state as decide reads it (20.4), with this app session's part. */
    data class State(
        val consent: String = "unknown",
        val asks: Int = 0,
        val sessions: Int = 0,
        val lastAskSession: Int = 0,
        val installedAt: Long? = null,
        val lastAskAt: Long? = null,
        val lastAskDay: Long? = null,
        val app: App = App(),
    )

    /** The wall clock in ms and the device's offset from UTC in minutes at that moment. */
    data class Now(val ms: Long, val tzMin: Int = 0)

    /** An event (20.5): `launch`, `take_start`, `take_end`, `ask_now`, `shown`, `answer`, `withdrawn`, `setting`. */
    data class Event(
        val type: String,
        /** take_end: `normal`, `interrupted`, `error`, `cancelled`. */
        val end: String? = null,
        val speechMs: Long? = null,
        val offered: Boolean? = null,
        /** answer: `yes`, `not_now`, `open`. */
        val answer: String? = null,
        /** setting: the switch. */
        val on: Boolean? = null,
    )

    /** What decide returns: the action (`none`, `sheet`, `card`), why an ask was held back or an event ignored, and the new state. */
    data class Decision(val action: String, val why: String?, val state: State)

    // ---------------------------------------------------------------- 20.3

    private val BOUNDS_SCALAR = mapOf("first_min_sessions" to (3 to 30), "first_min_hours" to (24 to 720),
        "good_session_min_s" to (60 to 600), "max_asks" to (1 to HARD_MAX_ASKS))
    private val BOUNDS_PAIR = mapOf("reask_days" to listOf(14 to 180, 30 to 365), "reask_sessions" to listOf(7 to 100, 10 to 100))

    /** A JSON number with an integral value (21 and 21.0), else null (a boolean, a string, 14.5, null, a list). */
    fun integral(v: Any?): Long? = when (v) {
        is Boolean -> null
        is Int -> v.toLong()
        is Long -> v
        is Double -> if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 9.0e18) v.toLong() else null
        is Float -> if (v.isFinite() && v == Math.floor(v.toDouble()).toFloat()) v.toLong() else null
        else -> null
    }

    private fun clamp(v: Long, b: Pair<Int, Int>): Int = when {
        v < b.first -> b.first
        v > b.second -> b.second
        else -> v.toInt()
    }

    /**
     * 20.3: field by field, an integral number is clamped into its bounds; anything else (absent, null, a boolean, a
     * string, a fraction) is the default. reask_days and reask_sessions: a list of exactly two entries, each read as a
     * scalar against its own bounds; another length or type gives the default pair. Not an object: every default.
     */
    fun resolveConfig(raw: Any?): Config {
        val d = Config()
        val m = raw as? Map<*, *> ?: return d
        fun scalar(k: String, def: Int): Int = integral(m[k])?.let { clamp(it, BOUNDS_SCALAR.getValue(k)) } ?: def
        fun pair(k: String, def: List<Int>): List<Int> {
            val v = m[k] as? List<*> ?: return def
            if (v.size != 2) return def
            val b = BOUNDS_PAIR.getValue(k)
            return List(2) { i -> integral(v[i])?.let { clamp(it, b[i]) } ?: def[i] }
        }
        return Config(scalar("first_min_sessions", d.firstMinSessions), scalar("first_min_hours", d.firstMinHours),
            pair("reask_days", d.reaskDays), pair("reask_sessions", d.reaskSessions),
            scalar("good_session_min_s", d.goodSessionMinS), scalar("max_asks", d.maxAsks))
    }

    // ---------------------------------------------------------------- 20.4

    /** The device's calendar day: whole days since 1970-01-01 in the device's time zone at [now]. */
    fun localDay(now: Now): Long = Math.floorDiv(now.ms + now.tzMin * MINUTE_MS, DAY_MS)

    /**
     * The stored state as decide reads it: a 3.x state's missing fields (installed_at now; with an ask already shown,
     * last_ask_at now and last_ask_day today), 2.0.0's declined without a count (one ask), and the clock rule (a stored
     * time later than now becomes now, last_ask_day with it).
     */
    fun normalize(state: State, now: Now): State {
        val consent = if (state.consent in CONSENTS) state.consent else "unknown"
        var asks = maxOf(state.asks, 0)
        val sessions = maxOf(state.sessions, 0)
        var las = maxOf(state.lastAskSession, 0)
        if (consent == "declined" && asks < 1) asks = 1
        if (las > sessions) las = sessions
        val t = now.ms
        val ia = state.installedAt.let { if (it == null || it > t) t else it }
        var la = state.lastAskAt
        var ld = state.lastAskDay
        if (asks == 0) {
            la = null
            ld = null
        } else if (la == null || la > t) {
            la = t
            ld = localDay(now)
        } else if (ld == null) {
            ld = localDay(Now(la, now.tzMin))
        }
        return State(consent, asks, sessions, las, ia, la, ld, state.app)
    }

    // ---------------------------------------------------------------- 20.5

    /** The first rule (in this order) that keeps an ask from being due now, or null. [moment]: the end of a take (the
     *  60 s apply); false for the app's ask(). */
    private fun blocked(s: State, cfg: Config, now: Now, moment: Boolean): String? {
        val app = s.app
        if (s.consent == "granted") return "granted"
        if (s.consent == "final") return "final"
        if (s.asks >= cfg.maxAsks) return "cap"
        if (app.ask != null) return "ask_open"
        if (app.asked) return "app_session"
        if (s.lastAskDay != null && localDay(now) <= s.lastAskDay) return "same_day"
        if (moment && app.recitedMs < cfg.goodSessionMinS * 1000L) return "short"
        val t = now.ms
        if (s.asks == 0) {
            if (t - s.installedAt!! < cfg.firstMinHours * HOUR_MS) return "first_hours"
            if (s.sessions < cfg.firstMinSessions) return "first_sessions"
        } else {
            val k = s.asks - 1
            if (t - s.lastAskAt!! < cfg.reaskDays[k] * DAY_MS) return "reask_days"
            if (s.sessions - s.lastAskSession < cfg.reaskSessions[k]) return "reask_sessions"
        }
        return null
    }

    private fun pend(s: State): Pair<State, String> {
        val kind = if (s.asks == 0) "sheet" else "card"
        return s.copy(app = s.app.copy(ask = Ask(kind, s.asks + 1, false))) to kind
    }

    /** The ask in hand goes on screen now: counted, unless it is stale (then dropped). */
    private fun show(s: State, cfg: Config, now: Now): Pair<State, String?> {
        val a = s.app.ask!!
        if (s.consent !in listOf("unknown", "declined") || s.asks + 1 != a.number || s.asks >= cfg.maxAsks) {
            return s.copy(app = s.app.copy(ask = null)) to "stale"
        }
        return s.copy(asks = s.asks + 1, lastAskAt = now.ms, lastAskDay = localDay(now), lastAskSession = s.sessions,
            app = s.app.copy(asked = true, ask = a.copy(shown = true))) to null
    }

    /** "Not now" on the sheet or the card, the sheet closed without an answer, a card dismissed or gone (20.6). */
    private fun notNow(s: State): State {
        val c = if (s.consent in listOf("unknown", "declined")) (if (s.asks >= HARD_MAX_ASKS) "final" else "declined") else s.consent
        return s.copy(consent = c, app = s.app.copy(ask = null))
    }

    /** 20.5: the decision, a pure function of the state (with this app session's part), the event, the config and the clock. */
    fun decide(state: State, event: Event, config: Any?, now: Now): Decision {
        val cfg = config as? Config ?: resolveConfig(config)
        var s = normalize(state, now)
        var action = "none"
        var why: String? = null
        when (event.type) {
            "launch" -> s = s.copy(app = App())
            "take_start" -> {
                val a = s.app.ask
                s = s.copy(app = s.app.copy(takeOpen = true))
                if (a != null && !a.shown) s = s.copy(app = s.app.copy(ask = null))
                else if (a != null && a.kind == "card") s = notNow(s)
            }
            "take_end" -> {
                val speech = maxOf(event.speechMs ?: 0L, 0L)
                val completed = event.end == "normal" && speech > 0
                s = s.copy(app = s.app.copy(takeOpen = false))
                if (completed) s = s.copy(sessions = s.sessions + 1, app = s.app.copy(takes = s.app.takes + 1, recitedMs = s.app.recitedMs + speech))
                why = when {
                    !completed -> "not_completed"
                    event.offered != true -> "not_offered"
                    else -> blocked(s, cfg, now, moment = true)
                }
                if (completed && event.offered == true && why == null) {
                    val (n, kind) = pend(s)
                    s = n
                    action = kind
                }
            }
            "ask_now" -> {
                why = when {
                    event.offered != true -> "not_offered"
                    s.app.takeOpen -> "take_open"
                    s.app.takes == 0 -> "no_take"
                    else -> blocked(s, cfg, now, moment = false)
                }
                if (why == null) {
                    val (n, kind) = pend(s)
                    s = n
                    action = kind
                }
            }
            "shown" -> {
                val a = s.app.ask
                when {
                    a == null -> why = "no_ask"
                    a.shown -> why = "already_shown"
                    else -> {
                        val (n, w) = show(s, cfg, now)
                        s = n
                        why = w
                        if (w == null) action = a.kind
                    }
                }
            }
            "answer" -> {
                val a = s.app.ask
                val ans = event.answer
                when {
                    a == null -> why = "no_ask"
                    s.consent !in listOf("unknown", "declined") -> {
                        s = s.copy(app = s.app.copy(ask = null))
                        why = "answered"
                    }
                    ans == "yes" && a.kind != "sheet" -> why = "card_has_no_yes"
                    ans == "open" && a.kind != "card" -> why = "not_a_card"
                    ans !in listOf("yes", "not_now", "open") -> why = "unknown_answer"
                    else -> {
                        // an answer means the ask was on screen (an SDK that missed its shown)
                        if (!a.shown) {
                            val (n, w) = show(s, cfg, now)
                            s = n
                            why = w
                        }
                        if (why == null) when (ans) {
                            "yes" -> s = s.copy(consent = "granted", app = s.app.copy(ask = null))
                            "not_now" -> s = notNow(s)
                            else -> {
                                s = s.copy(app = s.app.copy(ask = Ask("sheet", a.number, true)))
                                action = "sheet"
                            }
                        }
                    }
                }
            }
            "withdrawn" -> {
                val a = s.app.ask
                when {
                    a == null -> why = "no_ask"
                    !a.shown -> s = s.copy(app = s.app.copy(ask = null))
                    a.kind == "card" -> s = notNow(s)
                    // a sheet taken back after it was shown: the ask is used, no answer is recorded
                    else -> s = s.copy(app = s.app.copy(ask = null))
                }
            }
            "setting" -> s = s.copy(consent = if (event.on == true) "granted" else "final", app = s.app.copy(ask = null))
            else -> why = "unknown_event"
        }
        return Decision(action, why, s)
    }
}
