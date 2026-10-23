package dev.dsh.pocket.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app has exactly two task modes; there is no basic chat mode. Each binds to a DSH
 * agent preset id. The native Cowork bundle registers cowork-phone; old cowork rows remain classified.
 */
enum class TaskMode(val preset: String, val title: String) {
    COWORK("cowork-phone", "Cowork"),
    CODE("standard", "Code"),
}

/** Broken registrations are visible diagnostically, but must never enable a task entry point. */
fun availablePresetIds(roster: JSONObject?): Set<String>? = roster?.objects("presets")
    ?.filter { !it.has("broken") || it.isNull("broken") }
    ?.map { it.text("id") }?.filter { it.isNotBlank() }?.toSet()

private fun values(row: JSONObject?): JSONObject? = row?.optJSONObject("projections")?.optJSONObject("values")
fun sessionPreset(row: JSONObject?): String = values(row)?.text("agentPreset").orEmpty()
/** Any non-cowork preset (standard, ptc, minimal …) is presented under Code. */
fun sessionMode(row: JSONObject?): TaskMode = if (sessionPreset(row) in setOf(TaskMode.COWORK.preset, "cowork")) TaskMode.COWORK else TaskMode.CODE
/** Historical cowork sessions stay recognisable without changing their persisted preset. */
fun presetTag(row: JSONObject?): String? = sessionPreset(row).takeIf { it.isNotBlank() && it != "cowork" && TaskMode.entries.none { mode -> mode.preset == it } }

enum class SessionFilter(val label: String) {
    ALL("全部"), NEEDS_INPUT("需要输入"), WORKING("运行中"), COMPLETED("已完成"), APPS("自动运营"), ARCHIVED("已归档"),
}

/** The standing session the notification trigger keeps for each app (cowork-app-<profile>-<package>): listed only under APPS. */
fun isAppSession(row: JSONObject): Boolean = row.text("sessionId").startsWith("cowork-app-")

fun needsInput(row: JSONObject, pendingAgents: Set<String>): Boolean = row.text("sessionId") in pendingAgents ||
    values(row)?.optJSONObject("userQuestions")?.objects("active")?.any { it.text("state") == "open" } == true

fun matchesFilter(row: JSONObject, filter: SessionFilter, archived: Set<String>, pendingAgents: Set<String>): Boolean {
    val isArchived = row.text("sessionId") in archived
    if (filter == SessionFilter.ARCHIVED) return isArchived
    if (isArchived) return false
    if (isAppSession(row) != (filter == SessionFilter.APPS)) return false
    val running = row.optBoolean("running")
    return when (filter) {
        SessionFilter.ALL, SessionFilter.APPS -> true
        SessionFilter.NEEDS_INPUT -> needsInput(row, pendingAgents)
        SessionFilter.WORKING -> running
        SessionFilter.COMPLETED -> !running && !needsInput(row, pendingAgents) && !row.optBoolean("blank")
        SessionFilter.ARCHIVED -> isArchived
    }
}

fun workspaceLabel(cwd: String?): String {
    val path = cwd.orEmpty().trim()
    if (path.isEmpty()) return "本机"
    return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
}

data class ToolLabel(val verb: String, val kind: String)
/** Tool name from a transcript row title of the form "name — 状态". */
fun toolName(row: TranscriptRow): String = row.title.substringBefore(" — ").trim()
fun toolLabel(name: String): ToolLabel {
    val n = name.lowercase(Locale.ROOT)
    fun has(vararg parts: String) = parts.any { n.contains(it) }
    return when {
        has("web_search", "web_fetch", "websearch", "webfetch", "browser", "http", "url") || n == "fetch" -> ToolLabel("访问网络", "web")
        has("bash", "shell", "exec", "terminal", "command", "pwsh", "powershell", "run_") -> ToolLabel("运行命令", "terminal")
        has("phone", "tap", "swipe", "screen", "display") -> ToolLabel("操作副屏", "phone")
        has("question", "ask_user") -> ToolLabel("向你提问", "question")
        has("todo", "plan", "goal") -> ToolLabel("更新计划", "plan")
        has("agent", "subagent", "workflow", "teammate", "delegate") -> ToolLabel("委派子任务", "agent")
        has("write", "edit", "patch", "apply", "replace", "create_file") -> ToolLabel("编辑文件", "write")
        has("read", "view", "cat", "open") -> ToolLabel("读取文件", "read")
        has("grep", "glob", "search", "find", "list", "ls") -> ToolLabel("搜索文件", "search")
        has("image", "screenshot", "photo") -> ToolLabel("查看图像", "image")
        else -> ToolLabel("使用工具", "generic")
    }
}

/** Parsed tool-call arguments from a tool row's detail ("Arguments:\n{json}"), if they are a JSON object. */
fun toolArguments(row: TranscriptRow): JSONObject? {
    val marker = "Arguments:\n"
    val start = row.detail.indexOf(marker)
    if (start < 0) return null
    val raw = row.detail.substring(start + marker.length)
    return try { org.json.JSONTokener(raw).nextValue() as? JSONObject } catch (_: Exception) { null }
}
private val TARGET_KEYS = listOf("command", "cmd", "file_path", "filePath", "path", "url", "query", "pattern", "q", "description", "prompt", "title", "text", "name")
/** A one-line hint of what the tool acted on (command, path, URL, query…). Empty when unknown. */
fun toolTarget(row: TranscriptRow): String {
    val args = toolArguments(row) ?: return ""
    for (key in TARGET_KEYS) {
        val value = args.opt(key)
        val text = when (value) {
            is String -> value
            is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }.joinToString(", ")
            else -> ""
        }.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (text.isNotEmpty()) return text.take(160)
    }
    (args.opt("queries") as? org.json.JSONArray)?.let { q -> (0 until q.length()).mapNotNull { q.opt(it) as? String }.firstOrNull()?.let { return it.take(160) } }
    return ""
}
/** Tool status from the reducer's title suffix. */
fun toolState(row: TranscriptRow): String = when {
    row.streaming -> "running"; row.error -> "failed"; else -> "done"
}

/** Display names for the provider/model route. Provider stays visible to disambiguate equal model ids. */
data class ModelChoice(
    val provider: String, val providerName: String,
    val model: String, val modelName: String,
    val effort: String, val effortName: String,
) {
    val selected: Boolean get() = provider.isNotBlank() && model.isNotBlank()
}
fun modelChoice(catalog: JSONObject, selection: JSONObject?): ModelChoice {
    val provider = selection?.text("provider").orEmpty()
    val model = selection?.text("model").orEmpty()
    val effort = selection?.text("reasoningEffort").orEmpty()
    val group = catalog.objects("groups").firstOrNull { it.text("id") == provider }
    val entry = group?.objects("models")?.firstOrNull { it.text("id") == model }
    val effortName = entry?.optJSONObject("reasoning")?.objects("efforts")?.firstOrNull { it.text("id") == effort }?.text("name").orEmpty()
    return ModelChoice(
        provider, group?.text("name")?.ifBlank { null } ?: provider,
        model, entry?.text("name")?.ifBlank { null } ?: model,
        effort, effortName.ifBlank { effort },
    )
}

data class TodoEntry(val content: String, val status: String)
fun todoItems(projection: JSONObject): List<TodoEntry> = projection.optJSONArray("todos")?.objects()
    ?.map { TodoEntry(it.text("content"), it.text("status")) }?.filter { it.content.isNotBlank() }.orEmpty()

data class GoalInfo(val objective: String, val phase: String, val blockedReason: String)
fun goalInfo(projection: JSONObject): GoalInfo? = projection.optJSONObject("goal")?.optJSONObject("goal")?.let {
    GoalInfo(it.text("objective"), it.text("phase"), it.optJSONObject("blockedReason")?.text("message").orEmpty())
}?.takeIf { it.objective.isNotBlank() && it.phase != "complete" }
fun goalPhaseLabel(phase: String): String = when (phase) {
    "active" -> "进行中"; "paused" -> "已暂停"; "blocked" -> "受阻"; "complete" -> "已完成"; else -> phase
}

fun relativeTime(value: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (value <= 0) return ""
    val diff = (now - value).coerceAtLeast(0)
    if (diff < 60_000) return "刚刚"
    if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
    val then = Calendar.getInstance(zone).apply { timeInMillis = value }
    val today = Calendar.getInstance(zone).apply { timeInMillis = now }
    fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    if (dayIndex(then) == dayIndex(today)) return "${diff / 3_600_000} 小时前"
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dayIndex(then) == dayIndex(yesterday)) return "昨天"
    val pattern = if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日"
    return SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone }.format(Date(value))
}

/** A small rotating pool, in the spirit of the reference app's greetings. */
fun greeting(hour: Int, dayOfWeek: Int, seed: Int): String {
    val time = when (hour) {
        in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..17 -> "下午好"; in 18..22 -> "晚上好"; else -> "夜深了"
    }
    val weekday = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[(dayOfWeek - 1).mod(7)]
    val pool = buildList {
        add(time); add("$time，今天想完成什么？"); add("欢迎回来"); add("${weekday}好")
        if (hour >= 23 || hour < 5) add("夜猫子，你好") else add("从哪里开始？")
        if (dayOfWeek == Calendar.FRIDAY) add("周五的感觉真好")
        if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) add("周末也在忙？")
        if (hour in 14..17) add("来杯咖啡，开始干活？")
    }
    return pool[seed.mod(pool.size)]
}
