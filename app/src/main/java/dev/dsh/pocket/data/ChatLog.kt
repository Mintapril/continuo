package dev.dsh.pocket.data

/**
 * The home page's 聊天记录: the owner's conversation with it (叫它) as a chat, from the session's transcript rows.
 * Everything it does lives in that session (patrols, due notes, every app's notifications), but the chat shows only
 * what the owner said, the notes it wrote down then and its last reply; the rest is 事件. Its thinking, tool calls and
 * intermediate replies stay out (设置 → 开发者 shows them). When the owner is the last to have spoken, the turn still
 * running ends with a line for what it is doing now (see Progress). What it said to the owner on its own (cowork_reach_owner:
 * in Continuo, or in one of the owner's apps) shows wherever it happened, its own runs included. Pure, so it is tested on
 * the JVM.
 */
object ChatLog {
    enum class Kind { OWNER, NOTE, REPLY, REACH, THINKING }
    data class Line(val key: String, val kind: Kind, val text: String, val label: String = "")

    /** A note it wrote down (schedule_create), in the owner's words, and when it runs; null for any other row. */
    data class Note(val words: String, val timing: String)
    fun noteOf(row: TranscriptRow): Note? {
        if (row.role != "tool" || toolName(row) != "schedule_create" || row.error || row.streaming) return null
        val args = toolArguments(row) ?: return null
        val words = args.text("prompt").ifBlank { args.text("title") }.removePrefix("[便条]").trim()
        val timing = when {
            args.has("daily") -> "每天 ${ScheduleFormat.shortTime(args.optJSONObject("daily")?.text("time").orEmpty())}"
            args.has("weekly") -> "每周"
            args.has("cron") -> "按时间表"
            args.has("every_seconds") -> ScheduleFormat.interval(args.optLong("every_seconds")) + "一次"
            else -> "到点"
        }
        return if (words.isBlank()) null else Note(words, timing)
    }

    /** It reached the owner on its own (cowork_reach_owner): what it said and where; null for any other row. */
    data class Reach(val text: String, val channel: String) {
        /** 来找你 in Continuo, 在 QQ 上找你 in one of the owner's apps. */
        val label: String get() = if (channel == CONTINUO) "来找你" else "在 ${Temperament.appLabel(channel)} 上找你"
    }
    const val REACH_TOOL = "cowork_reach_owner"
    const val CONTINUO = "continuo"
    fun reachOf(row: TranscriptRow): Reach? {
        if (row.role != "tool" || toolName(row) != REACH_TOOL || row.error || row.streaming) return null
        val args = toolArguments(row) ?: return null
        val text = args.text("text").trim()
        return if (text.isBlank()) null else Reach(text, args.text("channel").ifBlank { CONTINUO })
    }
    /** Its latest message to the owner on its own, if it came after the owner last spoke (the 叫它 sheet shows it). */
    fun reachAfterOwner(rows: List<TranscriptRow>): Reach? {
        val owner = rows.indexOfLast(::ownerWords)
        val at = rows.indexOfLast { reachOf(it) != null }
        return if (at > owner) reachOf(rows[at]) else null
    }

    /** The owner's own words: a user message they sent (a due note or patrol arrives as a titled user row). */
    fun ownerWords(row: TranscriptRow) = row.role == "user" && row.title.isBlank() && row.source in setOf("", "user")
    /** What starts a part of the conversation: any user-role row, and a notification trigger (a notice row). */
    fun opens(row: TranscriptRow) = row.role == "user" || row.source == "webhook"
    /** Whether the owner was the last to come in, so what runs now is about them (not a patrol or notifications). */
    fun ownerSpeaking(rows: List<TranscriptRow>) = rows.lastOrNull(::opens)?.let(::ownerWords) == true
    /** The rows of the owner's last part: what they said last and what followed until something else came in. */
    fun lastOwnerPart(rows: List<TranscriptRow>): List<TranscriptRow> {
        val at = rows.indexOfLast(::ownerWords)
        if (at < 0) return emptyList()
        return listOf(rows[at]) + rows.drop(at + 1).takeWhile { !opens(it) }
    }

    fun of(rows: List<TranscriptRow>, running: Boolean): List<Line> {
        // A part starts where someone or something came in: the owner's words, a due note or patrol, notifications. A
        // notification joining a run, or the owner speaking in the middle of one, starts a part of its own.
        val parts = ArrayList<MutableList<TranscriptRow>>()
        for (row in rows) {
            if (opens(row) || parts.isEmpty()) parts += mutableListOf(row) else parts.last() += row
        }
        val out = ArrayList<Line>()
        parts.forEachIndexed { index, part ->
            val head = part.first().takeIf(::ownerWords)
            // Its own runs stay out, except what it said to the owner there.
            if (head == null) { part.forEach { row -> reachOf(row)?.let { out += Line(row.key, Kind.REACH, it.text, it.label) } }; return@forEachIndexed }
            if (head.text.isNotBlank()) out += Line(head.key, Kind.OWNER, head.text.trim())
            part.forEach { row ->
                noteOf(row)?.let { out += Line(row.key, Kind.NOTE, it.words, it.timing) }
                reachOf(row)?.let { out += Line(row.key, Kind.REACH, it.text, it.label) }
            }
            val reply = part.lastOrNull { it.role == "assistant" && !it.error && !it.streaming && it.text.isNotBlank() }
            if (reply != null) out += Line(reply.key, Kind.REPLY, reply.text.trim())
            // The turn still running says what it is doing, after anything it said so far.
            if (running && index == parts.lastIndex) out += Line(head.key + ":thinking", Kind.THINKING, "")
        }
        return out
    }
}
