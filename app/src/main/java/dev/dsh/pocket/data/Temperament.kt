package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Its character (eight Jungian function values the owner sets), its energy and mood, and how it may reach the owner
 * (docs/personality-design.md). The Cowork plugin keeps all of it and computes the rest (type, extraversion, energy);
 * Continuo reads and sets it over DSH's RPC, namespace `coworkUnattended` (cowork-plugin/src/unattended.mjs). Pure, so
 * it is tested on the JVM.
 */
object Temperament {
    const val READ = "coworkUnattended/character"
    const val WRITE = "coworkUnattended/setCharacter"
    const val PSYCHE = "coworkUnattended/psyche"
    const val OUTREACH = "coworkUnattended/outreach"
    const val SET_OUTREACH = "coworkUnattended/setOutreach"
    const val SET_DND = "coworkUnattended/setDnd"
    const val OWNER_SEEN = "coworkUnattended/ownerSeen"

    val FUNCTIONS = listOf("Se", "Si", "Ne", "Ni", "Te", "Ti", "Fe", "Fi")
    /**
     * Functions that exclude each other (the plugin's psyche.mjs OPPOSITION): the same function in the other attitude (Fi
     * and Fe) and the opposite function in the other attitude (Fi and Te, Ne and Si). Two of them together never exceed
     * [OPPOSITE_CAP], so they cannot both be high; the plugin refuses a character that breaks it.
     */
    const val OPPOSITE_CAP = 120
    private fun flip(attitude: Char) = if (attitude == 'e') "i" else "e"
    /** The two functions [f] excludes: its other attitude, then the opposite function of the other attitude. */
    fun opposites(f: String): List<String> =
        listOf("${f[0]}${flip(f[1])}", "${mapOf('S' to 'N', 'N' to 'S', 'T' to 'F', 'F' to 'T').getValue(f[0])}${flip(f[1])}")
    /** [traits] with [f] set to [value] (0..100) and the two functions it excludes lowered as far as needed (a slider moved). */
    fun adjust(traits: Map<String, Int>, f: String, value: Int): Map<String, Int> {
        val v = value.coerceIn(0, 100)
        val out = traits.toMutableMap().apply { put(f, v) }
        for (g in opposites(f)) out[g] = minOf(out[g] ?: 0, OPPOSITE_CAP - v)
        return out
    }
    fun balanced(traits: Map<String, Int>): Boolean =
        FUNCTIONS.all { a -> opposites(a).all { b -> (traits[a] ?: 0) + (traits[b] ?: 0) <= OPPOSITE_CAP } }
    val FUNCTION_NAMES = mapOf("Se" to "外倾感觉", "Si" to "内倾感觉", "Ne" to "外倾直觉", "Ni" to "内倾直觉",
        "Te" to "外倾思维", "Ti" to "内倾思维", "Fe" to "外倾情感", "Fi" to "内倾情感")
    /** What each function looks like on its phone, one line, for the slider page. */
    val FUNCTION_HINTS = mapOf(
        "Se" to "被眼前的热闹吸引，看到就点，回得快",
        "Si" to "念旧有习惯，先看常看的人，爱记细节",
        "Ne" to "好奇跳跃，爱试新东西，爱联想开脑洞",
        "Ni" to "一条线追到底，话少但有结论",
        "Te" to "讲效率和结果，给有用的信息",
        "Ti" to "较真求准，爱问为什么",
        "Fe" to "会看气氛，接梗捧场，照顾人",
        "Fi" to "只为真心在意的开口，有自己的好恶")
    /** The 16 types in four temperaments, in picker order. */
    val GROUPS = listOf(
        "分析家（NT）" to listOf("INTJ", "INTP", "ENTJ", "ENTP"),
        "外交家（NF）" to listOf("INFJ", "INFP", "ENFJ", "ENFP"),
        "守护者（SJ）" to listOf("ISTJ", "ISFJ", "ESTJ", "ESFJ"),
        "探险家（SP）" to listOf("ISTP", "ISFP", "ESTP", "ESFP"))
    val TYPES = GROUPS.flatMap { it.second }
    val ACTIVITIES = listOf("browse", "read", "talk", "think")
    val ACTIVITY_NAMES = mapOf("browse" to "刷和逛", "read" to "细读", "talk" to "和人说话", "think" to "想事情、记东西")

    data class Derived(val type: String, val stack: List<String>, val e: Double, val extraverted: Int,
                       val effort: Map<String, Double>, val share: Map<String, Int>) {
        /** Activities from the easiest (lowest effort) to the hardest. */
        val easiest: List<String> get() = ACTIVITIES.sortedBy { effort[it] ?: 1.0 }
    }
    data class Profile(val traits: Map<String, Int>?, val derived: Derived?)

    private fun numbers(o: JSONObject?, keys: List<String>): Map<String, Double>? =
        o?.let { v -> keys.associateWith { k -> if (v.has(k) && !v.isNull(k)) v.optDouble(k) else Double.NaN }.takeIf { m -> m.values.none { it.isNaN() } } }
    /** The `character` / `setCharacter` result. */
    fun profile(result: Any?): Profile {
        val value = result as? JSONObject ?: return Profile(null, null)
        val traits = value.optJSONObject("traits")?.let { t ->
            FUNCTIONS.associateWith { f -> if (t.has(f) && !t.isNull(f)) t.optInt(f, -1) else -1 }.takeIf { m -> m.values.all { it in 0..100 } }
        }
        val d = value.optJSONObject("derived")
        val derived = d?.let {
            val type = it.text("type")
            val stack = it.optJSONArray("stack")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }.orEmpty()
            val effort = numbers(it.optJSONObject("effort"), ACTIVITIES) ?: return@let null
            val share = numbers(it.optJSONObject("share"), ACTIVITIES)?.mapValues { (_, v) -> v.toInt() } ?: return@let null
            if (type !in TYPES) null else Derived(type, stack, it.optDouble("e", 0.0), it.optInt("extraverted", 50), effort, share)
        }
        return Profile(traits, if (traits == null) null else derived)
    }
    fun typeRequest(type: String): JSONObject {
        require(type in TYPES) { "没有这个类型：$type" }
        return obj("request" to obj("type" to type))
    }
    fun traitsRequest(traits: Map<String, Int>): JSONObject {
        require(traits.keys == FUNCTIONS.toSet() && traits.values.all { it in 0..100 }) { "八个值都要在 0 到 100 之间" }
        require(balanced(traits)) { "相斥的两个功能加起来最多 $OPPOSITE_CAP" }
        return obj("request" to obj("traits" to JSONObject().apply { FUNCTIONS.forEach { put(it, traits.getValue(it)) } }))
    }
    fun clearRequest(): JSONObject = obj("request" to JSONObject().put("traits", JSONObject.NULL))
    /** How the 性格 row reads: INFP · 外倾 30%, or 没定. */
    fun summary(profile: Profile): String = profile.derived?.let { "${it.type} · 外倾 ${it.extraverted}%" } ?: "没定"
    /** What comes easy and what tires it, from the plugin's effort. */
    fun easeText(derived: Derived): String {
        val order = derived.easiest
        return "最轻松：${ACTIVITY_NAMES[order.first()]}；最费劲：${ACTIVITY_NAMES[order.last()]}"
    }

    data class Psyche(val active: Boolean, val energy: Double, val mood: Double, val energyBand: String, val moodBand: String,
                      val lastOwnerAt: Long, val running: Boolean)
    fun psyche(result: Any?): Psyche? {
        val v = result as? JSONObject ?: return null
        return Psyche(v.optBoolean("active"), v.optDouble("energy", 0.0).coerceIn(0.0, 100.0), v.optDouble("mood", 0.0).coerceIn(0.0, 100.0),
            v.text("energyBand"), v.text("moodBand"), v.optLong("lastOwnerAt"), v.optBoolean("running"))
    }
    fun energyWords(band: String) = when (band) { "full" -> "精神不错"; "low" -> "有点累"; "tired" -> "累了"; "spent" -> "累坏了"; else -> "" }
    fun moodWords(band: String) = when (band) { "high" -> "心情不错"; "normal" -> "还行"; "low" -> "有点低落"; "grip" -> "很糟"; else -> "" }

    /** One of the owner's own accounts in its apps: [remark] is how the owner shows in its app, [shownAs] how it shows in the owner's. */
    data class Contact(val packageName: String, val remark: String, val shownAs: String)
    data class Outreach(val contacts: List<Contact> = emptyList(), val level: String = "normal", val quietFrom: String = "23:00",
                        val quietTo: String = "08:00", val dndUntil: Long = 0, val preferences: String = "")
    /** How often it may open a topic with the owner, and how many a day. */
    val LEVELS = listOf("off" to "关", "few" to "少", "normal" to "适中", "many" to "多")
    val LEVEL_TOPICS = mapOf("off" to 0, "few" to 1, "normal" to 3, "many" to 5)
    fun levelText(level: String): String {
        val name = LEVELS.firstOrNull { it.first == level }?.second ?: level
        val n = LEVEL_TOPICS[level] ?: 0
        return if (n == 0) name else "$name · 每天最多 $n 件事"
    }
    const val CONTACTS_MAX = 8
    const val NAME_MAX = 40
    const val PREFERENCES_MAX = 600

    fun outreach(result: Any?): Outreach {
        val v = result as? JSONObject ?: return Outreach()
        val contacts = v.objects("contacts").mapNotNull { c ->
            val pkg = c.text("package"); val remark = c.text("remark"); val shown = c.text("shownAs")
            if (pkg.isBlank() || remark.isBlank() || shown.isBlank()) null else Contact(pkg, remark, shown)
        }
        val level = v.text("level").takeIf { l -> LEVELS.any { it.first == l } } ?: "normal"
        return Outreach(contacts, level, v.text("quietFrom").ifBlank { "23:00" }, v.text("quietTo").ifBlank { "08:00" },
            v.optLong("dndUntil").coerceAtLeast(0), v.text("preferences"))
    }
    private fun contactsJson(list: List<Contact>) = JSONArray().apply {
        list.forEach { put(obj("package" to it.packageName, "remark" to it.remark, "shownAs" to it.shownAs)) }
    }
    /** Why a contact cannot be stored, or null. */
    fun contactProblem(remark: String, shownAs: String): String? = when {
        remark.isBlank() -> "填上它那边给你的备注"
        shownAs.isBlank() -> "填上它在你那边显示的名字"
        remark.trim().codePointCount(0, remark.trim().length) > NAME_MAX || shownAs.trim().codePointCount(0, shownAs.trim().length) > NAME_MAX -> "名字最多 $NAME_MAX 个字"
        else -> null
    }
    fun contactsRequest(list: List<Contact>): JSONObject {
        require(list.size <= CONTACTS_MAX) { "最多 $CONTACTS_MAX 个" }
        require(list.map { it.packageName }.distinct().size == list.size) { "一个应用只填一个" }
        list.forEach { c -> contactProblem(c.remark, c.shownAs)?.let { throw IllegalArgumentException(it) } }
        return obj("request" to obj("contacts" to contactsJson(list.map { it.copy(remark = it.remark.trim(), shownAs = it.shownAs.trim()) })))
    }
    fun levelRequest(level: String): JSONObject {
        require(LEVELS.any { it.first == level }) { "没有这一档：$level" }
        return obj("request" to obj("level" to level))
    }
    private val CLOCK = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    /** "23:00-08:00" (also with ～, –, 到 or spaces) as two HH:mm, or null. */
    fun quietOf(text: String): Pair<String, String>? {
        val parts = text.trim().split(Regex("\\s*(?:-|–|—|~|～|到|至)\\s*"))
        if (parts.size != 2) return null
        fun clock(s: String): String? {
            val m = Regex("^(\\d{1,2})[:：](\\d{2})$").find(s.trim()) ?: return null
            val value = "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
            return value.takeIf { CLOCK.matches(it) }
        }
        val from = clock(parts[0]) ?: return null
        val to = clock(parts[1]) ?: return null
        return if (from == to) null else from to to
    }
    fun quietRequest(from: String, to: String): JSONObject {
        require(CLOCK.matches(from) && CLOCK.matches(to) && from != to) { "时间写成 23:00-08:00 这样" }
        return obj("request" to obj("quietFrom" to from, "quietTo" to to))
    }
    fun preferencesRequest(text: String): JSONObject {
        val value = text.replace("\r\n", "\n").trim()
        require(value.codePointCount(0, value.length) <= PREFERENCES_MAX) { "最多 $PREFERENCES_MAX 个字" }
        return obj("request" to obj("preferences" to value))
    }
    fun dndRequest(until: Long): JSONObject = obj("request" to obj("until" to until.coerceAtLeast(0)))
    fun seenRequest(at: Long): JSONObject = obj("request" to obj("at" to at))
    /** 别找我 choices: one hour from [now], or until 08:00 the next morning (today's if it is still before 8). */
    fun inAnHour(now: Long): Long = now + 3_600_000
    fun nextMorning(now: Long, zone: ZoneId): Long {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val today = at.toLocalDate().atTime(LocalTime.of(8, 0)).atZone(zone)
        return (if (at.isBefore(today)) today else today.plusDays(1)).toInstant().toEpochMilli()
    }
    /** How the 别找我 row reads now. */
    fun dndText(until: Long, now: Long, zone: ZoneId): String {
        if (until <= now) return "没开"
        val at: ZonedDateTime = Instant.ofEpochMilli(until).atZone(zone)
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (sameDay) "" else "明天 ") + at.format(DateTimeFormatter.ofPattern("HH:mm")) + " 前别找我"
    }

    /** The default words of the patrol notes Continuo wrote; they say nothing about what the owner wants. */
    private val PATROL_DEFAULTS = listOf(true, false).map { ScheduleFormat.patrolTask(it).removePrefix(ScheduleFormat.PATROL_MARKER).trim() }
    /** What the owner wrote in a patrol note ([巡视] …), without the marker and the default words; null when it is no patrol. */
    fun patrolDirection(prompt: String): String? {
        val text = prompt.trimStart()
        if (!text.startsWith(ScheduleFormat.PATROL_MARKER)) return null
        return PATROL_DEFAULTS.fold(text.removePrefix(ScheduleFormat.PATROL_MARKER)) { t, d -> t.replace(d, "") }.trim()
    }
    /** [current] with each of [additions] not in it yet on a line of its own, at most PREFERENCES_MAX characters. */
    fun mergePreferences(current: String, additions: List<String>): String {
        var out = current.trim()
        for (a in additions.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.contains(a)) continue
            val next = if (out.isEmpty()) a else "$out\n$a"
            if (next.codePointCount(0, next.length) > PREFERENCES_MAX) break
            out = next
        }
        return out
    }
    /** A known chat app's name for a line in the chat, else its package. */
    fun appLabel(packageName: String): String = when (packageName) {
        "com.tencent.mobileqq" -> "QQ"
        "com.tencent.mm" -> "微信"
        "com.tencent.tim" -> "TIM"
        "tv.danmaku.bili" -> "B站"
        "com.xingin.xhs" -> "小红书"
        "com.ss.android.ugc.aweme" -> "抖音"
        "com.sina.weibo" -> "微博"
        else -> packageName
    }
}
