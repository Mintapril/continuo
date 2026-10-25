package dev.dsh.pocket.island

/** Authoritative backend state. */
enum class IslandTaskState(val label: String) {
    RUNNING("正在执行"), WAITING("等待用户"), APPROVAL("需要确认"),
    PAUSING("正在暂停"), STOPPING("正在停止"),
    PAUSED("已暂停"), COMPLETED("本轮结束"), STOPPED("已停止"), ERROR("执行出错");

    val terminal: Boolean get() = this == COMPLETED || this == STOPPED || this == ERROR
}

data class IslandExecution(val instanceId: String, val executionId: String)

data class IslandTaskUpdate(val taskId: String, val revision: Long, val state: IslandTaskState,
    val execution: IslandExecution? = null) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 256)
        require(revision >= 0)
    }
}

enum class IslandEventKind(val label: String) {
    STARTED("任务开始"), PROGRESS("进度更新"), WAITING("等待用户"),
    APPROVAL("需要确认"), PAUSED("任务暂停"), COMPLETED("任务完成"), STOPPED("任务停止"), ERROR("任务出错")
}

/** Text must already be redacted upstream. By default only the fixed kind label is displayed. */
data class IslandEvent(
    val id: String,
    val kind: IslandEventKind,
    val text: String = "",
    val privacySafe: Boolean = false,
)

/**
 * Native key-action feed (no model call): the app the model is using and a few key actions.
 * seq increases only when the broker records a key action or app switch, so the island changes rarely.
 */
data class IslandActivity(val seq: Long = -1, val app: String = "", val text: String = "")

/**
 * Publish MAIN-display observations from a native trusted source, never screenshots or agent steps.
 * null package/game classification means unknown, not safe. Times use elapsed realtime, not wall time.
 * Refresh observations at least every 2 seconds. Service supplies actual lock and permission state.
 */
data class IslandEnvironment(
    val foregroundPackage: String? = null,
    val foregroundIsGame: Boolean? = null,
    val observedAtElapsedMillis: Long = -1,
)

enum class IslandAction { PAUSE, STOP, OPEN_TASK }

data class IslandActionRequest(
    val requestId: String,
    val taskId: String,
    val revision: Long,
    val action: IslandAction,
    val execution: IslandExecution? = null,
)

data class IslandSnapshot(
    val task: IslandTaskUpdate? = null,
    val activity: IslandActivity = IslandActivity(),
    val recentEvents: List<IslandEvent> = emptyList(),
    val environment: IslandEnvironment = IslandEnvironment(),
    val pendingAction: IslandActionRequest? = null,
    val actionError: Boolean = false,
    val backendConnected: Boolean = true,
) {
    val statusLabel: String get() = if (!backendConnected) "连接中断" else task?.state?.label ?: "暂无任务"

    /**
     * The session (broker task id) whose turn holds the AI screen while it runs, pauses or stops, or null. A task left
     * waiting for the owner is not counted: a turn in line is turned away then (COWORK_TASK_BUSY), it does not wait.
     */
    val screenHolder: String? get() = task?.takeIf { backendConnected && it.state in HOLDING }?.taskId

    private companion object {
        val HOLDING = setOf(IslandTaskState.RUNNING, IslandTaskState.PAUSING, IslandTaskState.STOPPING)
    }
}

/** Defense in depth, not a claim to detect all PII. Texts come from fixed native broker strings and app labels. */
object IslandPrivacy {
    private val whitespace = Regex("\\s+")
    private val sensitive = Regex(
        "(?i)(?:https?://|www\\.|content://|file://)\\S+|" +
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
            "(?:bearer\\s+|(?:token|password|secret|api[_-]?key|密码|令牌)\\s*[:=：]\\s*)\\S+|" +
            "(?:\\+?\\d[\\d .()-]{7,}\\d)|[A-Za-z0-9_-]{32,}"
    )

    fun safeLabel(text: String, maxLength: Int = 96): String {
        val plain = text.take(2048).filterNot {
            Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
        }
        return sensitive.replace(plain, "[已隐藏]").replace(whitespace, " ").trim().take(maxLength)
    }

    fun eventLabel(event: IslandEvent): String =
        if (event.privacySafe) safeLabel(event.text).ifBlank { event.kind.label } else event.kind.label
}

/** Which controls a task state allows; used by the status card and JVM tests. */
object IslandPolicy {
    fun allows(task: IslandTaskUpdate?, action: IslandAction): Boolean = when (action) {
        IslandAction.PAUSE -> task?.state == IslandTaskState.RUNNING
        IslandAction.STOP -> task != null && !task.state.terminal
        IslandAction.OPEN_TASK -> task != null
    }
}

/** Pure editing of Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ("pkg/cls:pkg/.cls"), used by IslandLayerKeeper. */
object IslandAccessibilityList {
    private val safe = Regex("[A-Za-z0-9._/:$]+")

    /** The value with [component] appended, keeping every other service; null when it is already listed. */
    fun withComponent(current: String?, component: String): String? {
        val entries = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        if (entries.any { expand(it) == expand(component) }) return null
        return (entries + component).joinToString(":")
    }

    /** Only plain component names are passed to the root shell, inside single quotes. */
    fun shellSafe(value: String): Boolean = safe.matches(value)

    private fun expand(entry: String): String {
        val slash = entry.indexOf('/')
        if (slash <= 0) return entry
        val pkg = entry.substring(0, slash)
        val cls = entry.substring(slash + 1)
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else entry
    }
}
