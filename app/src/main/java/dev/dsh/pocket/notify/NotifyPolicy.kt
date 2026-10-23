package dev.dsh.pocket.notify

import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Notification triggers have no per-app rules: every app not excluded feeds one batch, the Jev judge (in the Cowork
 * plugin) decides whether the batch wakes the model, and the model decides what, if anything, to do.
 */
object NotifyDefaults {
    const val OWN_PACKAGE = "dev.dsh.pocket"
    /** Each notification restarts this wait; when no app has posted for this long, the batch goes to Jev. */
    const val QUIET_MS = 10_000L
    /** A stream that never pauses still goes out this long after its first notification. */
    const val MAX_WAIT_MS = 120_000L
    private val UNPRINTABLE = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]+")
    /** A one-line app name of at most 60 characters; the package's last segment when there is none. */
    fun label(raw: String?, packageName: String): String =
        raw?.replace(UNPRINTABLE, " ")?.trim()?.take(60)?.trim()?.takeIf { it.isNotEmpty() } ?: packageName.substringAfterLast('.').take(60)
    /** "a, b，c\nd" -> [a, b, c, d] */
    fun words(text: String): List<String> = text.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** The parts of one posted notification the batch looks at. Title and text are read only for a watched package. */
data class Posted(
    val packageName: String, val key: String, val work: Boolean, val channel: String, val category: String?,
    val ongoing: Boolean, val groupSummary: Boolean, val title: String, val text: String, val at: Long,
)

enum class Verdict { ACCEPT, ONGOING, SUMMARY, CATEGORY, EMPTY, REPEAT }

object NotifyFilter {
    /** Notification.CATEGORY_* values that are never a message someone sent. */
    val IGNORED_CATEGORIES = setOf("promo", "recommendation", "status", "progress", "service", "transport", "sys", "navigation", "stopwatch", "location_sharing")

    /** Structural noise only; whether the rest means anything is Jev's call. */
    fun check(n: Posted): Verdict = when {
        n.ongoing -> Verdict.ONGOING
        n.groupSummary -> Verdict.SUMMARY
        n.category in IGNORED_CATEGORIES -> Verdict.CATEGORY
        n.title.isBlank() && n.text.isBlank() -> Verdict.EMPTY
        else -> Verdict.ACCEPT
    }
}

/**
 * Notifications of the Cowork work profile's apps belong to the AI: the broker keeps their channels silent
 * (QuietNotificationGate) and the listener, once it has read one, clears it from the owner's shade. Only the broker's
 * verified profile, only third-party apps other than the profile owner, and never what the system keeps (ongoing,
 * no-clear, foreground service). [system] is null when the notification carries no ApplicationInfo: left alone.
 */
object ProfileShade {
    const val PROFILE_OWNER = "dev.dsh.pocket.isolation"

    fun clears(userId: Int, profileUserId: Int, packageName: String, system: Boolean?, sticky: Boolean): Boolean =
        profileUserId > 0 && userId == profileUserId && system == false && packageName != PROFILE_OWNER && !sticky
}

/**
 * The one pending batch, across apps. It goes out once no app has posted for [NotifyDefaults.QUIET_MS], or
 * [NotifyDefaults.MAX_WAIT_MS] after its first notification. In memory only: a killed process starts fresh.
 */
class NotifyThrottle(private val quietMs: Long = NotifyDefaults.QUIET_MS, private val maxWaitMs: Long = NotifyDefaults.MAX_WAIT_MS) {
    data class Item(val packageName: String, val app: String, val work: Boolean, val sender: String, val text: String, val at: Long)
    data class Batch(val count: Int, val firstAt: Long, val lastAt: Long, val items: List<Item>)
    private var count = 0
    private var firstAt = 0L
    private var lastAt = 0L
    private val items = ArrayDeque<Item>()
    private val seen = LinkedHashMap<String, Pair<Int, Long>>()

    /** ACCEPT when the notification joined the batch, REPEAT when it re-posted content seen moments ago. */
    fun add(n: Posted, app: String): Verdict {
        val fingerprint = (n.title + '\u0000' + n.text).hashCode()
        seen.entries.removeAll { n.at - it.value.second > REPEAT_MS }
        if (seen[n.key]?.first == fingerprint) return Verdict.REPEAT
        seen.remove(n.key); seen[n.key] = fingerprint to n.at
        while (seen.size > 500) seen.remove(seen.keys.first())
        if (count == 0) firstAt = n.at
        count = (count + 1).coerceAtMost(999); lastAt = maxOf(lastAt, n.at)
        items.addLast(Item(n.packageName, app, n.work, clean(n.title, 80), clean(n.text, 400), n.at))
        while (items.size > MAX_ITEMS) items.removeFirst()
        return Verdict.ACCEPT
    }

    /** When the waiting batch may go out, or null when nothing waits. */
    fun fireAt(): Long? = if (count == 0) null else minOf(lastAt + quietMs, firstAt + maxOf(quietMs, maxWaitMs))

    /** Takes the batch if it is due at [now]. */
    fun take(now: Long): Batch? {
        val at = fireAt() ?: return null
        if (at > now) return null
        return Batch(count, firstAt, lastAt, items.toList()).also { forget() }
    }

    fun forget() { count = 0; firstAt = 0; lastAt = 0; items.clear() }
    fun waiting(): Int = count

    companion object {
        const val REPEAT_MS = 600_000L
        const val MAX_ITEMS = 20
        private val CONTROL = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")
        /** The plugin refuses control characters; it also caps lengths. */
        fun clean(text: String, max: Int) = text.replace(CONTROL, " ").trim().take(max)
    }
}

/** The batch request to the Cowork plugin's ingress (cowork-plugin/src/notify.mjs). */
object NotifyWire {
    const val URL = "http://127.0.0.1:3082/v1/notify"
    const val SIGNATURE_HEADER = "x-cowork-notify-signature"
    /** DSH credential the plugin reads the OpenRouter key for Jev (typesafe/jev-1.14) from. */
    const val JEV_CREDENTIAL = "JEV_API_KEY"
    private const val DOMAIN = "cowork-notify-v1\n"

    fun body(batch: NotifyThrottle.Batch, id: String, sentAt: Long): String {
        val items = JSONArray().apply { batch.items.forEach {
            put(JSONObject().put("package", it.packageName).put("app", it.app).put("profile", if (it.work) "work" else "personal")
                .put("sender", it.sender).put("text", it.text).put("at", minOf(it.at, sentAt)))
        } }
        // The plugin refuses times after sentAt; a clock stepped back on the phone must not void the batch.
        val last = minOf(batch.lastAt, sentAt)
        return JSONObject().put("v", 2).put("id", id).put("sentAt", sentAt).put("count", batch.count)
            .put("firstAt", minOf(batch.firstAt, last)).put("lastAt", last).put("items", items).toString()
    }

    /** Hex HMAC-SHA256 over the domain prefix and the exact body bytes; the key is the token's text. */
    fun mac(secret: String, body: ByteArray): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        update(DOMAIN.toByteArray(Charsets.UTF_8)); doFinal(body)
    }.joinToString("") { "%02x".format(it) }

    /** Jev judges each notification: [kept] of [total] passed; [meaningful] is the highest probability. */
    data class Outcome(val queued: Boolean, val judged: Boolean, val meaningful: Double?, val judgeError: String?, val kept: Int = 0, val total: Int = 0)
    fun outcome(text: String): Outcome = JSONObject(text).let {
        Outcome(it.getBoolean("queued"), it.optBoolean("judged"), if (it.has("meaningful")) it.getDouble("meaningful") else null,
            it.optString("judgeError").ifBlank { null }, it.optInt("kept"), it.optInt("total"))
    }

    /** Plugin judge errors in the user's words. */
    fun judgeError(code: String): String = when (code) {
        "no-key" -> "DSH 里没有 Jev 密钥"
        "rate-limited" -> "Jev 调用超过每小时上限"
        "timeout" -> "Jev 超时"
        "unexpected-answer" -> "Jev 返回格式异常"
        else -> if (code.startsWith("http-")) "Jev 返回 HTTP ${code.removePrefix("http-")}" else "Jev 不可用"
    }
}
