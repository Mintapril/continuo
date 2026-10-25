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

/**
 * The task state shared by the status card, the full-screen viewer and the app (fed by CoworkStateBridge), and the
 * pause/stop path from those controls to the broker. Own-app callbacks only; deliberately no exported receiver,
 * arbitrary intent or network destination. (The name dates from the task island this used to drive.)
 */
object IslandRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = IslandStore(scope)
    @Volatile private var actionHandler: (suspend (IslandActionRequest) -> Unit)? = null

    private var restored = false
    /** Once per process: keeps the optional accessibility layer (the viewer above the status bar) switched on. */
    @Synchronized fun restore(context: Context) {
        if (restored) return
        restored = true
        IslandLayerKeeper.start(context)
    }

    /** Install an application-lifetime handler; do not capture an Activity. Clear on logout. */
    fun setActionHandler(handler: (suspend (IslandActionRequest) -> Unit)?) { actionHandler = handler }

    fun dispatch(taskId: String, revision: Long, action: IslandAction, execution: IslandExecution?): Boolean =
        store.requestAction(taskId, revision, action, actionHandler, execution)

    /** Return an ordinary Settings intent; callers show explanation before launching it. No grant bypass. */
    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"),
    )
}

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
