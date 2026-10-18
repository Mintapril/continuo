package dev.dsh.pocket.island

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Own-app callbacks only; deliberately no exported receiver, arbitrary intent or network destination. */
object IslandRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = IslandStore(scope)
    @Volatile private var actionHandler: (suspend (IslandActionRequest) -> Unit)? = null
    private val mutableAvailability = MutableStateFlow(IslandAvailability())
    val availability: StateFlow<IslandAvailability> = mutableAvailability.asStateFlow()

    private var restored = false
    /** Preferences survive process death (ColorOS kills Pocket); first run defaults to island on with overlay. */
    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        val prefs = context.applicationContext.getSharedPreferences("island_preferences", Context.MODE_PRIVATE)
        val known = prefs.contains("enabled")
        store.configure(IslandPreferences(
            enabled = prefs.getBoolean("enabled", true), overlayEnabled = prefs.getBoolean("overlayEnabled", true),
            hideInGames = prefs.getBoolean("hideInGames", true),
            excludedPackages = prefs.getStringSet("excluded", emptySet()).orEmpty().toSet(),
        ))
        if (!known) save(prefs, store.snapshots.value.preferences)
        scope.launch {
            store.snapshots.collect { save(prefs, it.preferences) }
        }
    }
    private fun save(prefs: android.content.SharedPreferences, p: IslandPreferences) {
        prefs.edit().putBoolean("enabled", p.enabled).putBoolean("overlayEnabled", p.overlayEnabled)
            .putBoolean("hideInGames", p.hideInGames).putStringSet("excluded", p.excludedPackages).apply()
    }

    /** Install an application-lifetime handler; do not capture an Activity. Clear on logout. */
    fun setActionHandler(handler: (suspend (IslandActionRequest) -> Unit)?) { actionHandler = handler }

    internal fun dispatch(taskId: String, revision: Long, action: IslandAction, execution: IslandExecution?): Boolean =
        store.requestAction(taskId, revision, action, actionHandler, execution)

    internal fun report(value: IslandAvailability) { mutableAvailability.value = value }

    /** Call only from a visible Activity following the user's explicit enable action. Never from boot. */
    fun startUserInitiated(context: Context): IslandStartResult {
        val snapshot = store.snapshots.value
        if (!snapshot.preferences.enabled) return IslandStartResult.DISABLED
        // Explicit visible-Activity pre-arming must precede the protected main-screen baseline.
        // Idle service stays notification-only; polling can never start this service.
        return try {
            context.startForegroundService(Intent(context, IslandOverlayService::class.java))
            IslandStartResult.REQUESTED
        } catch (_: IllegalStateException) {
            report(IslandAvailability(failure = IslandFailure.START_RESTRICTED))
            IslandStartResult.RESTRICTED
        } catch (_: SecurityException) {
            report(IslandAvailability(failure = IslandFailure.START_RESTRICTED))
            IslandStartResult.RESTRICTED
        }
    }

    /** User explicitly dismisses the island; this does NOT stop or approve the backend task. */
    fun disable(context: Context) {
        store.configure(store.snapshots.value.preferences.copy(enabled = false, overlayEnabled = false))
        context.stopService(Intent(context, IslandOverlayService::class.java))
    }

    /** Return an ordinary Settings intent; callers show explanation before launching it. No grant bypass. */
    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"),
    )
}

enum class IslandStartResult { REQUESTED, DISABLED, NO_TASK, RESTRICTED }
enum class IslandFailure { NONE, START_RESTRICTED, OVERLAY_UNAVAILABLE, NOTIFICATIONS_UNAVAILABLE }

data class IslandAvailability(
    val surface: IslandSurface = IslandSurface.HIDDEN,
    val reason: IslandSuppression = IslandSuppression.NO_TASK,
    val notificationAvailable: Boolean = false,
    val failure: IslandFailure = IslandFailure.NONE,
)

/**
 * Apply protect() to individual sensitive APK controls (approval/auth/payment etc.). For Compose or
 * whole-Activity coverage, reject isObscured(event) in Activity.dispatchTouchEvent instead of installing
 * a decorView touch listener (a ViewGroup listener does not guard every descendant).
 * Includes partial obscuration, which View.filterTouchesWhenObscured alone does not reject.
 * Call before installing another touch listener, or use isObscured in that listener/dispatchTouchEvent.
 */
object IslandTouchGuard {
    fun isObscured(event: MotionEvent): Boolean =
        event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or PARTIALLY_OBSCURED) != 0

    fun protect(view: View) {
        view.filterTouchesWhenObscured = true
        view.setOnTouchListener { _, event -> isObscured(event) }
    }

    private const val PARTIALLY_OBSCURED = 0x2 // Public platform flag since API 29; harmless on API 28.
}


