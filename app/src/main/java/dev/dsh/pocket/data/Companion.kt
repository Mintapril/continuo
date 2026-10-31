package dev.dsh.pocket.data

import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The AI as a companion: the one conversation the owner has with it (叫它), where everything it does lives since
 * 2026-10-26 (patrols, notes and every app's notifications too), the diary of what it did, and the notes the owner
 * leaves it, written straight into DSH's scheduler by the Cowork plugin (cowork-plugin/src/unattended.mjs).
 * Pure, tested on the JVM.
 */
object Companion {
    /**
     * The first conversation 叫它 goes to (a Cowork session created on first use). Clearing the conversation moves 叫它
     * to a fresh session, `cowork-owner-<local time>`; the current one is kept by [OwnerConversation].
     */
    const val OWNER_SESSION = "cowork-owner"
    fun ownerTitle(name: String) = "你和${name.ifBlank { "它" }}"
    /** Whether [id] is one of the owner's conversations with it, the current one or an earlier, cleared one. */
    fun isOwnerSession(id: String?): Boolean = id == OWNER_SESSION || (id != null && OWNER_NEXT.matches(id))
    private val OWNER_NEXT = Regex("""cowork-owner-\d{8}-\d{6}""")
    /** The session a cleared conversation continues in (valid for the broker's `[a-zA-Z0-9_-]{1,100}`). */
    fun nextOwnerSession(now: LocalDateTime): String = "$OWNER_SESSION-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    /**
     * An active note of the cleared conversation, written again in [sessionId] as it is: same title and prompt (the
     * markers of the model's own notes and of patrols included), same rule; a one-time note keeps its exact time.
     */
    fun movedNote(sessionId: String, entry: JSONObject, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val change = when (entry.text("kind")) {
            "after", "at" -> {
                val at = ScheduleFormat.instant(entry.text("scheduledAt")) ?: error("便条「${entry.text("title")}」的时间读不出来")
                val local = at.atZone(zone)
                ScheduleFormat.once(local.toLocalDate().toString(), local.format(DateTimeFormatter.ofPattern("HH:mm:ss")), zone.id)
            }
            "every" -> ScheduleFormat.every(entry.optLong("everySeconds"))
            "daily" -> ScheduleFormat.daily(entry.text("time"), entry.text("timeZone"))
            "weekly" -> ScheduleFormat.weekly(entry.text("time"), entry.text("timeZone"),
                entry.optJSONArray("weekdays")?.let { days -> (0 until days.length()).map { days.optInt(it) } }.orEmpty())
            "cron" -> ScheduleFormat.cron(entry.text("expression"), entry.text("timeZone"))
            else -> error("便条「${entry.text("title")}」的时间规则不认识")
        }
        return obj("request" to obj("sessionId" to sessionId, "title" to entry.text("title"), "prompt" to entry.text("prompt"), "change" to change))
    }

    const val IDENTITY = "coworkUnattended/identity"
    const val SET_IDENTITY = "coworkUnattended/setIdentity"
    const val DIARY = "coworkUnattended/diary"
    const val EVENTS = "coworkUnattended/events"
    const val CREATE_NOTE = "coworkUnattended/createNote"
    const val SET_OWNER_SESSION = "coworkUnattended/setOwnerSession"

    fun identityRequest(name: String) = obj("request" to obj("name" to name.ifBlank { null }))
    /** Tells the plugin which conversation is the owner's now: the one session notifications go to. */
    fun ownerSessionRequest(id: String): JSONObject {
        require(isOwnerSession(id)) { "not an owner conversation: $id" }
        return obj("request" to obj("sessionId" to id))
    }
    /** Active notes still in the sessions it lived in before everything moved into the owner's conversation: the patrol
     *  session and each app's standing session (`cowork-app-*`). They are moved there like the notes of a cleared conversation. */
    fun strayNotes(tasks: List<JSONObject>): List<JSONObject> =
        tasks.filter { it.text("sessionId").startsWith("cowork-app-") && it.text("status") == "active" }
    /** The plugin's stored name, or null (none, or a plugin too old to have one). */
    fun storedName(result: Any?): String? = (result as? JSONObject)?.text("name")?.takeIf { it.isNotBlank() }

    // ── Diary ───────────────────────────────────────────────────────────

    /** One day of its diary ([date] is the owner's day, YYYY-MM-DD), written at night by the plugin from what it did. */
    data class DiaryEntry(val date: String, val at: Long, val text: String) {
        val key: String get() = date
        val day: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
    }
    data class DiaryPage(val entries: List<DiaryEntry>, val more: Boolean)

    fun diaryRequest(before: Long? = null, limit: Int = 20) = obj("request" to obj("before" to before, "limit" to limit))
    fun diaryPage(result: Any?): DiaryPage {
        val value = result as? JSONObject ?: return DiaryPage(emptyList(), false)
        val entries = value.objects("entries").mapNotNull { e ->
            val at = e.optLong("at"); val text = e.text("text").trim(); val date = e.text("date")
            if (at <= 0 || text.isEmpty() || runCatching { LocalDate.parse(date) }.isFailure) null else DiaryEntry(date, at, text)
        }
        return DiaryPage(entries, value.optBoolean("more"))
    }
    /** Older pages appended to what is shown, newest first, one entry a day. */
    fun merge(shown: List<DiaryEntry>, page: List<DiaryEntry>): List<DiaryEntry> =
        (shown + page).distinctBy { it.key }.sortedByDescending { it.at }

    // ── Events ──────────────────────────────────────────────────────────

    /**
     * 事件: one turn it ran on its own, started by its own time, a patrol, a notification or a due note (in the owner's conversation,
     * or before 2026-10-26 in 巡视 · 自动运营 or one app's notification session). From the plugin's activity: its last
     * reply, the apps it opened and what it told the owner, never what a notification said. [app] is the package whose
     * notifications started it, if one did.
     */
    data class Event(val at: Long, val sessionId: String, val kind: String, val text: String, val apps: List<String>,
                     val notified: List<String>, val app: String?, val personal: Boolean, val finished: Boolean) {
        val key: String get() = "$sessionId@$at"
    }
    data class EventPage(val entries: List<Event>, val more: Boolean)
    private val EVENT_KINDS = setOf("own", "patrol", "notify", "note")

    fun eventsRequest(before: Long? = null, limit: Int = 30) = obj("request" to obj("before" to before, "limit" to limit))
    fun eventsPage(result: Any?): EventPage {
        val value = result as? JSONObject ?: return EventPage(emptyList(), false)
        fun strings(e: JSONObject, key: String) = e.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.ifEmpty { null } } }.orEmpty()
        val entries = value.objects("entries").mapNotNull { e ->
            val at = e.optLong("at"); val sessionId = e.text("sessionId"); val kind = e.text("kind")
            if (at <= 0 || sessionId.isBlank() || kind !in EVENT_KINDS) return@mapNotNull null
            val app = e.optJSONObject("app")
            Event(at, sessionId, kind, e.text("text").trim(), strings(e, "apps"), strings(e, "notified"),
                app?.text("package")?.ifBlank { null }, app?.text("profile") == "personal", e.text("reason").isBlank())
        }
        return EventPage(entries, value.optBoolean("more"))
    }
    /** Older pages appended to what is shown, newest first. */
    fun mergeEvents(shown: List<Event>, page: List<Event>): List<Event> =
        (shown + page).distinctBy { it.key }.sortedByDescending { it.at }
    /** What started it, and in which app: 自己的时间, 巡视, 巡视 · QQ, 通知 · QQ, 便条 · QQ. [label] names an app from its package. */
    fun eventTitle(event: Event, label: (String) -> String): String {
        val what = when (event.kind) { "own" -> "自己的时间"; "patrol" -> "巡视"; "notify" -> "通知"; else -> "便条" }
        val app = event.app?.let { label(it) + if (event.personal) "（个人）" else "" }
        return if (app == null) what else "$what · $app"
    }

    /** 批一句: the owner's remark on one day of the diary, sent to their conversation with it, naming that day. */
    fun annotation(entry: DiaryEntry, text: String): String {
        require(text.isNotBlank()) { "写点什么再发" }
        val day = entry.day?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日" } ?: entry.date
        return "关于你 $day 的日记：${text.trim()}"
    }

    /** "10 月 25 日 · 周日", or 今天 / 昨天. */
    fun dayHeading(at: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        dayHeading(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), now, zone)
    fun dayHeading(day: LocalDate, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val today = now.atZone(zone).toLocalDate()
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.dayOfWeek.value - 1]
        val date = if (day.year == today.year) "${day.monthValue} 月 ${day.dayOfMonth} 日" else "${day.year} 年 ${day.monthValue} 月 ${day.dayOfMonth} 日"
        return when (ChronoUnit.DAYS.between(day, today)) { 0L -> "今天 · $date"; 1L -> "昨天 · $date"; else -> "$date · $week" }
    }
    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

    // ── Notes the owner writes ──────────────────────────────────────────

    /** The note's name: its first line, short. */
    fun noteTitle(text: String): String {
        val line = text.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val points = line.codePoints().toArray()
        return if (points.size <= 24) line else String(points, 0, 23) + "…"
    }

    /** The plugin's createNote arguments: the owner's words as they are, at the time they picked. */
    fun noteRequest(sessionId: String, text: String, timing: TimingDraft): JSONObject {
        require(text.isNotBlank()) { "先写下要它做的事" }
        timing.problem()?.let { throw IllegalArgumentException(it) }
        return obj("request" to obj("sessionId" to sessionId, "title" to noteTitle(text), "prompt" to text.trim(), "change" to timing.change()))
    }

    /** A one-time note at [time] on [date]. */
    fun once(date: LocalDate, time: String, zone: ZoneId) = TimingDraft("once", date = date.toString(), time = time, zone = zone.id)

    /** The quick choices on the note sheet: label and timing. 今晚 becomes 明晚 once tonight's hour has passed. */
    fun quickTimes(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, TimingDraft>> {
        val tonight = if (now.toLocalTime() < java.time.LocalTime.of(20, 55)) "今晚 21:00" to once(now.toLocalDate(), "21:00", zone)
            else "明晚 21:00" to once(now.toLocalDate().plusDays(1), "21:00", zone)
        val morning = if (now.toLocalTime() < java.time.LocalTime.of(7, 55)) "今早 08:00" to once(now.toLocalDate(), "08:00", zone)
            else "明早 08:00" to once(now.toLocalDate().plusDays(1), "08:00", zone)
        return listOf(tonight, morning)
    }

    /** When a note runs, the way a person says it: 明早 08:00, 每天 08:00, 每周一三五 09:00. */
    fun whenText(timing: TimingDraft, now: LocalDateTime = LocalDateTime.now()): String = when (timing.kind) {
        "once" -> runCatching { dayAndTime(LocalDate.parse(timing.date.trim()), timing.time.trim(), now.toLocalDate()) }.getOrDefault("${timing.date} ${timing.time}")
        "daily" -> "每天 ${timing.time.trim()}"
        "weekly" -> "${ScheduleFormat.weekdays(timing.days.toList())} ${timing.time.trim()}"
        "every" -> ScheduleFormat.interval((timing.minutes.trim().toLongOrNull() ?: 0) * 60) + "一次"
        else -> "按时间表"
    }

    /** 今天 / 明天 / 后天 / M月d日 with the time; 今晚, 明早 and 明晚 for the usual hours. */
    fun dayAndTime(date: LocalDate, time: String, today: LocalDate): String {
        val hour = time.substringBefore(':').toIntOrNull() ?: 12
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> if (hour >= 18) "今晚 $time" else if (hour < 11) "今早 $time" else "今天 $time"
            1L -> if (hour >= 18) "明晚 $time" else if (hour < 11) "明早 $time" else "明天 $time"
            2L -> "后天 $time"
            else -> "${date.monthValue}月${date.dayOfMonth}日 $time"
        }
    }

    /** The next time a stored note is due, the same way ("下次 明早 08:00"). */
    fun nextText(scheduledAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = runCatching { Instant.parse(scheduledAt) }.getOrNull() ?: return ""
        val local = at.atZone(zone)
        return dayAndTime(local.toLocalDate(), local.format(DateTimeFormatter.ofPattern("HH:mm")), now.atZone(zone).toLocalDate())
    }

    /** The receipt's second line after a note is stored: what it heard, in its own words. */
    fun receipt(text: String, timing: TimingDraft, now: LocalDateTime = LocalDateTime.now()): String =
        "${whenText(timing, now)} ${if (timing.kind == "once") "" else "都"}照做：${noteTitle(text)}"

    private val HOURLY = Regex("""0 (\d{1,2})-(\d{1,2})/(\d{1,2}) \* \* \*""")
    private val DAILY = Regex("""(\d{1,2}) (\d{1,2}) \* \* \*""")
    /** How often a stored note runs, with the usual cron rules in words (每天 9–21 点每 3 小时), else the host's own words. */
    fun frequency(entry: JSONObject): String {
        if (entry.text("kind") == "cron") {
            val cron = entry.text("expression").trim().replace(Regex("\\s+"), " ")
            HOURLY.matchEntire(cron)?.let { m -> return "每天 ${m.groupValues[1]}–${m.groupValues[2]} 点每 ${m.groupValues[3]} 小时" }
            DAILY.matchEntire(cron)?.let { m -> return "每天 %02d:%02d".format(m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        }
        return ScheduleFormat.frequency(entry)
    }

    /** The receipt after an edit: 已改（08:00 → 09:00）, or 已改 when only the words changed. */
    fun editReceipt(before: String, after: String): String = if (before.isBlank() || before == after) "已改" else "已改（$before → $after）"
}
