package dev.dsh.pocket.data

import org.json.JSONObject

/** Provider ids, not display names, identify distinct routes even for identical model ids. */
data class ModelIdentity(val provider: String, val model: String, val effort: String) {
    val label: String get() = "$provider · $model"
}
fun modelIdentity(selection: JSONObject?): ModelIdentity = ModelIdentity(
    selection?.text("provider")?.ifBlank { "未选择提供方" } ?: "未选择提供方",
    selection?.text("model")?.ifBlank { "选择模型" } ?: "选择模型",
    selection?.text("reasoningEffort").orEmpty(),
)

fun permissionLabel(value: String): String = when (value) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区读写"
    "danger-full-access" -> "完全访问"
    "" -> "默认权限"
    else -> "自定义权限"
}
fun permissionDescription(value: String): String = when (value) {
    "read-only" -> "只允许读取，不允许修改文件。"
    "workspace-write" -> "允许修改工作区内的文件，其他位置受限。"
    "danger-full-access" -> "不限制文件访问范围，请谨慎使用。"
    else -> "由 DSH 配置定义的权限。"
}

/** Workspace is chosen before a session is created; an active session never changes cwd. */
fun canChooseWorkspace(sessionId: String?, starting: Boolean, submitting: Boolean, uncertain: Boolean): Boolean =
    sessionId == null && !starting && !submitting && !uncertain

data class ChatEntry(val key: String, val kind: String, val rows: List<TranscriptRow>)
/**
 * Coalesce adjacent tool calls into one record, and adjacent audit rows into another.
 * A user or assistant message always splits the groups. Order is preserved.
 */
fun chatEntries(rows: List<TranscriptRow>): List<ChatEntry> {
    val keys = ArrayList<String>(); val kinds = ArrayList<String>(); val groups = ArrayList<ArrayList<TranscriptRow>>()
    rows.forEach { row ->
        val kind = when (row.role) {
            "tool" -> "tools"
            "notice", "system", "developer" -> if (row.error) "message" else "activity"
            else -> "message"
        }
        if (kind != "message" && kinds.lastOrNull() == kind) groups[groups.lastIndex].add(row)
        else { keys += row.key; kinds += kind; groups += arrayListOf(row) }
    }
    return groups.indices.map { ChatEntry(keys[it], kinds[it], groups[it]) }
}

/** Single-choice custom input overrides selected options; multi-choice can combine both. */
fun questionAnswers(questions: List<JSONObject>, selected: Map<String, Set<String>>, custom: Map<String, String>): JSONObject =
    obj("answers" to org.json.JSONArray(questions.map { question ->
        val id = question.text("id")
        val freeText = custom[id]?.trim()?.takeIf { it.isNotEmpty() }
        obj("id" to id, "selected" to org.json.JSONArray(
            if (!question.optBoolean("multiSelect") && freeText != null) emptyList<String>() else selected[id].orEmpty().toList()), "custom" to freeText)
    }))

fun continuedQuestions(projection: JSONObject): List<JSONObject> = projection.optJSONObject("userQuestions")?.objects("active")
    ?.filter { it.text("state") == "continued" }.orEmpty()

/** Combine output AND arguments; a metadata string must never hide the tool's result. */
fun transcriptDetail(row: TranscriptRow): String = listOf(row.text, row.reasoning, row.detail)
    .filter { it.isNotBlank() }.distinct().joinToString("\n\n")
