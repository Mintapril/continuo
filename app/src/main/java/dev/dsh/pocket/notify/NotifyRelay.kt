package dev.dsh.pocket.notify

import org.json.JSONArray
import org.json.JSONObject

/** The user's choices for the notification triggers: edited in Continuo, applied and kept by the broker. */
data class NotifyConfig(
    val enabled: Boolean = false,
    val excluded: Set<String> = emptySet(),
    val quietSeconds: Int = NotifyDefaults.DEFAULT_QUIET_SECONDS,
) {
    fun json(): JSONObject = JSONObject().put("enabled", enabled).put("excluded", JSONArray(excluded.sorted())).put("quietSeconds", quietSeconds)

    companion object {
        const val MAX_EXCLUDED = 200
        private val PACKAGE = Regex("[A-Za-z0-9_.]{1,200}")

        /** Null when [o] is not a valid config: the broker refuses it instead of guessing. */
        fun parse(o: JSONObject?): NotifyConfig? {
            if (o == null || o.length() != 3 || !o.has("enabled") || !o.has("excluded") || !o.has("quietSeconds")) return null
            val enabled = o.opt("enabled") as? Boolean ?: return null
            val list = o.opt("excluded") as? JSONArray ?: return null
            if (list.length() > MAX_EXCLUDED) return null
            val excluded = (0 until list.length()).map { (list.opt(it) as? String)?.takeIf(PACKAGE::matches) ?: return null }.toSet()
            val quiet = o.opt("quietSeconds") as? Int ?: return null
            if (quiet != NotifyDefaults.quietSeconds(quiet)) return null
            return NotifyConfig(enabled, excluded, quiet)
        }
    }
}

/**
 * The notification triggers, run by the root broker. ColorOS freezes Continuo a few seconds after the screen goes off
 * and holds its listener callbacks until the screen is on again; the broker is never frozen. Every watched
 * notification and the text a turn left unseen on the AI screen join one batch ([NotifyThrottle]); once it is due it
 * goes, signed, to the Cowork plugin, where Jev decides whether it wakes the app's session. From the first notification
 * of a batch until it has been handed over the CPU is held up ([NotifyDefaults.awakeMs]), or a phone on battery would
 * sleep with the batch pending. JVM-tested: the clock, the timer, sending and the wake lock come from [Host].
 */
class NotifyRelay(private val host: Host) {
    interface Host {
        fun now(): Long
        /** Calls [flush] after [delayMs], replacing the previous call it has not made yet. */
        fun arm(delayMs: Long)
        /** Throws when the batch could not be handed over. */
        fun send(batch: NotifyThrottle.Batch): NotifyWire.Outcome
        /** Keeps the CPU up for [ms] from now; 0 lets it go. */
        fun awake(ms: Long)
    }
    data class LogEntry(val at: Long, val text: String)

    private val throttle = NotifyThrottle()
    private val log = ArrayDeque<LogEntry>()
    @get:Synchronized var config = NotifyConfig(); private set

    @Synchronized fun configure(next: NotifyConfig) {
        config = next
        throttle.quietMs = next.quietSeconds * 1000L
        if (!next.enabled) { throttle.forget(); host.awake(0) }
        rearm()
    }

    /** Whether a notification of [packageName] may be read at all. */
    @Synchronized fun watches(packageName: String): Boolean =
        config.enabled && packageName != NotifyDefaults.OWN_PACKAGE && packageName !in config.excluded

    @Synchronized fun posted(n: Posted, app: String) {
        if (!watches(n.packageName) || NotifyFilter.check(n) != Verdict.ACCEPT) return
        if (throttle.add(n, app) != Verdict.ACCEPT) return
        rearm()
    }

    /** Text a finished turn left unseen on the AI screen (the app was in front, so it posted no notification). */
    fun screen(packageName: String, app: String?, lines: List<String>, at: Long, key: String) {
        val text = NotifyDefaults.screenText(lines)
        if (text.isEmpty()) return
        posted(Posted(packageName, key, work = true, channel = "", category = null, ongoing = false, groupSummary = false,
            title = "", text = text, at = at, screen = true), NotifyDefaults.label(app, packageName))
    }

    /** The timer: sends the batch if it is due. One caller at a time (the timer thread), outside the lock while sending. */
    fun flush() {
        val batch = synchronized(this) { if (!config.enabled) null else throttle.take(host.now()).also { rearm() } } ?: return
        val result = runCatching { host.send(batch) }
        // A batch collected meanwhile holds the CPU for itself; otherwise stay up until the Cowork turn has started.
        synchronized(this) { if (throttle.fireAt() == null) host.awake(if (result.getOrNull()?.queued == true) NotifyDefaults.HANDOFF_AWAKE_MS else 0) }
        val apps = batch.items.map { it.app }.distinct().let { it.take(3).joinToString("、") + if (it.size > 3) " 等" else "" }
        val what = "$apps · ${batch.count} 条"
        record(result.fold({ outcome ->
            val p = outcome.meaningful?.let { "%.2f".format(it) }
            when {
                outcome.queued -> "$what：Jev 判定 ${outcome.kept}/${outcome.total} 条有意义（最高 $p），只把这几条交给 Cowork（副屏忙时排队）"
                outcome.judgeError != null -> "$what：Jev 未能判断（${NotifyWire.judgeError(outcome.judgeError)}），未唤起"
                else -> "$what：Jev 判定都没有实际意义（最高 ${p ?: "?"}），未唤起"
            }
        }, { "$what：未能送去判断（${it.message ?: "发送失败"}）" }))
    }

    /** Newest first, for the settings page. */
    @Synchronized fun log(): List<LogEntry> = log.toList()

    @Synchronized private fun record(text: String) {
        log.addFirst(LogEntry(host.now(), text))
        while (log.size > LOG_SIZE) log.removeLast()
    }

    private fun rearm() {
        val at = throttle.fireAt() ?: return
        val now = host.now()
        host.awake(NotifyDefaults.awakeMs(at, now))
        host.arm((at - now).coerceIn(0, NotifyDefaults.MAX_WAIT_MS))
    }

    companion object { const val LOG_SIZE = 40 }
}
