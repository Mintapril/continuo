package dev.dsh.pocket.island

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps the inert IslandAccessibilityService enabled once the user switched this on (Cowork settings page).
 *
 * ColorOS force-stops Continuo now and then (exit history: "stop dev.dsh.pocket due to o-stop(40)"), and Android
 * removes a force-stopped package's accessibility services from the enabled list, so the island fell back below the
 * status bar. With the switch on, Continuo uses its existing KernelSU grant (the one that starts the broker) to put
 * the service back: at every process start, and whenever the setting changes while the process is alive. A dead
 * process cannot react; its next start does (the broker restarts the controller service when a task arrives).
 */
object IslandLayerKeeper {
    private const val PREFS = "island_preferences"
    private const val KEY = "keepAccessibilityLayer"
    /** At most one root write per interval, so a tool that keeps rewriting the list cannot make this spin. */
    private const val MIN_INTERVAL_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastWrite = -MIN_INTERVAL_MS
    private var observing = false

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    /** The user's switch. Turning it off only stops the upkeep; it does not turn the service off. */
    suspend fun setEnabled(context: Context, on: Boolean): Result<Unit> {
        prefs(context).edit().putBoolean(KEY, on).apply()
        return if (on) ensure(context.applicationContext) else Result.success(Unit)
    }

    /** Called once per process from IslandRuntime.restore. */
    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        if (!observing) {
            observing = true
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { scope.launch { ensure(app) } }
                },
            )
        }
        scope.launch { ensure(app) }
    }

    /** Puts the island service back when the user opted in and it is missing; a no-op otherwise. */
    suspend fun ensure(context: Context): Result<Unit> = lock.withLock {
        if (!enabled(context)) return@withLock Result.success(Unit)
        val wait = lastWrite + MIN_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        val resolver = context.contentResolver
        val component = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val next = IslandAccessibilityList.withComponent(current, component)
        val switchedOn = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (next == null && switchedOn) return@withLock Result.success(Unit)
        lastWrite = SystemClock.elapsedRealtime()
        runCatching<Unit> {
            val value = next ?: current.orEmpty()
            check(IslandAccessibilityList.shellSafe(value)) { "无障碍服务列表含有无法处理的字符" }
            root("settings put secure enabled_accessibility_services '$value' && settings put secure accessibility_enabled 1")
            android.util.Log.i(TAG, "accessibility layer re-enabled")
        }.onFailure { android.util.Log.w(TAG, "accessibility layer upkeep failed: ${it.message}") }
    }

    private fun root(command: String) {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) error("KernelSU 授权超时")
            val output = process.inputStream.use { String(it.readNBytes(4096), Charsets.UTF_8).trim() }
            check(process.exitValue() == 0) { "root 命令失败${if (output.isNotEmpty()) "：" + output.take(200) else ""}" }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "DshIsland"
}
