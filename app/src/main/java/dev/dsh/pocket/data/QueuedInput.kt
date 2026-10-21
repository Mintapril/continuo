package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending input in a session's `inbox` projection. DSH keeps what was queued when a turn is stopped, but starts no
 * turn for it until another message arrives, so the next reply would answer the previous message. The app wakes the
 * session by taking the oldest pending message out and steering its text back in: the new turn then claims it first
 * and the remaining queue after it, in order.
 */
object QueuedInput {
    /** The message DSH would take first (next-step input before the queued turns), or null when nothing is pending. */
    fun oldest(projection: JSONObject): JSONObject? {
        val inbox = projection.optJSONObject("inbox") ?: return null
        return (inbox.optJSONArray("next-step").items() + inbox.optJSONArray("next-turn").items()).firstOrNull { it.text("id").isNotBlank() }
    }

    /** The message's text parts as prompt content, or null when it holds anything else (attachments are not re-sent). */
    fun textContent(message: JSONObject): JSONArray? {
        val parts = message.optJSONArray("content").items()
        if (parts.isEmpty() || parts.any { it.text("type") != "text" }) return null
        return JSONArray().apply { parts.forEach { put(obj("type" to "text", "text" to it.text("text"))) } }
    }

    private fun JSONArray?.items(): List<JSONObject> = this?.objects() ?: emptyList()
}
