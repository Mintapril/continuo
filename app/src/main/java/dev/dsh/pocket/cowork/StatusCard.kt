package dev.dsh.pocket.cowork

import android.content.Context
import dev.dsh.pocket.data.Companion
import dev.dsh.pocket.data.ScheduleFormat
import org.json.JSONObject

/**
 * What the AI is doing, in the owner's words: the title and line of its status card (the ongoing notification), and
 * which face its avatar shows. Pure; built from the broker state sample on every change.
 */
object StatusCard {
    /** The avatar states (the 3x3 sheets minus the blink frame). */
    enum class Mood(val key: String) { IDLE("idle"), WORK("work"), LISTEN("listen"), CALL("call"), ACK("ack"), HAPPY("happy"), SLEEP("sleep"), EAT("eat") }

    data class Card(
        val title: String,
        val text: String,
        /** A turn holds its screen: the card offers 停止, the viewer 暂停 and 停止. */
        val working: Boolean,
        /** The model asked the owner to take its screen and the owner has not yet. */
        val asking: Boolean,
        /** The owner is operating its screen. */
        val ownerControl: Boolean,
        val mood: Mood,
    )

    private val WORKING = setOf("running", "pausing", "stopping", "waiting_user")

    /** How long a task that ended in an error stays on the card. The broker keeps the last task's state until the next
     *  task begins (hours, for a patrol), and Continuo has nothing more to show about it, so the card goes idle after. */
    const val ERROR_SHOWN_MS = 30 * 60_000L

    /** One task that ended in an error (the broker, the task, its execution), or null when the broker reports none. */
    fun errorKey(status: JSONObject): String? {
        if (status.optString("state") != "error" || status.optString("taskId").isBlank()) return null
        return "${status.optString("instanceId")}|${status.optString("taskId")}|${status.optString("executionId")}"
    }

    /** What the task was, from its session: the patrol, something in the owner's conversation (叫它), or anything else. */
    private fun unfinished(taskId: String) = when {
        taskId == ScheduleFormat.PATROL_SESSION -> "巡视"
        Companion.isOwnerSession(taskId) -> "你交代的事"
        else -> "刚才那件事"
    }

    /**
     * [accepted]: the owner already took the screen the model asked for. [hour] and [minute] are the phone's local
     * time, for the idle faces (asleep at night, eating at lunch). [errorAgeMs]: how long ago this phone first saw the
     * error the broker reports ([ErrorClock]).
     */
    fun of(name: String, link: CoworkLinkState, status: JSONObject, accepted: Boolean, hour: Int, minute: Int = 0, errorAgeMs: Long = 0): Card {
        val who = name.ifBlank { "它" }
        if (link != CoworkLinkState.CONNECTED) return when {
            link.connecting -> Card("$who · 正在醒来", "正在连接它的手机", false, false, false, Mood.SLEEP)
            link == CoworkLinkState.DISCONNECTED -> Card("$who · 没在线", "打开 Continuo 就会连上", false, false, false, Mood.SLEEP)
            else -> Card("$who · 联系不上", "它的手机连接中断，正在恢复", false, false, false, Mood.SLEEP)
        }
        val state = status.optString("state")
        val hasTask = status.optString("taskId").isNotBlank()
        val working = hasTask && state in WORKING
        val control = status.optBoolean("userControl") && status.optInt("displayId") > 0
        val asking = control && status.optString("interventionBy") == "model" && !accepted
        val activity = status.optJSONObject("activity")
        val app = activity?.optString("app").orEmpty().trim()
        val action = activity?.optString("text").orEmpty().trim()
        if (asking) {
            val reason = status.optString("interventionReason").trim()
            return Card("${who}找你", if (reason.isEmpty()) "需要你接手它的手机" else "需要你接手：$reason", working, true, true, Mood.CALL)
        }
        if (control) return Card("你在用${who}的手机", "用完点“交还”，它接着做", working, false, true, Mood.LISTEN)
        return when {
            hasTask && state == "running" -> Card(if (app.isEmpty()) "$who · 在忙" else "$who · 在 $app", action.ifEmpty { "在干活" }, true, false, false, Mood.WORK)
            hasTask && (state == "pausing" || state == "stopping") -> Card("$who · 正在停下", "收拾手上的事", true, false, false, Mood.WORK)
            hasTask && state == "waiting_user" -> Card("$who · 在等你", "它在等你回话", true, false, false, Mood.LISTEN)
            hasTask && state == "error" && errorAgeMs < ERROR_SHOWN_MS -> Card("$who · ${unfinished(status.optString("taskId"))}没做完",
                if (app.isEmpty()) "中途出错，停下了" else "在 $app 里出错，停下了", false, false, false, Mood.CALL)
            else -> Card("$who · ${if (hasTask && state == "paused") "停下了" else "空闲"}",
                if (action.isEmpty()) "叫它做事，或者写张便条" else "刚才：$action", false, false, false, idleMood(hour, minute))
        }
    }

    /** Asleep late at night, eating around lunch, otherwise idle. */
    fun idleMood(hour: Int, minute: Int = 0): Mood = when {
        hour >= 23 || hour < 7 -> Mood.SLEEP
        (hour == 11 && minute >= 45) || (hour == 12 && minute < 30) -> Mood.EAT
        else -> Mood.IDLE
    }
}

/**
 * When this phone first saw the error the broker reports (the broker keeps no time for it), remembered across restarts
 * so the card does not show the same error for another [StatusCard.ERROR_SHOWN_MS] each time Continuo starts.
 */
object ErrorClock {
    private var key: String? = null
    private var seen = 0L

    @Synchronized fun ageMs(context: Context, status: JSONObject, now: Long = System.currentTimeMillis()): Long {
        val error = StatusCard.errorKey(status) ?: return 0
        if (error != key) {
            val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            seen = if (prefs.getString("error_key", null) == error) prefs.getLong("error_seen", now)
                else now.also { prefs.edit().putString("error_key", error).putLong("error_seen", it).apply() }
            key = error
        }
        return now - seen
    }
}
