package dev.dsh.pocket.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read/write gateway to the `dsh-mneme` plugin running inside the on-device DSH.
 *
 * The plugin serves plain HTTP routes under `/api/dsh-mneme/` authenticated by the same session cookie as
 * the RPC API; [DshApi.rest] covers that. Two behaviours are inherited from the plugin and are surfaced
 * rather than hidden: feature-flag writes and the standard/light mode are persisted to the settings kv
 * and only take effect the next time DSH boots, and every write route is rejectable — so each call
 * reports success to the caller instead of assuming it landed.
 */
class MemoryCenter(private val api: DshApi) {
    /** Result of the last probe: null = not asked yet, false = plugin absent (every route 404s). */
    var available by mutableStateOf<Boolean?>(null); private set
    var error by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set

    var version by mutableStateOf(""); private set
    var mode by mutableStateOf(""); private set
    var features by mutableStateOf(obj()); private set
    var overrides by mutableStateOf(obj()); private set
    var total by mutableStateOf(0); private set
    var archivedTotal by mutableStateOf(0); private set
    var semanticReady by mutableStateOf(false); private set
    var semanticDetail by mutableStateOf(""); private set
    var injectStatus by mutableStateOf(obj()); private set
    var dream by mutableStateOf(obj()); private set

    var entries by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var entriesTotal by mutableStateOf(0); private set
    var filter by mutableStateOf("all"); private set
    var query by mutableStateOf(""); private set
    var loadingMore by mutableStateOf(false); private set
    var hasMore by mutableStateOf(false); private set

    /** Whether the effective flag set came from the plugin or from our optimistic edit. */
    var dirtyFlags by mutableStateOf(false); private set

    private fun path(tail: String) = "/api/dsh-mneme$tail"

    private suspend fun fetch(tail: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        api.rest(path(tail), method, body)

    /** Probe + load everything the settings page needs. Cheap: nine small JSON reads. */
    suspend fun refresh() {
        busy = true
        try {
            val info = runCatching { fetch("/info") }
            if (info.isFailure) {
                // 404 means "this DSH has no mneme"; anything else (timeout, 5xx) leaves the previous
                // verdict alone so a transient blip does not flip the page to "not installed".
                val cause = info.exceptionOrNull()
                if (cause is PluginUnavailableException) { available = false; error = "" }
                else error = cause?.message ?: "无法读取记忆插件"
                return
            }
            available = true
            error = ""
            version = info.getOrNull()?.text("version") ?: ""
            coroutineScope {
                val flags = async { runCatching { fetch("/features") }.getOrNull() }
                val list = async { runCatching { fetch("/list?limit=1&order=chrono") }.getOrNull() }
                val archived = async { runCatching { fetch("/list?limit=1&archived=only") }.getOrNull() }
                val semantic = async { runCatching { fetch("/semantic") }.getOrNull() }
                val injection = async { runCatching { fetch("/inject-status") }.getOrNull() }
                val sleep = async { runCatching { fetch("/dream-status") }.getOrNull() }
                val panel = async { runCatching { fetch("/mode") }.getOrNull() }
                flags.await()?.let {
                    features = it.optJSONObject("effective") ?: obj()
                    overrides = it.optJSONObject("overrides") ?: obj()
                }
                list.await()?.let { total = it.optInt("total", 0) }
                archived.await()?.let { archivedTotal = it.optInt("total", 0) }
                semantic.await()?.let {
                    semanticReady = it.optBoolean("ready") && it.optBoolean("configured")
                    semanticDetail = if (semanticReady) {
                        buildString {
                            append(it.optString("embedProvider").ifBlank { "向量" })
                            it.optJSONObject("index")?.let { index ->
                                append(" · ").append(index.optInt("embeddedCount")).append("/").append(index.optInt("totalCount"))
                            }
                        }
                    } else when (it.text("reason")) {
                        "not-configured" -> "未配置（仅关键词检索）"
                        "" -> "不可用"
                        else -> it.text("reason")
                    }
                }
                injection.await()?.let { injectStatus = it }
                sleep.await()?.let { dream = it }
                panel.await()?.let { mode = it.text("mode") }
            }
            if (entries.isEmpty() || dirtyFlags) load()
        } finally { busy = false }
    }

    fun selectFilter(value: String) { if (filter != value) { filter = value; query = "" } }

    suspend fun load() = loadPage(reset = true)

    suspend fun more() {
        if (!hasMore || loadingMore) return
        loadPage(reset = false)
    }

    private suspend fun loadPage(reset: Boolean) {
        val offset = if (reset) 0 else entries.size
        loadingMore = !reset
        try {
            val tail = buildString {
                append("/list?limit=30&offset=").append(offset)
                when (filter) {
                    "archived" -> append("&archived=only")
                    "deposited" -> append("&deposited=only")
                    "all" -> append("&order=chrono")
                    else -> append("&type=").append(filter).append("&order=chrono")
                }
            }
            val page = fetch(tail)
            val items = page.objects("items")
            entries = if (reset) items else entries + items
            entriesTotal = page.optInt("total", entries.size)
            hasMore = entries.size < entriesTotal
            dirtyFlags = false
        } finally { loadingMore = false }
    }

    suspend fun search(text: String) {
        query = text.trim()
        if (query.isEmpty()) { load(); return }
        busy = true
        try {
            val result = fetch("/search?mode=auto&limit=40&q=" + java.net.URLEncoder.encode(query, "UTF-8"))
            entries = result.objects("items")
            entriesTotal = entries.size
            hasMore = false
            dirtyFlags = false
        } finally { busy = false }
    }

    /**
     * Patch one or more feature flags. Dotted keys must stay flat on the wire: the plugin rejects a
     * nested object (`{"memoryQualityFilter":{...}}` → 400 unknown feature flag) and only understands
     * the flattened `"memoryQualityFilter.enabled"` spelling.
     */
    suspend fun patchFlag(key: String, value: Any) {
        val result = fetch("/features", "PUT", obj(key to value))
        features = result.optJSONObject("effective") ?: features
        overrides = result.optJSONObject("overrides") ?: overrides
        dirtyFlags = false
    }

    suspend fun setMode(value: String) { mode = fetch("/mode", "PUT", obj("mode" to value)).text("mode") }

    suspend fun update(id: String, patch: JSONObject) { fetch("/update", "POST", patch.put("id", id)) }

    suspend fun remove(id: String) { fetch("/delete", "POST", obj("id" to id)) }

    suspend fun create(type: String, title: String, content: String, importance: Int) {
        fetch("/create", "POST", obj("type" to type, "title" to title, "content" to content, "importance" to importance))
    }

    suspend fun setArchived(id: String, archived: Boolean) = update(id, obj("archived" to archived))

    suspend fun setImportance(id: String, importance: Int) = update(id, obj("importance" to importance))

    suspend fun injectionPreview(): JSONObject = fetch("/inject-preview").optJSONObject("snapshot") ?: obj()

    /**
     * `dryRun` answers "how much is reclaimable now". The destructive form must send both
     * `dryRun:false` and `confirm:true` — the plugin treats a missing dryRun as true, so a bare
     * confirm would silently plan again instead of deleting.
     */
    suspend fun reclaim(dryRun: Boolean): JSONObject =
        if (dryRun) fetch("/maintenance/reclaim")
        else fetch("/maintenance/reclaim", "POST", obj("dryRun" to false, "confirm" to true))

    suspend fun recallStats(days: Int = 30): JSONObject = fetch("/recall-stats?window=$days")

    /** Clear a plugin-level failure without discarding the loaded data. */
    fun clearError() { error = "" }

    companion object {
        /** Filters offered as chips; `all`/`archived`/`deposited` are views, the rest are memory types. */
        val TYPES = listOf("all", "archived", "deposited", "preference", "project", "decision", "history", "pitfall", "constraint", "document")
        fun label(value: String) = when (value) {
            "all" -> "全部"
            "archived" -> "归档"
            "deposited" -> "已沉淀"
            "preference" -> "偏好"
            "project" -> "项目"
            "decision" -> "决策"
            "history" -> "历史"
            "rejected_solution" -> "已否决"
            "pitfall" -> "教训"
            "constraint" -> "约束"
            "document" -> "文档"
            else -> value
        }
    }
}

internal fun JSONObject.stringArray(key: String): List<String> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
}