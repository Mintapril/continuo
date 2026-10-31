package dev.dsh.pocket.data

import org.json.JSONObject

/**
 * The Jev focus the Cowork model keeps per app: one line it adds to the Jev judge's question about that app's
 * notifications, so it decides itself which of them wake it (cowork-plugin/src/jev-focus.mjs). Read and cleared over
 * DSH's RPC (namespace `coworkUnattended`); the owner can only clear one. A cleared focus stays stored with its time
 * (text "") so a run nobody watches cannot set it again at once; the list leaves it out.
 */
object JevFocus {
    const val READ = "coworkUnattended/jevFocus"
    const val CLEAR = "coworkUnattended/clearJevFocus"

    /** One app's focus; [app] is the plugin's key, `<profile>:<package>`. */
    data class Entry(val app: String, val profile: String, val packageName: String, val text: String, val at: Long, val by: String)

    /** The focuses in effect, newest change first. */
    fun entries(stored: JSONObject?): List<Entry> {
        if (stored == null) return emptyList()
        return stored.keys().asSequence().mapNotNull { app ->
            val entry = stored.optJSONObject(app) ?: return@mapNotNull null
            val text = entry.text("text"); val profile = app.substringBefore(':', ""); val pkg = app.substringAfter(':', "")
            if (text.isBlank() || profile !in setOf("work", "personal") || pkg.isBlank()) null
            else Entry(app, profile, pkg, text, entry.optLong("at"), entry.text("by"))
        }.sortedByDescending { it.at }.toList()
    }

    /** The `clearJevFocus` arguments. */
    fun clearRequest(app: String): JSONObject = obj("request" to obj("app" to app))

    /** When the model changed it, in words. */
    fun byLabel(by: String): String = when (by) {
        "owner" -> "和你对话时"
        "patrol" -> "自己逛的时候"
        "notify" -> "通知唤起时"
        "unattended" -> "无人值守时"
        else -> ""
    }
}
