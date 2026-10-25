package dev.dsh.pocket.cowork

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.dsh.pocket.island.EdgeGlow
import dev.dsh.pocket.island.IslandAccessibilityService
import dev.dsh.pocket.island.IslandTouchGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The AI's screen over the whole phone screen, in one of two modes:
 * - watching (看画面): the owner only looks; touches never reach the AI's screen. 接手 takes it, 暂停/停止 act on the
 *   turn, an edge swipe or 关闭 leaves.
 * - in control (接手): the owner operates the AI's screen with touch, edge gestures, an app list and this phone's
 *   keyboard; 交还 hands it back.
 * The broker decides who is in control. A takeover the model asked for only opens once the owner accepts it (the
 * 去接手 notification). Owned by [CoworkControllerService].
 */
internal class TakeoverScreen(private val service: Context, private val scope: CoroutineScope, private val name: () -> String) {
    private enum class Mode { WATCH, CONTROL }
    private var overlayContext: Context? = null
    private var windowManager: WindowManager? = null
    /** True when the window lives on the accessibility layer, the only non-system layer above the status bar. */
    private var accessibilityLayer = false
    private var mode: Mode? = null
    /** The owner opened the viewer (看画面); after handing back it stays open on the watching mode. */
    private var watching = false
    /** The owner accepted the model's request for help, or took the screen themselves (the card stops saying 找你). */
    val accepted = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var liveHeld = false
    private var root: FrameLayout? = null
    private var image: ImageView? = null
    private var video: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var manager: WindowManager? = null
    private var watchBar: View? = null
    private var watchTitle: TextView? = null
    private var pauseButton: TextView? = null
    private var stopButton: TextView? = null
    private var stopArmedAt = 0L
    private var tools: View? = null
    private var takeButton: TextView? = null
    private var handBackButton: TextView? = null
    /** A short line at the top that says what just changed (你在操作 / 交还了), then fades. */
    private var cue: TextView? = null
    // Typing into the owned display during a takeover. Its IME policy is HIDE, so this phone's keyboard types into a
    // local proxy and committed text goes over the takeover channel. The picture only becomes focusable while typing;
    // it stays on its layer, because the system draws the keyboard right above the window it types into.
    private var typing = false
    private var keyboardDismissed = false
    /** The person raised the keyboard with the 键盘 button: typing stays on without a field the broker recognised (a web
     *  page or a code entry the app draws itself); the keys go to whatever has focus on the owned display. */
    private var manualTyping = false
    private var keysButton: View? = null
    private var appPicker: View? = null
    private var keyboard: RemoteKeyboardView? = null
    private var typingBar: View? = null
    private var imeBottom = 0
    private var imeShown = false
    private var contentShift = 0f
    /** Raises the keyboard once the typing window actually holds focus (showSoftInput before that is ignored). */
    private val typingFocus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused && typing) keyboard?.showKeyboard() }
    private var edgeGlow: EdgeGlow? = null

    /** Whether the screen can be shown at all (overlay permission, or the accessibility layer). */
    fun available(): Boolean = IslandAccessibilityService.available || Settings.canDrawOverlays(service)

    /** 看画面. Returns false when no window can be shown. */
    fun watch(): Boolean {
        watching = true
        sync(CoworkConnection.status.value)
        return mode != null
    }

    /** 去接手: the owner accepts the model's request (or takes the screen when nobody asked). */
    fun accept(): Boolean {
        takeButton?.takeIf { mode == Mode.WATCH }?.apply { text = "接手中"; isEnabled = false }
        accepted.value = true
        val status = CoworkConnection.status.value
        if (!status.optBoolean("userControl")) scope.launch { runCatching { CoworkConnection.takeover(true) } }
        sync(status)
        return mode != null || !status.optBoolean("userControl")
    }

    /** Follows the broker state: who is in control decides the mode. */
    fun sync(status: JSONObject) {
        val linked = CoworkConnection.connection.value == CoworkLinkState.CONNECTED && status.optInt("displayId") > 0
        val control = linked && status.optBoolean("userControl")
        if (!control) accepted.value = false
        val asked = control && status.optString("interventionBy") == "model" && !accepted.value
        val next = when {
            !linked -> null
            control && !asked -> Mode.CONTROL
            watching -> Mode.WATCH
            else -> null
        }
        if (next == null) { if (!linked) watching = false; close(); return }
        if (root == null && !open()) { watching = false; return }
        if (next != mode) switchTo(next)
        refreshWatchBar(status)
    }

    /**
     * Leaves the screen entirely (关闭, an edge swipe while watching, handing back when the owner was not watching, or
     * the service ending). [animated]: the picture fades and shrinks away first; it takes no touch while it does.
     */
    fun close(animated: Boolean = true) {
        val view = root ?: run { mode = null; return }
        val keyboardUp = typing
        stopTyping(reattach = false)
        manualTyping = false; appPicker = null; keysButton = null
        root = null; image = null; video = null; watchBar = null; watchTitle = null; pauseButton = null; stopButton = null; tools = null
        takeButton = null; handBackButton = null; cue = null
        edgeGlow?.hide(fade = animated); edgeGlow = null
        val wm = manager; val p = params
        manager = null; params = null; keyboardDismissed = false; mode = null
        syncLive(false)
        if (!animated || wm == null || p == null) { runCatching { wm?.removeViewImmediate(view) }; return }
        // No touch while it fades. A keyboard that was up keeps its window focused until it has slid down: a window that
        // loses focus (or goes away) takes its keyboard with it at once, without the slide.
        val unfocus = if (keyboardUp) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or unfocus; wm.updateViewLayout(view, p) }
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(200).setInterpolator(AccelerateInterpolator()).start()
        view.postDelayed({ runCatching { wm.removeViewImmediate(view) } }, if (keyboardUp) KEYBOARD_DOWN_MS else 200L)
    }

    /** Rotation or display change: rebuild on the new geometry. */
    fun reopen() {
        val was = mode ?: return
        close(animated = false); overlayContext = null; windowManager = null
        if (was == Mode.WATCH) watching = true
        sync(CoworkConnection.status.value)
    }

    fun onFieldChanged() = syncTyping()

    fun frame(bitmap: android.graphics.Bitmap?) { image?.setImageBitmap(bitmap) }

    private fun ensureWindow(): Boolean {
        if (windowManager != null && accessibilityLayer == IslandAccessibilityService.available) return true
        windowManager = null; overlayContext = null
        // Measured on this device: a TYPE_APPLICATION_OVERLAY window gets base layer 111000 while the status bar sits at
        // 151000, so it could never cover the clock or the status icons. Windows created from an AccessibilityService
        // context as TYPE_ACCESSIBILITY_OVERLAY sit above it instead.
        IslandAccessibilityService.instance?.let { accessibility ->
            accessibility.getSystemService(WindowManager::class.java)?.let { wm ->
                overlayContext = accessibility; windowManager = wm; accessibilityLayer = true
                return true
            }
        }
        accessibilityLayer = false
        if (!Settings.canDrawOverlays(service)) return false
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val displayContext = service.createDisplayContext(display)
        val context = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        overlayContext = context
        windowManager = context.getSystemService(WindowManager::class.java)
        return windowManager != null
    }

    private fun windowType(): Int = if (accessibilityLayer) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * The owned display fills the whole screen exactly like the main screen (same aspect, drawn behind the status bar
     * and cutout). In control the flowing colour frame runs around the edges.
     */
    private fun open(): Boolean {
        if (!ensureWindow()) return false
        val context = overlayContext ?: return false
        val wm = windowManager ?: return false
        val real = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else
            context.resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            filterTouchesWhenObscured = true
            contentDescription = "${name()} 的手机画面"
        }
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(CoworkConnection.live.value) }
        val live = LiveTextureView(context, FULLSCREEN_SINK_PRIORITY)
        frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
        frame.addView(live, FrameLayout.LayoutParams(-1, -1))
        // System gestures: a swipe that starts on the left/right/bottom edge is a "back / leave" gesture. It must act on the
        // owned display (or close the viewer), not on the main screen below the overlay, so (1) the edge strips are
        // excluded from the system gesture recogniser, and (2) the overlay recognises the swipe itself.
        val edge = dp(EDGE_GESTURE_DP).toFloat()
        val reach = dp(GESTURE_REACH_DP).toFloat()
        var gestureEdge = 0 // 0 none, 1 left, 2 right, 3 bottom
        var gestureFired = false
        var downX = 0f; var downY = 0f
        frame.setOnTouchListener { v, event ->
            if (IslandTouchGuard.isObscured(event)) return@setOnTouchListener true
            val controlling = mode == Mode.CONTROL && CoworkConnection.status.value.optBoolean("userControl")
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x; downY = event.y; gestureFired = false
                gestureEdge = when {
                    event.x <= edge -> 1
                    event.x >= v.width - edge -> 2
                    event.y >= v.height - edge -> 3
                    else -> 0
                }
            }
            if (gestureEdge != 0) {
                // The swipe is consumed here and never forwarded as a touch to the owned display.
                if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = event.x - downX; val dy = event.y - downY
                    val inward = when (gestureEdge) { 1 -> dx; 2 -> -dx; else -> -dy }
                    if (!gestureFired && inward >= reach) {
                        gestureFired = true
                        // Android gesture navigation: the side edges go back, the bottom edge goes home (the AI desktop).
                        // While only watching, any of them leaves the viewer.
                        if (controlling) CoworkConnection.sendKey(if (gestureEdge == 3) "HOME" else "BACK") else { watching = false; close() }
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) gestureEdge = 0
                return@setOnTouchListener true
            }
            if (!controlling) return@setOnTouchListener true
            val info = LiveVideo.info.value ?: return@setOnTouchListener true
            val x = (event.x / v.width * info.width).coerceIn(0f, info.width - 1f)
            val y = ((event.y + contentShift) / v.height * info.height).coerceIn(0f, info.height - 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> CoworkConnection.sendTouch("down", x, y)
                MotionEvent.ACTION_MOVE -> CoworkConnection.sendTouch("move", x, y)
                MotionEvent.ACTION_UP -> {
                    CoworkConnection.sendTouch("up", x, y)
                    // Tapping the screen again (e.g. the same field after the keyboard was put away) may bring it back.
                    if (keyboardDismissed) { keyboardDismissed = false; v.postDelayed({ syncTyping() }, 300) }
                }
                MotionEvent.ACTION_CANCEL -> CoworkConnection.sendTouch("cancel", x, y)
            }
            true
        }
        frame.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) {
                val h = b - t; val w = r - l
                // Android caps exclusion at 200dp of height per edge; cover the middle band of both side edges.
                val band = minOf(h, dp(200))
                val top = (h - band) / 2
                v.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, top, dp(EDGE_GESTURE_DP), top + band),
                    android.graphics.Rect(w - dp(EDGE_GESTURE_DP), top, w, top + band),
                )
            }
        }
        frame.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val shown = insets.isVisible(WindowInsets.Type.ime())
                imeBottom = if (shown) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
                // The keyboard was put away (its own hide key, or BACK): leave typing until the screen is tapped again.
                if (typing && imeShown && !shown) { keyboardDismissed = true; manualTyping = false; frame.post { syncTyping() } }
                imeShown = shown
            }
            updateShift(); insets
        }
        // In control: 交还 above two buttons near the bottom edge: open a work-profile app (the AI desktop has no
        // launcher), and raise this phone's keyboard when a field was not recognised by itself.
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; alpha = 0.9f; visibility = View.GONE }
        val giveBack = pillButton("交还", ACCENT) { handBack() }
        column.addView(giveBack, LinearLayout.LayoutParams(dp(72), dp(40)).apply { bottomMargin = dp(10) })
        column.addView(pillButton("应用") { toggleAppPicker() }, LinearLayout.LayoutParams(dp(72), dp(40)).apply { bottomMargin = dp(10) })
        val keys = pillButton("键盘") { manualTyping = true; keyboardDismissed = false; syncTyping() }
        column.addView(keys, LinearLayout.LayoutParams(dp(72), dp(40)))
        frame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(EDGE_GESTURE_DP + 12); bottomMargin = dp(EDGE_GESTURE_DP + 64)
        })
        // Watching: what it is doing on top, and the controls at the bottom.
        val title = TextView(context).apply { textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(Color.argb(225, 22, 22, 26)); cornerRadius = dp(22).toFloat() }
            isClickable = true
        }
        bar.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val take = pillButton("接手", ACCENT) { accept() }
        val pause = pillButton("暂停") { act("pause") }
        val stop = pillButton("停止", DANGER) { stopPressed() }
        val leave = pillButton("关闭") { watching = false; close() }
        for (b in listOf(take, pause, stop, leave)) buttons.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        bar.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        frame.addView(bar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = dp(EDGE_GESTURE_DP); marginEnd = dp(EDGE_GESTURE_DP); bottomMargin = dp(EDGE_GESTURE_DP + 24)
        })
        val statusId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val note = TextView(context).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; alpha = 0f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { setColor(Color.argb(215, 22, 22, 26)); cornerRadius = dp(20).toFloat() }
        }
        frame.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (if (statusId != 0) service.resources.getDimensionPixelSize(statusId) else dp(32)) + dp(14)
        })
        val p = fullScreenParams(real.width(), real.height(), windowType())
        // The picture grows in from slightly smaller while it fades in over what was on screen.
        frame.alpha = 0f; frame.scaleX = 0.94f; frame.scaleY = 0.94f
        try { wm.addView(frame, p) }
        catch (error: RuntimeException) { android.util.Log.w(TAG, "full screen failed", error); return false }
        frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        root = frame; image = poster; video = live; params = p; manager = wm
        watchBar = bar; watchTitle = title; pauseButton = pause; stopButton = stop; tools = column; keysButton = keys
        takeButton = take; handBackButton = giveBack; cue = note
        syncLive(true)
        return true
    }

    private fun switchTo(next: Mode) {
        val frame = root ?: return
        val previous = mode
        mode = next
        val control = next == Mode.CONTROL
        takeButton?.apply { text = "接手"; isEnabled = true }
        handBackButton?.apply { text = "交还"; isEnabled = true }
        if (previous == null) {
            // First appearance: the window itself animates in.
            watchBar?.visibility = if (control) View.GONE else View.VISIBLE
            tools?.visibility = if (control) View.VISIBLE else View.GONE
        } else if (control) {
            watchBar?.fadeOut(dy = dp(24).toFloat()); tools?.fadeIn(TOOLS_ALPHA, dx = dp(36).toFloat())
        } else {
            tools?.fadeOut(dx = dp(36).toFloat()); watchBar?.fadeIn(1f, dy = dp(24).toFloat())
        }
        if (control) showCue("你在操作${name()}的手机") else if (previous == Mode.CONTROL) showCue("交还了，${name()}接着做")
        appPicker?.let { closeAppPicker(frame, it) }
        if (control) {
            val context = overlayContext ?: return
            val wm = manager ?: return
            val real = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else
                context.resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
            // The colour frame is four thin non-touchable windows (pure decoration, not secure: they show no content).
            if (edgeGlow == null) edgeGlow = EdgeGlow(context, context.resources.displayMetrics.density).also { it.show(wm, real.width(), real.height(), 120f, windowType()) }
            if (CoworkConnection.apps.value.isEmpty()) scope.launch { runCatching { CoworkConnection.refreshApps() } }
            syncTyping()
        } else {
            edgeGlow?.hide(fade = true); edgeGlow = null
            manualTyping = false; stopTyping()
        }
    }

    /** Shows [view] (sliding from [dx]/[dy] towards its place) up to [to] alpha; a pending fade-out is cancelled. */
    private fun View.fadeIn(to: Float, dx: Float = 0f, dy: Float = 0f) {
        animate().cancel()
        if (visibility != View.VISIBLE) { alpha = 0f; translationX = dx; translationY = dy; visibility = View.VISIBLE }
        animate().alpha(to).translationX(0f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Hides [view] (sliding towards [dx]/[dy]); it is GONE once the fade ends, unless something showed it again. */
    private fun View.fadeOut(dx: Float = 0f, dy: Float = 0f) {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        val keep = alpha
        animate().alpha(0f).translationX(dx).translationY(dy).setDuration(180).setInterpolator(AccelerateInterpolator())
            .withEndAction { visibility = View.GONE; alpha = keep; translationX = 0f; translationY = 0f }.start()
    }

    private fun showCue(text: String) {
        val view = cue ?: return
        view.text = text
        view.animate().cancel()
        view.translationY = -dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction {
            view.animate().alpha(0f).setStartDelay(1_300).setDuration(320).withEndAction { view.animate().setStartDelay(0) }.start()
        }.start()
    }

    private fun refreshWatchBar(status: JSONObject) {
        if (mode != Mode.WATCH) return
        val card = StatusCard.of(name(), CoworkConnection.connection.value, status, accepted.value, 12, errorAgeMs = ErrorClock.ageMs(service, status))
        watchTitle?.text = if (card.asking) "${card.title}：${card.text.removePrefix("需要你接手：")}" else card.title
        val running = status.optString("state") == "running" && status.optString("taskId").isNotBlank()
        pauseButton?.visibility = if (running) View.VISIBLE else View.GONE
        stopButton?.visibility = if (card.working) View.VISIBLE else View.GONE
        if (!card.working) stopArmedAt = 0L
        stopButton?.text = if (armed()) "确认停止" else "停止"
    }

    private fun armed() = stopArmedAt > 0 && android.os.SystemClock.elapsedRealtime() - stopArmedAt < 4_000

    /** Stop ends the whole turn: the first press arms it, a second within four seconds confirms. */
    private fun stopPressed() {
        if (!armed()) {
            stopArmedAt = android.os.SystemClock.elapsedRealtime(); stopButton?.text = "确认停止"
            stopButton?.postDelayed({ if (!armed()) stopButton?.text = "停止" }, 4_100)
            return
        }
        stopArmedAt = 0L; act("stop")
    }

    private fun act(action: String) {
        val target = CoworkControlTarget.from(CoworkConnection.status.value)
        scope.launch { runCatching { CoworkConnection.control(action, target) }.onFailure { android.util.Log.w(TAG, "$action failed: ${it.message}") } }
    }

    private fun handBack() {
        handBackButton?.apply { text = "交还中"; isEnabled = false }
        accepted.value = false
        scope.launch { runCatching { CoworkConnection.takeover(false) } }
    }

    /** Work-profile apps over the picture; a tap opens one on the owned display, a tap beside the list closes it. */
    private fun toggleAppPicker() {
        val frame = root ?: return
        appPicker?.let { closeAppPicker(frame, it); return }
        val context = overlayContext ?: return
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        fun fill() {
            list.removeAllViews()
            val apps = CoworkConnection.apps.value
            if (apps.isEmpty()) list.addView(text("正在读取它手机里的应用…", 14f, DIM).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) })
            apps.forEach { app ->
                list.addView(text(app.label, 16f).apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12)); isClickable = true; isFocusable = true
                    setOnClickListener { CoworkConnection.sendLaunch(app.packageName); appPicker?.let { closeAppPicker(frame, it) } }
                    IslandTouchGuard.protect(this)
                })
            }
        }
        fill()
        if (CoworkConnection.apps.value.isEmpty()) scope.launch { runCatching { CoworkConnection.refreshApps() }; if (appPicker != null) fill() }
        val card = ScrollView(context).apply {
            background = GradientDrawable().apply { setColor(Color.argb(240, 28, 28, 30)); cornerRadius = dp(18).toFloat() }
            addView(list)
        }
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0)); isClickable = true
            setOnClickListener { closeAppPicker(frame, this) }
            addView(card, FrameLayout.LayoutParams(dp(260), (frame.height * 0.6f).toInt().coerceAtLeast(dp(200)), Gravity.CENTER))
        }
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        appPicker = scrim
        scrim.alpha = 0f; card.scaleX = 0.9f; card.scaleY = 0.9f
        scrim.animate().alpha(1f).setDuration(160).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** The app list fades away, then leaves the picture. */
    private fun closeAppPicker(frame: FrameLayout, picker: View) {
        if (appPicker === picker) appPicker = null
        picker.isClickable = false
        picker.animate().cancel()
        picker.animate().alpha(0f).setDuration(140).withEndAction { frame.removeView(picker) }.start()
    }

    /** Parameters for the full-screen window; it opens unfocusable and only takes focus while typing. */
    private fun fullScreenParams(width: Int, height: Int, type: Int) = WindowManager.LayoutParams(
        width, height, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT, // so the picture can fade in and out over what is on screen
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Continuo 它的手机"
        preferredRefreshRate = 120f // follow the highest rate the panel supports
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
    }

    /** Typing follows the broker (a focused input field on the owned display while the person is in control), or the
     *  person's 键盘 button. */
    private fun syncTyping() {
        val frame = root ?: return
        val control = mode == Mode.CONTROL && CoworkConnection.status.value.optBoolean("userControl")
        val field = CoworkConnection.field.value?.takeIf { control }
        if (!control) manualTyping = false
        if (field == null && !manualTyping) { keyboardDismissed = false; stopTyping(); return }
        if (keyboardDismissed && !manualTyping) { stopTyping(); return }
        if (!typing && !startTyping(frame)) return
        keyboard?.remote = field
        typingBar?.findViewWithTag<TextView>(TYPING_LABEL)?.text = when {
            field == null -> "用本机键盘输入：按键直接发给副屏上选中的地方"
            field.password -> "正在输入副屏的密码框：用本机键盘输入，AI 看不到"
            else -> "正在输入副屏的输入框：用本机键盘输入"
        }
        updateShift()
    }

    private fun startTyping(frame: FrameLayout): Boolean {
        val context = overlayContext ?: return false
        val p = params ?: return false
        val current = manager ?: return false
        frame.removeCallbacks(unfocus) // a pending "unfocus after the keyboard went down" no longer applies
        try {
            // Only the flags change: removing and re-adding the window would drop the live picture for a moment.
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            current.updateViewLayout(frame, p)
        } catch (error: RuntimeException) {
            android.util.Log.w(TAG, "typing window failed", error)
            params?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED }
            keyboardDismissed = true
            return false
        }
        val label = TextView(context).apply { tag = TYPING_LABEL; textSize = 13f; setTextColor(Color.WHITE) }
        val preview = TextView(context).apply { textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        val proxy = RemoteKeyboardView(context,
            send = { key -> when (key) { is RemoteKeystroke.Text -> CoworkConnection.sendText(key.text); is RemoteKeystroke.Key -> CoworkConnection.sendKey(key.key) } },
            refused = { label.text = "密码框只能输入英文字母、数字和符号" },
            composing = { preview.text = it })
        proxy.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { keyboardDismissed = true; manualTyping = false; syncTyping(); true } else false
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(preview) }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.argb(230, 28, 28, 30)); cornerRadius = dp(18).toFloat() }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(proxy, LinearLayout.LayoutParams(1, 1))
            addView(pillButton("收起键盘") { keyboardDismissed = true; manualTyping = false; syncTyping() }, LinearLayout.LayoutParams(dp(96), dp(40)))
        }
        val statusId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusId != 0) service.resources.getDimensionPixelSize(statusId) else dp(32)
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = statusBar + dp(60); marginStart = dp(12); marginEnd = dp(12)
        })
        bar.alpha = 0f; bar.translationY = -dp(16).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        typingBar = bar; keyboard = proxy; typing = true; imeShown = false
        keysButton?.visibility = View.GONE
        proxy.post { proxy.showKeyboard() }
        frame.viewTreeObserver.addOnWindowFocusChangeListener(typingFocus)
        return true
    }

    /**
     * Leave typing; [reattach] makes the picture unfocusable again, once the keyboard has slid down (a window that loses
     * focus takes its keyboard away at once, without the slide). Typing again before then cancels it.
     */
    private fun stopTyping(reattach: Boolean = true) {
        if (!typing) return
        typing = false; imeShown = false; imeBottom = 0
        keysButton?.visibility = View.VISIBLE
        root?.viewTreeObserver?.removeOnWindowFocusChangeListener(typingFocus)
        keyboard?.hideKeyboard()
        typingBar?.let { bar -> bar.animate().cancel(); bar.animate().alpha(0f).translationY(-dp(16).toFloat()).setDuration(150).withEndAction { (bar.parent as? ViewGroup)?.removeView(bar) }.start() }
        typingBar = null; keyboard = null
        updateShift()
        val frame = root ?: return
        if (!reattach) return
        frame.removeCallbacks(unfocus)
        frame.postDelayed(unfocus, KEYBOARD_DOWN_MS)
    }

    private val unfocus = Runnable {
        val frame = root ?: return@Runnable
        val p = params ?: return@Runnable
        if (typing) return@Runnable
        runCatching {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            manager?.updateViewLayout(frame, p)
        }.onFailure { android.util.Log.w(TAG, "leaving typing failed", it) }
    }

    /** Keep the focused field above the keyboard by moving the picture up; touches follow the same offset. */
    private fun updateShift() {
        val frame = root
        val field = CoworkConnection.field.value
        val info = LiveVideo.info.value
        var shift = 0f
        if (typing && frame != null && field?.bounds != null && info != null && imeBottom > 0 && frame.height > 0) {
            val bottom = field.bounds[3] / info.height.toFloat() * frame.height
            shift = (bottom + dp(16) - (frame.height - imeBottom)).coerceIn(0f, frame.height * 0.6f)
        }
        contentShift = shift
        for (view in listOfNotNull(image, video)) { view.animate().cancel(); view.animate().translationY(-shift).setDuration(200).setInterpolator(DecelerateInterpolator()).start() }
    }

    /** The broker allows one viewer; the shared reference count keeps the call sheet and this screen from fighting. */
    private fun syncLive(want: Boolean) {
        if (want && !liveHeld) { CoworkConnection.acquireLive(); liveHeld = true }
        else if (!want && liveHeld) { CoworkConnection.releaseLive(); liveHeld = false }
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(requireNotNull(overlayContext)).apply {
        text = value; textSize = size; setTextColor(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun pillButton(label: String, tint: Int = Color.WHITE, onClick: () -> Unit) = TextView(requireNotNull(overlayContext)).apply {
        text = label; textSize = 14f; setTextColor(tint); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        val shape = GradientDrawable().apply { setColor(Color.rgb(44, 44, 46)); cornerRadius = dp(22).toFloat() }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, null)
        setOnClickListener { onClick() }
        IslandTouchGuard.protect(this)
    }

    private fun dp(value: Int): Int = (value * (overlayContext ?: service).resources.displayMetrics.density).toInt()

    private companion object {
        /** The full-screen picture must out-rank the call sheet's small preview as the decoder's output. */
        const val FULLSCREEN_SINK_PRIORITY = 10
        const val EDGE_GESTURE_DP = 24
        const val TOOLS_ALPHA = 0.9f
        /** How long the system takes to slide the keyboard down, with a little margin (measured ~200 ms on this phone). */
        const val KEYBOARD_DOWN_MS = 320L
        const val TYPING_LABEL = "typing-label"
        const val GESTURE_REACH_DP = 56
        const val TAG = "DshTakeover"
        val DIM = Color.rgb(152, 152, 160)
        val ACCENT = Color.rgb(100, 210, 255)
        val DANGER = Color.rgb(255, 120, 110)
    }
}
