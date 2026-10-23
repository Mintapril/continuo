package dev.dsh.pocket.cowork

import org.json.JSONObject

/**
 * Text a finished Cowork turn left unseen on the AI screen: the app was in front of it, so it posted no notification
 * for what arrived meanwhile. The broker keeps the last few in its state (`screenNews`, numbered within one broker
 * instance, like `ownerNotices`); the controller hands each one once to the notification triggers and remembers the
 * last instance and number it handed over. News older than [maxAgeMs] (Continuo was not running) is skipped.
 */
object ScreenNewsFeed {
    data class News(val seq: Long, val packageName: String, val app: String, val lines: List<String>, val at: Long)

    /** The entries not handed over yet: those after [seenSeq] of [seenInstance], or all of another broker instance. */
    fun fresh(state: JSONObject, seenInstance: String, seenSeq: Long, now: Long, maxAgeMs: Long): List<News> {
        val instance = state.optString("instanceId")
        val rows = state.optJSONArray("screenNews") ?: return emptyList()
        if (instance.isBlank()) return emptyList()
        val after = if (instance == seenInstance) seenSeq else 0L
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val seq = row.optLong("seq", -1)
            val pkg = row.optString("package").trim()
            val raw = row.optJSONArray("lines")
            val lines = if (raw == null) emptyList() else (0 until raw.length()).map { raw.optString(it).trim() }.filter { it.isNotEmpty() }
            if (seq <= after || pkg.isEmpty() || lines.isEmpty()) null
            else News(seq, pkg, row.optString("app").trim().ifEmpty { pkg }, lines, row.optLong("at", 0))
        }.sortedBy { it.seq }.filter { it.at >= now - maxAgeMs }
    }

    /** The highest number in the state: what the controller remembers, so a skipped (too old) entry is not retried. */
    fun lastSeq(state: JSONObject): Long {
        val rows = state.optJSONArray("screenNews") ?: return 0
        return (0 until rows.length()).maxOfOrNull { rows.optJSONObject(it)?.optLong("seq", 0) ?: 0 } ?: 0
    }
}
