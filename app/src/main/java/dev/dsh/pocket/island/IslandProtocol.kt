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

data class IslandPreferences(
    val enabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val hideInGames: Boolean = true,
    val excludedPackages: Set<String> = emptySet(),
)

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
    val preferences: IslandPreferences = IslandPreferences(),
    val environment: IslandEnvironment = IslandEnvironment(),
    val pendingAction: IslandActionRequest? = null,
    val actionError: Boolean = false,
    val backendConnected: Boolean = true,
) {
    val statusLabel: String get() = if (!backendConnected) "连接中断" else task?.state?.label ?: "暂无任务"
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

enum class IslandSurface { HIDDEN, NOTIFICATION, OVERLAY }

enum class IslandSuppression {
    DISABLED, NO_TASK, USER_NOTIFICATION_ONLY, PERMISSION_MISSING, DEVICE_LOCKED,
    FOREGROUND_UNKNOWN, FOREGROUND_STALE, EXCLUDED_APP, GAME, NONE
}

data class IslandVisibility(val surface: IslandSurface, val reason: IslandSuppression)

/** Pure policy used by both service and JVM tests. Unknown/stale foreground is fail-closed. */
object IslandPolicy {
    const val FOREGROUND_TTL_MILLIS = 5_000L

    fun visibility(
        snapshot: IslandSnapshot,
        nowElapsedMillis: Long,
        overlayPermissionGranted: Boolean,
        deviceLocked: Boolean,
    ): IslandVisibility {
        fun hidden(reason: IslandSuppression) = IslandVisibility(IslandSurface.HIDDEN, reason)
        fun fallback(reason: IslandSuppression) = IslandVisibility(IslandSurface.NOTIFICATION, reason)
        val prefs = snapshot.preferences
        if (!prefs.enabled) return hidden(IslandSuppression.DISABLED)
        if (snapshot.task == null) return fallback(IslandSuppression.NO_TASK)
        if (!prefs.overlayEnabled) return fallback(IslandSuppression.USER_NOTIFICATION_ONLY)
        if (!overlayPermissionGranted) return fallback(IslandSuppression.PERMISSION_MISSING)
        if (deviceLocked) return fallback(IslandSuppression.DEVICE_LOCKED)
        val env = snapshot.environment
        if (env.foregroundPackage.isNullOrBlank()) return fallback(IslandSuppression.FOREGROUND_UNKNOWN)
        val age = nowElapsedMillis - env.observedAtElapsedMillis
        if (env.observedAtElapsedMillis < 0 || age < 0 || age > FOREGROUND_TTL_MILLIS) {
            return fallback(IslandSuppression.FOREGROUND_STALE)
        }
        if (env.foregroundPackage in prefs.excludedPackages) return fallback(IslandSuppression.EXCLUDED_APP)
        if (prefs.hideInGames && env.foregroundIsGame == null) return fallback(IslandSuppression.FOREGROUND_UNKNOWN)
        if (prefs.hideInGames && env.foregroundIsGame == true) return fallback(IslandSuppression.GAME)
        return IslandVisibility(IslandSurface.OVERLAY, IslandSuppression.NONE)
    }

    fun allows(task: IslandTaskUpdate?, action: IslandAction): Boolean = when (action) {
        IslandAction.PAUSE -> task?.state == IslandTaskState.RUNNING
        IslandAction.STOP -> task != null && !task.state.terminal
        IslandAction.OPEN_TASK -> task != null
    }
}
