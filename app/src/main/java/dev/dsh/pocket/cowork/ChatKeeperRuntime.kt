package dev.dsh.pocket.cowork

import android.content.Context
import dev.dsh.notifykeep.ChatKeepPolicy
import java.io.File
import java.util.concurrent.TimeUnit

/** Project-owned independent root helper: install at connection, not tied to the display/Broker lifetime. */
internal object ChatKeeperRuntime {
    private const val BASE = "/data/local/dsh-native/chat-notify-keeper"
    /** The owner's switch in 设置 → 它的手机. The keeper's own `disabled` flag on the phone is the only record of it. */
    private const val ON = "rm -f $BASE/disabled; /system/bin/sh $BASE/keeper.sh start"
    private const val OFF = "/system/bin/sh $BASE/keeper.sh disable"
    /**
     * The state, the app list on the phone (`LIST`, the keeper's default when there is no file), and which of those apps
     * hold a keeper token right now (`PROTECTED`, the `forcingToImportant=` line of their process records).
     */
    private val STATUS = "if [ ! -r $BASE/keeper.sh ]; then echo NOT_INSTALLED; else " +
        "if [ -e $BASE/disabled ]; then echo DISABLED; else /system/bin/sh $BASE/keeper.sh status; fi; " +
        "if [ -r ${ChatKeepPolicy.LIST_FILE} ]; then L=\$(cat ${ChatKeepPolicy.LIST_FILE}); else L='${ChatKeepPolicy.DEFAULT_PACKAGES.joinToString(" ")}'; fi; " +
        "for P in \$L; do echo LIST \$P; done; " +
        "if [ ! -e $BASE/disabled ]; then for P in \$L; do " +
        "if dumpsys activity -p \$P processes 2>/dev/null | grep -q 'forcingToImportant=ImportanceToken {[^}]*continuo-chat-notify-'; then echo PROTECTED \$P; fi; " +
        "done; fi; fi"

    @Volatile private var installedApk: String? = null
    /** Why the last install failed; shown on the settings page, cleared by the next good install. */
    @Volatile var installError: String? = null; private set

    /** Optional for Cowork: a failure is kept for the settings page and never blocks the connection. */
    @Synchronized fun ensureInstalled(context: Context) {
        val apk = context.applicationInfo.sourceDir
        if (installedApk == apk) return
        try {
            val folder = File(context.filesDir, "chat-notify-keeper").apply { mkdirs() }
            for (name in listOf("install.sh", "keeper.sh", "boot.sh")) {
                val target = File(folder, name)
                context.assets.open("chat-notify-keeper/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val script = File(folder, "install.sh").absolutePath
            // Only the OS-provided app-private path is interpolated, with shell quoting; no model/user arguments.
            val quoted = "'" + script.replace("'", "'\\''") + "'"
            root("/system/bin/sh $quoted", 20, "通知保活安装失败，请检查 KernelSU 授权")
            installedApk = apk; installError = null
        } catch (e: Exception) {
            installError = e.message ?: "通知保活没装上"
        }
    }

    fun status(): ChatKeeperStatus = runCatching { ChatKeeperStatus.parse(root(STATUS, 15, "读不到保活状态，请检查 KernelSU 授权")) }
        .getOrElse { ChatKeeperStatus(ChatKeeperStatus.State.FAILED, error = it.message) }

    /** Off stops the keeper and releases its tokens; on clears the flag and starts it now (the boot monitor would within 30 s). */
    fun setEnabled(on: Boolean) { root(if (on) ON else OFF, 15, if (on) "保活没能启动，请检查 KernelSU 授权" else "保活没能停下，请检查 KernelSU 授权") }

    /**
     * Writes the app list and restarts a running keeper on it (`keeper.sh start` restarts when the list changed). Every name
     * is checked as a package name before it reaches the shell; an empty list is allowed and pins nothing.
     */
    fun setPackages(packages: List<String>) {
        require(packages.size <= ChatKeepPolicy.MAX_PACKAGES) { "最多 ${ChatKeepPolicy.MAX_PACKAGES} 个应用" }
        require(packages.all(ChatKeepPolicy::validName)) { "应用包名不对" }
        val file = ChatKeepPolicy.LIST_FILE
        root("set -e; printf '%s\\n' ${packages.distinct().joinToString(" ")} > $file.new; chmod 600 $file.new; mv -f $file.new $file; " +
            "if [ ! -e $BASE/disabled ]; then /system/bin/sh $BASE/keeper.sh start; fi", 15, "应用名单没能保存，请检查 KernelSU 授权")
    }

    private fun root(command: String, seconds: Long, failure: String): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) error("KernelSU 授权超时，请在 KernelSU 中检查授权")
            val output = process.inputStream.use { it.readNBytes(4097) }
            check(process.exitValue() == 0 && output.size <= 4096) { failure }
            return String(output, Charsets.UTF_8)
        } finally { process.destroy() }
    }
}

/** The keeper as 设置 → 它的手机 shows it, parsed from [ChatKeeperRuntime]'s status command (JVM tests). */
internal data class ChatKeeperStatus(
    val state: State,
    /** The apps on the list, in order; the keeper's default when the phone has no list. */
    val packages: List<String> = emptyList(),
    val protectedPackages: Set<String> = emptySet(),
    val error: String? = null,
) {
    enum class State { RUNNING, STOPPED, BUSY, DISABLED, NOT_INSTALLED, FAILED }

    /** The switch position: on unless the owner turned it off. A stopped keeper is restarted by the boot monitor. */
    val on get() = state == State.RUNNING || state == State.STOPPED || state == State.BUSY
    /** Whether the switch and the app list can be changed: it needs the scripts on the phone and a readable state. */
    val switchable get() = state != State.NOT_INSTALLED && state != State.FAILED && state != State.BUSY

    fun summary(label: (String) -> String): String = when (state) {
        State.RUNNING -> {
            val online = packages.filter { it in protectedPackages }.map(label)
            val offline = packages.filterNot { it in protectedPackages }.map(label)
            when {
                packages.isEmpty() -> "没选应用，什么都不保持"
                offline.isEmpty() -> "${online.joinToString("、")}在线，系统清后台时跳过${if (online.size > 1) "它们" else "它"}"
                online.isEmpty() -> "${offline.joinToString("、")}${if (offline.size > 1) "都" else ""}没在运行，打开一次后才会保持"
                else -> "${online.joinToString("、")}在线；${offline.joinToString("、")}没在运行，打开一次后才会保持"
            }
        }
        State.STOPPED -> "保活没在运行，正常情况下半分钟内会自动重启"
        State.BUSY -> "正在切换…"
        State.DISABLED -> "已关：这些应用被系统清掉后，打开之前收不到它们的消息"
        State.NOT_INSTALLED -> "还没装上：连上它的手机时会自动装"
        State.FAILED -> error ?: "读不到保活状态"
    }

    companion object {
        /** Names for the default apps when the work-profile app list is not loaded (not connected to its phone). */
        val KNOWN = mapOf("com.tencent.mobileqq" to "QQ", "com.tencent.mm" to "微信")

        fun parse(output: String): ChatKeeperStatus {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val first = lines.firstOrNull().orEmpty()
            val state = when {
                first == "NOT_INSTALLED" -> State.NOT_INSTALLED
                first == "DISABLED" -> State.DISABLED
                first.startsWith("RUNNING") -> State.RUNNING
                first == "STOPPED" -> State.STOPPED
                first == "BUSY" -> State.BUSY
                else -> return ChatKeeperStatus(State.FAILED, error = "读不到保活状态")
            }
            fun tagged(tag: String) = lines.filter { it.startsWith("$tag ") }.map { it.removePrefix("$tag ").trim() }
            // The same parsing as the keeper's, so the page shows what the keeper actually pins.
            val packages = ChatKeepPolicy.parsePackages(tagged("LIST").joinToString("\n"))
            val protected = if (state == State.RUNNING) tagged("PROTECTED").filter { it in packages }.toSet() else emptySet()
            return ChatKeeperStatus(state, packages, protected)
        }
    }
}
