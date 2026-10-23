package dev.dsh.pocket.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Viewer/editor over DSH's own automation (`@deepseek-ai/dsh-schedule`). DSH 0.2.0 shipped it as the optional,
 * default-off `dsh-experimental-schedule-bundle`; since 0.2.1 `dsh-web-app` always mounts it. Tasks are created by the model with
 * `schedule_create` inside a session; the host stores them and delivers each due occurrence back into that session.
 *
 * Remote surface used here (all through the same cookie-authenticated RPC as the chat):
 * `pluginManager/listBundles` / `setBundleEnabled(name, enabled)` for the switch, and the `schedule` namespace:
 * `catalog()` (every stored task with its session), `update({request})`, `delete({request})`, `history({request})`.
 * There is no remote create. A bundle that is off has no `schedule` routes, so the catalog is read only when on.
 */
class ScheduleCenter(private val api: DshApi) {
    /** The bundle row from the plugin manager: null = not found (or not read yet, see [bundleRead]). */
    var bundle by mutableStateOf<JSONObject?>(null); private set
    var bundleRead by mutableStateOf(false); private set
    var bundleError by mutableStateOf(""); private set
    /** null = not asked yet, false = the schedule service is not mounted (catalog unreadable). */
    var service by mutableStateOf<Boolean?>(null); private set
    var tasks by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var error by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    val enabled: Boolean? get() = bundle?.let { it.optBoolean("enabled") }
    /** The service ships with DSH itself (no switch): see [ScheduleFormat.switchable]. */
    val builtIn: Boolean get() = bundle?.let { !ScheduleFormat.switchable(it) } == true

    suspend fun refresh() {
        busy = true
        try {
            val bundles = attempt { api.rpc("pluginManager/listBundles") as? JSONArray ?: JSONArray() }
            bundles.onSuccess { bundle = ScheduleFormat.findBundle(it); bundleError = "" }
                .onFailure { bundleError = it.message ?: "无法读取插件列表" }
            bundleRead = true
            // A known-off bundle has no schedule routes; anything else (on, or the manager unreadable) tries the catalog.
            if (bundles.isSuccess && enabled != true) { service = false; tasks = emptyList(); error = ""; return }
            readCatalog()
        } finally { busy = false }
    }

    /** Re-read the task list only (after a `schedule/changed` event); a no-op while the service is not mounted. */
    suspend fun reload() { if (service == true) readCatalog() }

    private suspend fun readCatalog() {
        val catalog = attempt { api.rpc("schedule/catalog") as? JSONArray ?: JSONArray() }
        catalog.onSuccess { service = true; tasks = it.objects(); error = "" }
            .onFailure { service = false; tasks = emptyList(); error = if (enabled == true) it.message ?: "无法读取任务目录" else "" }
    }

    /**
     * Turn the bundle on or off. "applied" mounts or unmounts the three rows (time context, schedule service, task
     * page) in the running host; "restart-required" only takes effect at the next DSH start. Stored tasks stay on disk
     * when off. Returns a sentence for the user.
     */
    suspend fun setEnabled(on: Boolean): String {
        val name = bundle?.text("name")?.takeIf { it.isNotBlank() } ?: error("本机 DSH 没有自动化任务组件")
        if (builtIn) error("自动化任务随 DSH 内置，不能单独开关")
        val result = api.rpc("pluginManager/setBundleEnabled", obj("name" to name, "enabled" to on)) as? JSONObject ?: obj()
        result.optJSONObject("error")?.let { error("插件管理拒绝：${it.text("code").ifBlank { "operation-error" }}${it.text("diagnostic").let { d -> if (d.isBlank()) "" else "（$d）" }}") }
        val message = when (result.text("application")) {
            "applied" -> if (on) "自动化任务已启用" else "自动化任务已关闭；已存的任务保留在本机"
            "restart-required" -> "已保存，重启 DSH 后生效（设置 → 运行环境）"
            "overridden" -> "已保存，但被 profile 配置覆盖，当前不生效"
            "cancelled" -> "操作已取消"
            "failed" -> error("插件管理未能应用这次修改")
            else -> "已提交"
        }
        // The service mounts asynchronously after "applied": give its routes a moment before reading the catalog.
        for (attempt in 0 until 4) {
            refresh()
            if (!on || result.text("application") != "applied" || service == true) break
            delay(800)
        }
        return message
    }

    /**
     * Edit title, prompt and/or timing of an active task. The host compares the whole expected record: on
     * `schedule_conflict` (a delivery or another edit moved it) the catalog is re-read and the same edit is tried once
     * more against the fresh record.
     */
    suspend fun update(entry: JSONObject, title: String?, prompt: String?, change: JSONObject?) {
        var current = entry
        for (attempt in 0..1) {
            val request = obj("sessionId" to current.text("sessionId"), "id" to current.text("id"),
                "expected" to ScheduleFormat.record(current), "title" to title, "prompt" to prompt, "change" to change)
            val result = api.call("schedule/update", request)
            val code = result.text("code")
            if (code.isEmpty()) { readCatalog(); return }
            if (code == "schedule_conflict" && attempt == 0) {
                readCatalog()
                current = tasks.firstOrNull { it.text("id") == entry.text("id") } ?: error(ScheduleFormat.failure("schedule_not_found"))
                continue
            }
            readCatalog()
            error(ScheduleFormat.failure(code, result.text("message")))
        }
    }

    /** Hard delete: the row and its saved run records go; the session and any queued message stay. */
    suspend fun delete(entry: JSONObject) {
        val result = api.call("schedule/delete", obj("sessionId" to entry.text("sessionId"), "id" to entry.text("id")))
        readCatalog()
        if (!result.optBoolean("deleted")) error(ScheduleFormat.failure(result.text("code").ifBlank { "schedule_not_found" }, result.text("message")))
    }

    /** One page (newest first) of saved deliveries; `before` is the previous page's `nextBefore`. */
    suspend fun history(entry: JSONObject, before: String? = null): JSONObject {
        val result = api.call("schedule/history", obj("sessionId" to entry.text("sessionId"), "id" to entry.text("id"), "limit" to 20, "before" to before))
        result.text("code").takeIf { it.isNotEmpty() }?.let { error(ScheduleFormat.failure(it, result.text("message"))) }
        return result
    }

    /** runCatching that never swallows coroutine cancellation (the catalog is also re-read from the events stream). */
    private inline fun <T> attempt(block: () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}

/** Pure helpers (JVM-testable): wire shapes, labels and the reminder framing. */
object ScheduleFormat {
    const val SERVICE_MODULE = "@deepseek-ai/dsh-schedule"
    private val IANA = Regex("^(UTC|[A-Za-z][A-Za-z0-9_+.-]*(/[A-Za-z0-9_+.-]+)+)$")
    private val CLOCK = Regex("^([01]\\d|2[0-3]):([0-5]\\d)(:([0-5]\\d))?$")
    private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

    /** The bundle whose rows include the schedule service; never matched by its package name. */
    fun findBundle(bundles: JSONArray): JSONObject? = bundles.objects().firstOrNull { b -> b.objects("rows").any { it.text("moduleName") == SERVICE_MODULE } }

    /** Only an optional bundle may be switched here. Since DSH 0.2.1 the service ships in the non-optional
     *  `dsh-web-app`; switching that bundle off would take the whole web host down with it. */
    fun switchable(bundle: JSONObject): Boolean = bundle.optBoolean("optional")

    /** The stored record only: the catalog's sessionId/status/lastDelivery are not part of `expected`. */
    fun record(entry: JSONObject): JSONObject {
        val keys = listOf("id", "kind", "title", "prompt", "scheduledAt") + when (entry.text("kind")) {
            "after" -> listOf("afterSeconds")
            "every" -> listOf("everySeconds")
            "daily" -> listOf("time", "timeZone")
            "weekly" -> listOf("time", "timeZone", "weekdays")
            "cron" -> listOf("expression", "timeZone")
            else -> emptyList()
        }
        return JSONObject().apply { keys.forEach { k -> if (entry.has(k)) put(k, entry.get(k)) } }
    }

    fun validZone(zone: String) = IANA.matches(zone)
    /** "9:5" is not a clock; "09:05" and "09:05:30" are. Returns HH:mm:ss or null. */
    fun clock(text: String): String? = CLOCK.matchEntire(text.trim())?.let { m -> "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[4].ifEmpty { "00" }}" }
    /** Stored HH:mm:ss(.fff) shown as HH:mm, keeping non-zero seconds. */
    fun shortTime(time: String): String {
        val base = time.substringBefore('.')
        return if (base.length == 8 && base.endsWith(":00")) base.take(5) else base
    }
    fun weekdays(days: List<Int>): String = when (days.sorted()) {
        listOf(1, 2, 3, 4, 5) -> "工作日"
        listOf(6, 7) -> "周末"
        (1..7).toList() -> "每天"
        else -> "每周" + days.sorted().joinToString("、") { WEEKDAYS.getOrElse(it - 1) { "?" } }
    }
    fun interval(seconds: Long): String = when {
        seconds % 86_400 == 0L -> "每 ${seconds / 86_400} 天"
        seconds % 3_600 == 0L -> "每 ${seconds / 3_600} 小时"
        seconds % 60 == 0L -> "每 ${seconds / 60} 分钟"
        else -> "每 $seconds 秒"
    }

    /** "每天 09:00", "工作日 08:30 · Asia/Tokyo", "每 30 分钟", "仅一次", "Cron 0 9 * * 1-5". */
    fun frequency(entry: JSONObject, deviceZone: String = ZoneId.systemDefault().id): String {
        val zone = entry.text("timeZone").takeIf { it.isNotBlank() && it != deviceZone }?.let { " · $it" }.orEmpty()
        return when (entry.text("kind")) {
            "after", "at" -> "仅一次"
            "every" -> interval(entry.optLong("everySeconds"))
            "daily" -> "每天 ${shortTime(entry.text("time"))}$zone"
            "weekly" -> "${weekdays(entry.optJSONArray("weekdays").ints())} ${shortTime(entry.text("time"))}$zone"
            "cron" -> "Cron ${entry.text("expression")}$zone"
            else -> entry.text("kind")
        }
    }

    fun instant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    /** Local wall clock of an RFC 3339 instant: "10月21日 周三 09:00" (year added when it differs from now). */
    fun localTime(value: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = instant(value) ?: return value
        val local = LocalDateTime.ofInstant(at, zone); val today = LocalDateTime.ofInstant(now, zone)
        val pattern = if (local.year == today.year) "M月d日 EEE HH:mm" else "yyyy年M月d日 HH:mm"
        return local.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
    }
    fun relative(value: String, now: Instant = Instant.now()): String {
        val at = instant(value) ?: return ""
        val minutes = (at.epochSecond - now.epochSecond) / 60
        return when {
            minutes < 0 -> "已到期，等待投递"
            minutes < 1 -> "不到 1 分钟后"
            minutes < 60 -> "$minutes 分钟后"
            minutes < 48 * 60 -> "${minutes / 60} 小时${if (minutes % 60 > 0) " ${minutes % 60} 分钟" else ""}后"
            else -> "${minutes / 1440} 天后"
        }
    }

    /** A timing `change` for `schedule/update`; the kinds and argument names are the host's create selectors. */
    fun once(date: String, time: String, zone: String) = obj("kind" to "at", "at" to obj("date" to date, "time" to time, "time_zone" to zone))
    fun every(seconds: Long) = obj("kind" to "every", "every_seconds" to seconds)
    fun daily(time: String, zone: String) = obj("kind" to "daily", "daily" to obj("time" to time, "time_zone" to zone))
    fun weekly(time: String, zone: String, days: List<Int>) = obj("kind" to "weekly", "weekly" to obj("time" to time, "time_zone" to zone, "weekdays" to JSONArray(days.sorted())))
    fun cron(expression: String, zone: String) = obj("kind" to "cron", "cron" to obj("expression" to expression.trim().replace(Regex("\\s+"), " "), "time_zone" to zone))

    /** Host result codes in the user's words. */
    fun failure(code: String, message: String = ""): String = when (code) {
        "schedule_not_found" -> "任务已不存在（可能刚被删除），列表已刷新"
        "schedule_ended" -> "任务已结束，不能再修改"
        "schedule_conflict" -> "任务刚被投递或修改过，请重新打开后再改"
        "invalid_prompt" -> "名称或内容无效：名称 1–120 字，内容不能为空"
        "invalid_rule" -> "时间规则无效${if (message.isNotBlank()) "：$message" else ""}"
        "invalid_selector" -> "时间设置无效：只能选一种规则"
        "invalid_time_zone" -> "时区无效，请填写 IANA 时区（如 Asia/Shanghai）"
        "not_future" -> "时间必须晚于现在"
        "time_out_of_range" -> "时间超出可用范围"
        "frequency_too_high" -> "间隔至少 60 秒"
        "subagent_session" -> "子代理的会话不能设置自动化任务"
        "delivery_cursor_not_found" -> "运行记录已变化，请刷新"
        else -> "操作未完成（$code）${if (message.isNotBlank()) "：$message" else ""}"
    }

    /**
     * The reminder prompt(s) inside a delivered `[SCHEDULE REMINDER]` / `[SCHEDULE REMINDER BATCH]` message, or null
     * when the text is not that framing.
     */
    fun reminderPrompts(text: String): List<String>? {
        val lines = text.lines()
        fun payload(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        return when (lines.firstOrNull()?.trim()) {
            "[SCHEDULE REMINDER]" -> payload("reminder_prompt_json: ")?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }?.let { listOf(it) }
            "[SCHEDULE REMINDER BATCH]" -> payload("reminders_json: ")?.let { raw -> runCatching { JSONArray(raw).objects().map { it.text("reminder_prompt") } }.getOrNull() }
            else -> null
        }
    }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    /**
     * Timing as the user edits it: kind once/daily/weekly/every/cron. [change] is the host's `change` argument,
     * [describe] the same rule in words for a drafted request to the model, [problem] the first input error.
     */
    data class TimingDraft(val kind: String = "daily", val date: String = "", val time: String = "09:00", val days: Set<Int> = setOf(1, 2, 3, 4, 5),
                           val minutes: String = "60", val cron: String = "", val zone: String = ZoneId.systemDefault().id) {
        private val clockValue get() = clock(time)
        fun problem(): String? = when {
            kind != "every" && !validZone(zone.trim()) -> "时区无效，请填写 IANA 时区（如 Asia/Shanghai）"
            kind in setOf("once", "daily", "weekly") && clockValue == null -> "时间格式为 HH:mm，例如 09:00"
            kind == "once" && runCatching { java.time.LocalDate.parse(date.trim()) }.isFailure -> "日期格式为 yyyy-MM-dd，例如 2026-10-21"
            kind == "weekly" && days.isEmpty() -> "至少选择一天"
            kind == "every" && (minutes.trim().toLongOrNull() ?: 0L) < 1 -> "间隔至少 1 分钟"
            kind == "cron" && cron.trim().split(Regex("\\s+")).size != 5 -> "Cron 需要 5 段：分 时 日 月 周"
            kind !in setOf("once", "daily", "weekly", "every", "cron") -> "请选择时间规则"
            else -> null
        }
        fun change(): JSONObject = when (kind) {
            "once" -> once(date.trim(), clockValue!!, zone.trim())
            "every" -> every(minutes.trim().toLong() * 60)
            "weekly" -> weekly(clockValue!!, zone.trim(), days.toList())
            "cron" -> ScheduleFormat.cron(cron, zone.trim())
            else -> daily(clockValue!!, zone.trim())
        }
        fun describe(): String = when (kind) {
            "once" -> "${date.trim()} ${shortTime(clockValue ?: time)}，仅一次（时区 ${zone.trim()}）"
            "every" -> "${interval(minutes.trim().toLong() * 60)}一次"
            "weekly" -> "${weekdays(days.toList())} ${shortTime(clockValue ?: time)}（时区 ${zone.trim()}）"
            "cron" -> "按 cron 表达式 ${cron.trim()}（时区 ${zone.trim()}）"
            else -> "每天 ${shortTime(clockValue ?: time)}（时区 ${zone.trim()}）"
        }
        companion object {
            /** The draft that shows a stored task's current rule (one-shot targets in [zone]). */
            fun of(entry: JSONObject, zone: ZoneId = ZoneId.systemDefault()): TimingDraft {
                val stored = entry.text("timeZone").ifBlank { zone.id }
                return when (entry.text("kind")) {
                    "after", "at" -> {
                        val local = instant(entry.text("scheduledAt"))?.let { LocalDateTime.ofInstant(it, zone) }
                        TimingDraft("once", local?.toLocalDate()?.toString().orEmpty(), local?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "09:00", zone = zone.id)
                    }
                    "every" -> TimingDraft("every", minutes = (entry.optLong("everySeconds") / 60).coerceAtLeast(1).toString(), zone = zone.id)
                    "weekly" -> TimingDraft("weekly", time = shortTime(entry.text("time")), days = entry.optJSONArray("weekdays").ints().toSet(), zone = stored)
                    "cron" -> TimingDraft("cron", cron = entry.text("expression"), zone = stored)
                    else -> TimingDraft("daily", time = shortTime(entry.text("time")), zone = stored)
                }
            }
        }
    }

    /** The request a new task starts from: the model creates it with schedule_create in a new Cowork session. */
    fun request(title: String, task: String, timing: TimingDraft): String =
        "请用自动化任务（schedule_create）安排下面这件事，创建后告诉我结果，现在不用执行：\n名称：${title.trim()}\n时间：${timing.describe()}\n到时要做：${task.trim()}"
}
