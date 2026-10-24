package dev.dsh.pocket.data

import org.json.JSONObject

/**
 * The model unattended Cowork runs use: patrols and runs a notification trigger started each may name one selection of
 * DSH's model catalog, or none (the session keeps its own model). Stored by the Cowork plugin and read over DSH's RPC
 * (namespace `coworkUnattended`, cowork-plugin/src/unattended.mjs); the plugin applies it per request, so DSH's default
 * model and the sessions' own selections stay as they are.
 */
object UnattendedModels {
    const val PATROL = "patrol"
    const val NOTIFY = "notify"
    const val READ = "coworkUnattended/models"
    const val WRITE = "coworkUnattended/setModel"

    /** The `setModel` arguments; a blank provider or model clears the setting. */
    fun request(kind: String, provider: String?, model: String?, effort: String?): JSONObject {
        require(kind == PATROL || kind == NOTIFY) { "unknown kind $kind" }
        val selection: Any = if (provider.isNullOrBlank() || model.isNullOrBlank()) JSONObject.NULL
            else obj("provider" to provider, "model" to model, "reasoningEffort" to effort?.takeIf { it.isNotBlank() })
        return obj("request" to obj("kind" to kind, "selection" to selection))
    }

    /** The stored selection of [kind], or null when it follows the session. */
    fun selection(stored: JSONObject?, kind: String): JSONObject? =
        stored?.optJSONObject(kind)?.takeIf { it.text("provider").isNotBlank() && it.text("model").isNotBlank() }

    /** How a setting reads in the list: the catalog's model name (provider name when ids repeat), else the raw ids. */
    fun label(selection: JSONObject?, catalog: JSONObject): String {
        if (selection == null) return "跟随会话"
        val group = catalog.objects("groups").firstOrNull { it.text("id") == selection.text("provider") }
        val model = group?.objects("models")?.firstOrNull { it.text("id") == selection.text("model") }
        val name = model?.text("name")?.ifBlank { null } ?: selection.text("model")
        val provider = group?.text("name")?.ifBlank { null } ?: selection.text("provider")
        val effort = selection.text("reasoningEffort").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "$provider · $name$effort"
    }
}
