package dev.dsh.pocket.data

/**
 * The home page's 聊天记录: the owner's conversation with it (叫它) as a chat, from the session's transcript rows. Each
 * turn shows what started it (the owner's words, or a small label for a due note or patrol), the notes it wrote down
 * and its last reply. Its thinking, tool calls and intermediate replies stay out (设置 → 开发者 shows them). The turn
 * still running ends with a line for what it is doing now (see Progress). Pure, so it is tested on the JVM.
 */
object ChatLog {
    enum class Kind { OWNER, LABEL, NOTE, REPLY, THINKING }
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

    fun of(rows: List<TranscriptRow>, running: Boolean): List<Line> {
        // A turn starts at a user-role row: the owner's words (no title), or a due note or patrol (titled).
        val turns = ArrayList<MutableList<TranscriptRow>>()
        for (row in rows) {
            if (row.role == "user" || turns.isEmpty()) turns += mutableListOf(row) else turns.last() += row
        }
        val out = ArrayList<Line>()
        turns.forEachIndexed { index, turn ->
            val head = turn.first().takeIf { it.role == "user" }
            if (head != null && head.text.isNotBlank()) {
                if (head.title.isBlank()) out += Line(head.key, Kind.OWNER, head.text.trim())
                else out += Line(head.key, Kind.LABEL, clip(head.text.trim().lineSequence().first().trim(), 60), head.title)
            }
            turn.forEach { row -> noteOf(row)?.let { out += Line(row.key, Kind.NOTE, it.words, it.timing) } }
            val reply = turn.lastOrNull { it.role == "assistant" && !it.error && !it.streaming && it.text.isNotBlank() }
            if (reply != null) out += Line(reply.key, Kind.REPLY, reply.text.trim())
            // The turn still running says what it is doing, after anything it said so far.
            if (running && index == turns.lastIndex && head != null) out += Line(head.key + ":thinking", Kind.THINKING, "")
        }
        return out
    }

    private fun clip(text: String, max: Int): String {
        val points = text.codePoints().toArray()
        return if (points.size <= max) text else String(points, 0, max - 1) + "…"
    }
}
