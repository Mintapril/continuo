package dev.dsh.pocket

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.dsh.pocket.island.IslandTouchGuard
import dev.dsh.pocket.ui.CallSheet
import dev.dsh.pocket.ui.NoteSheet
import dev.dsh.pocket.ui.PocketTheme

/**
 * 叫它 / 记便条 as a window over whatever is on screen, without starting an activity: no task switch and no launch
 * animation (ColorOS grows a card from a quick-settings tile around any activity the tile starts). Used by the tile,
 * the status card and the assistant ([dev.dsh.pocket.assist.AssistSession]). It needs "display over other apps" and an
 * unlocked screen; otherwise [show] returns false and the caller opens [CallActivity]. The view model (one DSH
 * connection) stays a few minutes after the sheet is put away, so calling again is instant.
 */
object CallOverlay {
    private var host: Host? = null
    private val main = Handler(Looper.getMainLooper())
    private val release = Runnable { host?.takeIf { !it.showing }?.let { it.destroy(); host = null } }

    fun available(context: Context): Boolean = Settings.canDrawOverlays(context) &&
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    /** Shows 叫它, or 记便条 when [note]; [draft] only fills the input. False when the caller should open CallActivity. */
    fun show(context: Context, note: Boolean = false, draft: String? = null): Boolean {
        val app = context.applicationContext as Application
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { if (!show(app, note, draft)) app.startActivity(open(app, note, draft)) }; return true }
        if (!available(app)) return false
        main.removeCallbacks(release)
        dev.dsh.pocket.island.IslandRuntime.restore(app)
        dev.dsh.pocket.cowork.CoworkControllerService.ensureRunning(app)
        val shown = (host ?: Host(app).also { host = it }).show(note, draft)
        if (!shown) main.postDelayed(release, KEEP_MS)
        return shown
    }

    internal fun open(context: Context, note: Boolean, draft: String?, voice: Boolean = false): Intent =
        (if (note) CallActivity.note(context, draft) else CallActivity.call(context, draft)).putExtra(CallActivity.EXTRA_VOICE, voice)

    private fun putAway() = main.postDelayed(release, KEEP_MS)

    private const val KEEP_MS = 5 * 60_000L

    private class Host(private val app: Application) : OnBackPressedDispatcherOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        private val saved = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        private val windows = app.getSystemService(WindowManager::class.java)
        private var view: View? = null
        val showing get() = view != null
        private val vm: PocketViewModel
        /** The screen going off puts the sheet away: it never stays over the lock screen. */
        private val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { view?.let { remove(it) } } }

        init {
            saved.performAttach(); saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
            vm = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PocketViewModel::class.java]
        }

        fun show(note: Boolean, draft: String?): Boolean {
            view?.let { remove(it) }
            // The root guards against touches through other windows and turns back into the sheet's back: on Android 13+
            // through the window's back callback (targeting Android 16, back no longer arrives as a key event), before
            // that as the back key.
            val back = { onBackPressedDispatcher.onBackPressed() }
            val sheet = object : FrameLayout(app) {
                private val callback: Any? = if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { back() } else null
                override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (IslandTouchGuard.isObscured(event)) true else super.dispatchTouchEvent(event)
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
                    return true
                }
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback as android.window.OnBackInvokedCallback)
                }
                override fun onDetachedFromWindow() {
                    if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback as android.window.OnBackInvokedCallback)
                    super.onDetachedFromWindow()
                }
            }
            sheet.setViewTreeLifecycleOwner(this); sheet.setViewTreeViewModelStoreOwner(this)
            sheet.setViewTreeSavedStateRegistryOwner(this); sheet.setViewTreeOnBackPressedDispatcherOwner(this)
            val compose = ComposeView(app)
            sheet.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val close = { remove(sheet) }
            // What needs an activity (the microphone permission, a question or approval sheet) continues in CallActivity.
            val handOver = { voice: Boolean -> app.startActivity(open(app, note, null, voice)); remove(sheet) }
            compose.setContent {
                PocketTheme(vm.theme, opaque = false) {
                    if (note) NoteSheet(vm, draft, close = close)
                    else CallSheet(vm, draft, close = close, askMic = { handOver(true) }, onPending = { handOver(false) })
                }
            }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
                title = "Continuo 叫它"
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            }
            try { windows.addView(sheet, params) } catch (error: RuntimeException) { android.util.Log.w("CallOverlay", "sheet window refused", error); return false }
            view = sheet
            vm.resumeConnection() // a connection that dropped while the sheet was put away comes back at once
            ContextCompat.registerReceiver(app, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            registry.currentState = Lifecycle.State.RESUMED
            return true
        }

        fun remove(sheet: View) {
            if (view !== sheet) return
            view = null
            runCatching { app.unregisterReceiver(screenOff) }
            runCatching { windows.removeViewImmediate(sheet) }
            registry.currentState = Lifecycle.State.CREATED
            putAway()
        }

        fun destroy() {
            view?.let { remove(it) }
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
