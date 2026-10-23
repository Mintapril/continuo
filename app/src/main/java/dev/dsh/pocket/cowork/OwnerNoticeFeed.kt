package dev.dsh.pocket.cowork

import org.json.JSONObject

/**
 * Notes the AI left for the owner (phone_notify_owner) when a run nobody watched needed them or could not go on. The
 * broker keeps the last few in its state (`ownerNotices`, numbered within one broker instance); the controller shows
 * each one once as a notification and remembers the last instance and number it showed.
 */
object OwnerNoticeFeed {
    data class Notice(val seq: Long, val text: String, val at: Long)

    /** The notices not shown yet: those after [seenSeq] of [seenInstance], or all of them from another broker instance. */
    fun fresh(state: JSONObject, seenInstance: String, seenSeq: Long): List<Notice> {
        val instance = state.optString("instanceId")
        val rows = state.optJSONArray("ownerNotices") ?: return emptyList()
        if (instance.isBlank()) return emptyList()
        val after = if (instance == seenInstance) seenSeq else 0L
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val seq = row.optLong("seq", -1)
            val text = row.optString("text").trim()
            if (seq <= after || text.isEmpty()) null else Notice(seq, text, row.optLong("at", 0))
        }.sortedBy { it.seq }
    }
}
