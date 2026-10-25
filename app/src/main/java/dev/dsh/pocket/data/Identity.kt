package dev.dsh.pocket.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who the AI is to its owner: the name the owner gave it and the avatar they picked, chosen during setup and changeable
 * in settings. Kept on this phone only; the name is also handed to the Cowork plugin so the model knows it.
 */
data class Identity(val name: String = "", val avatar: String = IdentityRules.DEFAULT_AVATAR, val onboarded: Boolean = false) {
    /** The name in every sentence the app shows; "它" until the owner gives one. */
    val shown: String get() = name.ifBlank { "它" }
}

/** Pure rules, tested on the JVM. */
object IdentityRules {
    const val MAX_NAME = 12
    const val DEFAULT_AVATAR = "deepseek"
    /** The stickers shipped in assets/avatars, in picker order. */
    val AVATARS = listOf("deepseek", "claude", "chatgpt", "gemini", "grok", "qwen", "glm", "kimi")
    /** Names offered on the naming step; the owner may type any other. */
    val SUGGESTIONS = listOf("小满", "阿简", "木木", "十一", "豆豆", "阿青", "小鲸", "七七", "小柒", "阿禾", "团子", "知了")
    /** The app itself is called 通奏 / Continuo; the AI must not share its name. */
    private val TAKEN = setOf("通奏", "continuo")

    fun normalize(name: String): String = name.replace(Regex("\\s+"), " ").trim()

    /** Why [name] cannot be used, or null when it can. */
    fun problem(name: String): String? {
        val value = normalize(name)
        return when {
            value.isEmpty() -> "给它起个名字"
            value.codePointCount(0, value.length) > MAX_NAME -> "名字最多 $MAX_NAME 个字"
            value.lowercase() in TAKEN -> "“$value”是这个应用的名字，换一个吧"
            value.any { Character.isISOControl(it) || it in "\"\\<>{}[]`" } -> "名字里不能有符号 \" \\ < > { } [ ] `"
            else -> null
        }
    }

    fun avatar(value: String?): String = value?.takeIf { it in AVATARS } ?: DEFAULT_AVATAR

    /** Three suggestions that change with [round] (the 换一批 button). */
    fun suggestions(round: Int): List<String> = List(3) { SUGGESTIONS[(round * 3 + it).mod(SUGGESTIONS.size)] }
}

/** Process-wide holder: the app, the status card and the call sheet all read the same value. */
object IdentityStore {
    private val state = MutableStateFlow(Identity())
    private var loaded = false

    fun flow(context: Context): StateFlow<Identity> { load(context); return state.asStateFlow() }
    fun current(context: Context): Identity { load(context); return state.value }

    @Synchronized private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        state.value = Identity(prefs.getString("name", "").orEmpty(), IdentityRules.avatar(prefs.getString("avatar", null)),
            prefs.getBoolean("onboarded", false))
    }

    fun setName(context: Context, name: String) {
        val value = IdentityRules.normalize(name)
        require(IdentityRules.problem(value) == null) { IdentityRules.problem(value)!! }
        update(context) { it.copy(name = value) }
    }
    fun setAvatar(context: Context, avatar: String) = update(context) { it.copy(avatar = IdentityRules.avatar(avatar)) }
    fun finishOnboarding(context: Context) = update(context) { it.copy(onboarded = true) }

    private fun update(context: Context, change: (Identity) -> Identity) {
        load(context)
        val next = change(state.value)
        prefs(context).edit().putString("name", next.name).putString("avatar", next.avatar).putBoolean("onboarded", next.onboarded).apply()
        state.value = next
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
}

/**
 * Which DSH session 叫它 talks in now: [Companion.OWNER_SESSION] at first, a fresh one each time the owner clears the
 * conversation (the earlier one is archived). Shared by every screen of the app's process.
 */
object OwnerConversation {
    private val state = MutableStateFlow(Companion.OWNER_SESSION)
    private var loaded = false

    fun flow(context: Context): StateFlow<String> { load(context); return state.asStateFlow() }
    fun current(context: Context): String { load(context); return state.value }

    @Synchronized private fun load(context: Context) {
        if (loaded) return
        loaded = true
        state.value = prefs(context).getString("ownerSession", null)?.takeIf { Companion.isOwnerSession(it) } ?: Companion.OWNER_SESSION
    }

    fun set(context: Context, id: String) {
        require(Companion.isOwnerSession(id)) { "not an owner conversation: $id" }
        load(context)
        check(prefs(context).edit().putString("ownerSession", id).commit()) { "新对话没能记下来" }
        state.value = id
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
}
