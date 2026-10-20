package dev.dsh.pocket.island

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.LiveTextureView
import dev.dsh.pocket.cowork.LiveVideo
import dev.dsh.pocket.cowork.RemoteKeyboardView
import dev.dsh.pocket.cowork.RemoteKeystroke
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.dsh.pocket.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manifest must be non-exported and specialUse FGS (see integration handoff). No auto/boot restart.
 * A native View window avoids borrowing an Activity/Compose lifecycle and remains tightly bounded.
 */
class IslandOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager
    private var overlayContext: Context? = null
    private var windowManager: WindowManager? = null
    /** True when the window lives on the accessibility layer, which is the only non-system layer above the status bar. */
    private var accessibilityLayer = false
    private var panel: IslandPillView? = null
    private var expanded = false
    private var attachedTaskId: String? = null
    private var lastRendered: IslandSnapshot? = null
    private var notificationSignature: String? = null
    private var foregroundStarted = false
    private var overlayFailed = false
    private var overlayPermissionLast = false
    private var liveView: ImageView? = null
    private var liveHeld = false
    private var lastLiveKey = ""
    /** Model-requested takeover: the island opens by itself; the user accepts with a double tap on the preview. */
    private var requestAccepted = false
    private var requestExpanded = false
    private var fullView: FrameLayout? = null
    private var fullImage: ImageView? = null
    private var fullVideo: View? = null
    private var fullParams: WindowManager.LayoutParams? = null
    /** The manager the full-screen window is attached to: the island's own, or [typingManager] while typing. */
    private var fullManager: WindowManager? = null
    // Typing into the owned display during a takeover. Its IME policy is HIDE, so this phone's keyboard types into a
    // local proxy and committed text goes over the takeover channel. The keyboard is drawn above ordinary overlays
    // but below the accessibility layer, so while typing the picture moves to the ordinary overlay layer.
    private var typing = false
    private var keyboardDismissed = false
    private var keyboard: RemoteKeyboardView? = null
    private var typingBar: View? = null
    private var typingManager: WindowManager? = null
    private var imeBottom = 0
    private var imeShown = false
    private var contentShift = 0f
    /** Raises the keyboard once the typing window actually holds focus (showSoftInput before that is ignored). */
    private val typingFocus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused && typing) keyboard?.showKeyboard() }
    private var edgeGlow: EdgeGlow? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "任务岛状态", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "用户开启的任务状态与暂停、停止入口；不展示敏感任务内容"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        // Always post immediately when started through startForegroundService, even if task vanished.
        val initial = IslandRuntime.store.snapshots.value
        try {
            val notification = buildNotification(initial)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (_: RuntimeException) {
            IslandRuntime.report(IslandAvailability(failure = IslandFailure.START_RESTRICTED))
            stopSelf()
            return
        }
        scope.launch { IslandRuntime.store.snapshots.collectLatest { render(it) } }
        scope.launch { CoworkConnection.live.collect { frame -> liveView?.setImageBitmap(frame); fullImage?.setImageBitmap(frame) } }
        // Intervention is driven by the broker state: whoever starts it (double tap here, the app, or the model asking
        // for help), the full-screen view appears while the person is in control and disappears when control returns.
        scope.launch { CoworkConnection.status.collect { syncIntervention(it) } }
        scope.launch { CoworkConnection.field.collect { syncTyping() } }
        // Permission, keyguard and foreground-context expiry only; never screenshot/agent-loop driven.
        scope.launch {
            while (isActive) {
                delay(1_000)
                render(IslandRuntime.store.snapshots.value)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_CONTROL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                val revision = intent.getLongExtra(EXTRA_REVISION, -1)
                val action = intent.getStringExtra(EXTRA_CONTROL)?.let { value ->
                    IslandAction.entries.firstOrNull { it.name == value }
                }
                if (taskId != null && action != null) IslandRuntime.dispatch(taskId, revision, action,
                    IslandExecution(intent.getStringExtra("instanceId").orEmpty(), intent.getStringExtra("executionId").orEmpty()))
            }
            ACTION_DISMISS -> IslandRuntime.disable(this)
            null -> overlayFailed = false // An explicit user start may retry an unavailable window.
        }
        render(IslandRuntime.store.snapshots.value)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeOverlay()
        overlayContext = null
        windowManager = null
        overlayFailed = false
        render(IslandRuntime.store.snapshots.value)
    }

    private fun render(snapshot: IslandSnapshot) {
        if (!foregroundStarted) return
        val permission = Settings.canDrawOverlays(this)
        if (permission != overlayPermissionLast) { overlayFailed = false; overlayPermissionLast = permission }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive
        // The island also shows over Continuo itself. The APK's approval/authentication controls stay safe because
        // MainActivity/CoworkActivity reject obscured touches (IslandTouchGuard) in dispatchTouchEvent.
        var visibility = IslandPolicy.visibility(snapshot, SystemClock.elapsedRealtime(), permission, locked)
        if (visibility.surface == IslandSurface.HIDDEN) {
            removeOverlay()
            IslandRuntime.report(IslandAvailability(reason = visibility.reason, notificationAvailable = notificationsAvailable()))
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return
        }
        if (visibility.surface == IslandSurface.OVERLAY && !overlayFailed) {
            if (!showOverlay(snapshot)) overlayFailed = true
        } else removeOverlay()
        if (overlayFailed && visibility.surface == IslandSurface.OVERLAY) {
            removeOverlay()
            visibility = visibility.copy(surface = IslandSurface.NOTIFICATION)
        }
        var notificationAvailable = notificationsAvailable()
        val signature = "${snapshot.task}|${snapshot.backendConnected}|${snapshot.pendingAction?.requestId}|${snapshot.actionError}|${visibility.surface}"
        if (notificationSignature != signature && notificationAvailable) {
            try {
                notifications.notify(NOTIFICATION_ID, buildNotification(snapshot))
                notificationSignature = signature
            } catch (_: SecurityException) { notificationSignature = null; notificationAvailable = false }
        }
        IslandRuntime.report(IslandAvailability(
            surface = visibility.surface, reason = visibility.reason,
            notificationAvailable = notificationAvailable,
            failure = when {
                !notificationAvailable -> IslandFailure.NOTIFICATIONS_UNAVAILABLE
                overlayFailed -> IslandFailure.OVERLAY_UNAVAILABLE
                else -> IslandFailure.NONE
            },
        ))
    }

    private fun notificationsAvailable(): Boolean = notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun ensureWindow(): Boolean {
        if (windowManager != null) return true
        // Measured on this device: a TYPE_APPLICATION_OVERLAY window gets base layer 111000 while the status bar
        // sits at 151000, so the island could never cover the clock, the battery/Wi-Fi icons or the status chips.
        // Windows created from an AccessibilityService context as TYPE_ACCESSIBILITY_OVERLAY sit above it instead.
        IslandAccessibilityService.instance?.let { accessibility ->
            accessibility.getSystemService(WindowManager::class.java)?.let { manager ->
                overlayContext = accessibility
                windowManager = manager
                accessibilityLayer = true
                return true
            }
        }
        accessibilityLayer = false
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val displayContext = createDisplayContext(display)
        val context = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        overlayContext = context
        windowManager = context.getSystemService(WindowManager::class.java)
        return windowManager != null
    }

    /** The accessibility layer when the user enabled the helper service, the ordinary overlay layer otherwise. */
    private fun overlayWindowType(): Int = if (accessibilityLayer) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    } else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showOverlay(snapshot: IslandSnapshot): Boolean = try {
        if (windowManager != null && accessibilityLayer != IslandAccessibilityService.available) {
            // The user just switched the accessibility helper on or off: rebuild the window on the other layer.
            removeOverlay()
            overlayContext = null
            windowManager = null
        }
        if (!ensureWindow()) false else {
            if (attachedTaskId != snapshot.task?.taskId) {
                expanded = false
                lastRendered = null
                attachedTaskId = snapshot.task?.taskId
                panel?.setExpanded(false, animate = false)
            }
            val context = requireNotNull(overlayContext)
            var created = false
            val root = panel ?: IslandPillView(context, geometry(), ::resizeWindow).also {
                panel = it
                created = true
                it.onToggle = { toggle() }
                it.onOutside = { if (expanded && fullView == null) toggle() }
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                applySnapshot(it, snapshot)
                windowManager!!.addView(it, layoutParams(it.collapsedWindowWidth(), it.collapsedWindowHeight()))
                it.appear()
            }
            val presentation = snapshot.copy(environment = IslandEnvironment())
            val status = CoworkConnection.status.value
            val liveKey = "${status.optInt("displayId") > 0 && status.optBoolean("workspaceActive")}|${status.optBoolean("userControl")}|${status.optString("interventionBy")}|$requestAccepted|${status.optString("interventionReason")}"
            if (!created && (lastRendered != presentation || lastLiveKey != liveKey)) {
                lastLiveKey = liveKey
                applySnapshot(root, snapshot)
            }
            lastRendered = presentation
            if (created) syncIntervention(status)
            true
        }
    } catch (error: RuntimeException) {
        android.util.Log.w("DshIsland", "overlay failed", error)
        removeOverlay()
        false // BadToken/display removal/permission revocation: keep notification, never request grants.
    }

    private fun toggle() {
        val view = panel ?: return
        expanded = !expanded
        val snapshot = IslandRuntime.store.snapshots.value
        lastRendered = null
        applySnapshot(view, snapshot)
        view.setExpanded(expanded)
    }

    /** Pixel geometry of the camera band, measured from the real insets so the pill sits on the cutout. */
    private fun geometry(): IslandPillView.Geometry {
        val wm = requireNotNull(windowManager)
        val density = requireNotNull(overlayContext).resources.displayMetrics.density
        val screenW: Int
        val screenH: Int
        var bandBottom = 0
        var holeW = 0
        var centerY = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            screenW = metrics.bounds.width(); screenH = metrics.bounds.height()
            val insets = metrics.windowInsets
            bandBottom = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
            insets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }?.let {
                holeW = it.width(); centerY = (it.top + it.bottom) / 2
                bandBottom = maxOf(bandBottom, it.bottom)
            }
        } else {
            val m = requireNotNull(overlayContext).resources.displayMetrics
            screenW = m.widthPixels; screenH = m.heightPixels
        }
        if (bandBottom <= 0) {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            bandBottom = if (id > 0) resources.getDimensionPixelSize(id) else (24 * density).toInt()
        }
        if (centerY <= 0) centerY = bandBottom / 2
        val collapsedH = minOf((30 * density).toInt(), (bandBottom - 4 * density).toInt().coerceAtLeast((22 * density).toInt()))
        val hole = maxOf(holeW, (26 * density).toInt())
        val collapsedW = hole + (2 * (10 + 40) * density).toInt()
        val margin = (18 * density).toInt()
        val expandedW = minOf((340 * density).toInt(), screenW - 2 * margin).coerceAtLeast(collapsedW)
        val top = (centerY - collapsedH / 2).coerceAtLeast(0)
        val maxBody = (screenH - top - collapsedH - (130 * density).toInt()).coerceAtLeast((120 * density).toInt())
        return IslandPillView.Geometry(centerY, collapsedW, collapsedH, expandedW, maxBody, density, bandBottom, hole)
    }

    private fun resizeWindow(w: Int, h: Int) {
        val view = panel ?: return
        runCatching { windowManager?.updateViewLayout(view, layoutParams(w, h)) }
    }

    private fun layoutParams(w: Int, h: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        w, h,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            (if (liveView != null || fullView != null) WindowManager.LayoutParams.FLAG_SECURE else 0) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0; y = 0 // Draw from the physical top edge so the pill overlaps the camera and status-bar band.
        title = "DSH 任务岛"
        noMoveAnimation(this)
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /**
     * Resizing a centred overlay moves its left edge; the system otherwise animates that move (about 14 frames),
     * which showed as the pill sliding sideways whenever the island collapsed or elongated.
     * PRIVATE_FLAG_NO_MOVE_ANIMATION makes the window frame jump to its final place; the pill itself animates inside.
     */
    private fun noMoveAnimation(params: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(params, field.getInt(params) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }.onFailure { android.util.Log.w("IslandDbg", "privateFlags unavailable: $it") }
    }

    private fun applySnapshot(view: IslandPillView, snapshot: IslandSnapshot) {
        val task = snapshot.task
        val connected = snapshot.backendConnected
        val active = connected && task != null && !task.state.terminal
        val running = active && task?.state == IslandTaskState.RUNNING
        val app = snapshot.activity.app
        val askPending = modelRequestPending(CoworkConnection.status.value)
        val headline = when {
            askPending -> "请求接管"
            !connected -> snapshot.statusLabel
            running && app.isNotBlank() -> "正在使用 $app"
            running -> "正在执行"
            else -> snapshot.statusLabel
        }
        val action = if (running) snapshot.activity.text else ""
        view.interventionMode = fullView != null
        view.onDoubleTap = { scope.launch { runCatching { CoworkConnection.takeover(false) } } }
        view.setHeader(
            task?.state, connected, active, if (active) headline else "", action, headline,
            "DSH 任务岛，$headline${if (action.isNotBlank()) "，$action" else ""}，${if (expanded) "收起详情" else "展开详情"}",
        )
        if (task == null || !expanded) { liveView = null; if (fullView == null) syncLive(false); view.setBody(null) }
        else view.setBody(buildBody(snapshot, task))
    }

    /** Expanded island: no buttons. Recent key actions on the left, the owned-display preview on the right. */
    private fun buildBody(snapshot: IslandSnapshot, task: IslandTaskUpdate): View {
        val context = requireNotNull(overlayContext)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), (panel?.bodyInset() ?: dp(8)), dp(20), dp(18))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val state = task.state
        if (snapshot.backendConnected && state != IslandTaskState.RUNNING) {
            list.addView(text(when (state) {
                IslandTaskState.APPROVAL -> "需要你确认，请返回应用"
                IslandTaskState.WAITING -> "等待你的输入"
                IslandTaskState.PAUSING -> "正在暂停…"
                IslandTaskState.STOPPING -> "正在停止…"
                IslandTaskState.PAUSED -> "已暂停"
                IslandTaskState.COMPLETED -> "本轮已结束"
                IslandTaskState.STOPPED -> "已停止"
                IslandTaskState.ERROR -> "执行出错，请在应用中查看"
                IslandTaskState.RUNNING -> ""
            }, 13f, Color.rgb(255, 214, 10)).apply { setPadding(0, 0, 0, dp(8)) })
        }
        val ask = CoworkConnection.status.value
        if (modelRequestPending(ask)) {
            val reason = ask.optString("interventionReason")
            list.addView(text("AI 请求接管${if (reason.isNotBlank()) "：$reason" else ""}", 14f, Color.rgb(255, 159, 10)).apply { setPadding(0, 0, 0, dp(4)) })
            list.addView(text("双击右侧画面开始操作", 12f, DIM).apply { setPadding(0, 0, 0, dp(8)) })
        } else if (ask.optBoolean("userControl")) {
            list.addView(text("你正在操作副屏", 13f, Color.rgb(100, 210, 255)).apply { setPadding(0, 0, 0, dp(8)) })
        }
        snapshot.recentEvents.takeLast(4).reversed().forEachIndexed { index, event ->
            list.addView(text(IslandPrivacy.eventLabel(event), 13f, if (index == 0) Color.WHITE else DIM).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(3))
            })
        }
        val status = CoworkConnection.status.value
        val workspace = status.optInt("displayId") > 0 && status.optBoolean("workspaceActive") && CoworkConnection.connection.value == CoworkLinkState.CONNECTED
        liveView = null
        body.addView(list, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        if (workspace) {
            val width = dp(112)
            val info = LiveVideo.info.value
            val height = width * (info?.height ?: 1608) / (info?.width ?: 720)
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(CoworkConnection.live.value)
            }
            val box = FrameLayout(context).apply {
                setBackgroundColor(Color.rgb(20, 20, 22))
                contentDescription = "副屏预览，双击介入"
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
                }
                addView(poster, FrameLayout.LayoutParams(-1, -1))
                addView(LiveTextureView(context), FrameLayout.LayoutParams(-1, -1))
                val detector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        requestAccepted = true
                        scope.launch { runCatching { CoworkConnection.takeover(true) }; syncIntervention(CoworkConnection.status.value) }
                        return true
                    }
                })
                isClickable = true
                setOnTouchListener { _, event -> if (IslandTouchGuard.isObscured(event)) true else detector.onTouchEvent(event) }
                setOnClickListener { }
                // Accessibility has no double-tap gesture of its own: the standard activate action enters intervention too.
                accessibilityDelegate = object : View.AccessibilityDelegate() {}
            }
            liveView = poster
            body.addView(box, LinearLayout.LayoutParams(width, height))
        }
        syncLive(workspace || fullView != null)
        return body
    }

    /** The broker state decides: person in control => seamless full screen with the colour frame, else closed. */
    private fun syncIntervention(status: org.json.JSONObject) {
        val control = status.optBoolean("userControl") && status.optInt("displayId") > 0 && status.optBoolean("workspaceActive") &&
            CoworkConnection.connection.value == CoworkLinkState.CONNECTED
        if (!control) { requestAccepted = false; requestExpanded = false }
        val ask = modelRequestPending(status)
        if (ask && fullView == null) {
            // Same as the user tapping the island: expand it once per request and show "请求接管". No full screen yet.
            val view = panel
            if (view != null && !requestExpanded) {
                requestExpanded = true
                if (!expanded) toggle() else { lastRendered = null; applySnapshot(view, IslandRuntime.store.snapshots.value) }
            }
        }
        else if (control && fullView == null && panel != null) openFullScreen()
        else if (!control && fullView != null) closeFullScreen()
    }

    /** The model asked for the screen and the person has not accepted yet (user-started takeover opens at once). */
    private fun modelRequestPending(status: org.json.JSONObject): Boolean =
        status.optBoolean("userControl") && status.optString("interventionBy") == "model" && !requestAccepted &&
            status.optInt("displayId") > 0 && status.optBoolean("workspaceActive") && CoworkConnection.connection.value == CoworkLinkState.CONNECTED

    /**
     * Intervention: the owned display fills the whole screen exactly like the main screen (same aspect, drawn behind
     * the status bar and cutout). The only difference is the flowing colour frame around the edges. The island stays
     * on top; a double tap on it hands control back.
     */
    private fun openFullScreen() {
        if (fullView != null) return
        val context = overlayContext ?: return
        val manager = windowManager ?: return
        val density = context.resources.displayMetrics.density
        val real = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds else
            context.resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            filterTouchesWhenObscured = true
            contentDescription = "副屏实时画面，双击任务岛交还给 AI"
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(CoworkConnection.live.value)
        }
        val video = LiveTextureView(context, FULLSCREEN_SINK_PRIORITY) // must beat the small island preview
        root.addView(poster, FrameLayout.LayoutParams(-1, -1))
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        // System gestures: a swipe that starts on the left/right/bottom edge is a "back / leave" gesture. It must act on the
        // owned display, not on the main screen below the overlay, so (1) the edge strips are excluded from the system
        // gesture recogniser, and (2) the overlay recognises the swipe itself and sends BACK to the owned display.
        val edge = dp(EDGE_GESTURE_DP).toFloat()
        val reach = dp(GESTURE_REACH_DP).toFloat()
        var gestureEdge = 0 // 0 none, 1 left, 2 right, 3 bottom
        var gestureFired = false
        var downX = 0f; var downY = 0f
        root.setOnTouchListener { v, event ->
            if (IslandTouchGuard.isObscured(event)) return@setOnTouchListener true
            if (!CoworkConnection.status.value.optBoolean("userControl")) return@setOnTouchListener true
            val info = LiveVideo.info.value ?: return@setOnTouchListener true
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
                    // Android gesture navigation: the side edges go back, the bottom edge goes home (the AI desktop).
                    if (!gestureFired && inward >= reach) { gestureFired = true; CoworkConnection.sendKey(if (gestureEdge == 3) "HOME" else "BACK") }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) gestureEdge = 0
                return@setOnTouchListener true
            }
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
        root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
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
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val shown = insets.isVisible(WindowInsets.Type.ime())
                imeBottom = if (shown) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
                // The keyboard was put away (its own hide key, or BACK): leave typing until the screen is tapped again.
                if (typing && imeShown && !shown) { keyboardDismissed = true; root.post { syncTyping() } }
                imeShown = shown
            }
            updateShift(); insets
        }
        fullImage = poster
        fullVideo = video
        fullView = root
        val params = fullScreenParams(real.width(), real.height(), overlayWindowType(), focusable = false)
        try {
            manager.addView(root, params)
            fullParams = params; fullManager = manager
        } catch (error: RuntimeException) {
            android.util.Log.w("DshIsland", "full screen failed", error)
            fullView = null; fullImage = null; fullVideo = null
            return
        }
        CoworkConnection.islandFullScreen.value = true
        // The colour frame is four thin non-touchable windows (pure decoration, not secure: they show no content).
        edgeGlow = EdgeGlow(context, density).also { it.show(manager, real.width(), real.height(), 120f, overlayWindowType()) }
        syncLive(true)
        // Fold the expanded island away at once: it must not sit on top of the full-screen picture.
        panel?.let { island -> if (expanded) { expanded = false; island.collapseNow() } }
        raiseIsland()
        syncTyping()
    }

    /** Fresh parameters for the full-screen window. A window re-added through another manager must not carry the token
     *  the previous manager wrote into its parameters, so moving between layers always builds new ones. */
    private fun fullScreenParams(width: Int, height: Int, type: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width, height, type,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "DSH 任务岛全屏预览"
        preferredRefreshRate = 120f // follow the highest rate the panel supports
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        softInputMode = if (focusable) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
    }

    /** Windows of one type stack in insertion order: put the island back above a newly added full-screen window. */
    private fun raiseIsland() {
        val manager = windowManager ?: return
        panel?.let { island ->
            val params = island.layoutParams
            if (params != null) runCatching {
                manager.removeViewImmediate(island)
                manager.addView(island, params)
            }
            lastRendered = null
            applySnapshot(island, IslandRuntime.store.snapshots.value)
        }
    }

    private fun closeFullScreen() {
        val view = fullView ?: return
        stopTyping(reattach = false)
        fullView = null; fullImage = null; fullVideo = null
        edgeGlow?.hide()
        edgeGlow = null
        runCatching { (fullManager ?: windowManager)?.removeViewImmediate(view) }
        fullManager = null; fullParams = null; keyboardDismissed = false
        CoworkConnection.islandFullScreen.value = false
        if (liveView == null) syncLive(false)
        panel?.interventionMode = false
    }

    /** Typing follows the broker: a focused input field on the owned display while the person is in control. */
    private fun syncTyping() {
        val root = fullView ?: return
        val field = CoworkConnection.field.value?.takeIf { CoworkConnection.status.value.optBoolean("userControl") }
        if (field == null) { keyboardDismissed = false; stopTyping(); return }
        if (keyboardDismissed) { stopTyping(); return }
        if (!typing && !startTyping(root)) return
        keyboard?.remote = field
        typingBar?.findViewWithTag<TextView>(TYPING_LABEL)?.text =
            if (field.password) "正在输入副屏的密码框：用本机键盘输入，AI 看不到" else "正在输入副屏的输入框：用本机键盘输入"
        updateShift()
    }

    /** The ordinary overlay layer, for the full-screen picture while the keyboard has to be drawn above it. */
    private fun typingManager(): WindowManager? = typingManager ?: run {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val displayContext = createDisplayContext(display)
        val context = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        context.getSystemService(WindowManager::class.java)?.also { typingManager = it }
    }

    private fun startTyping(root: FrameLayout): Boolean {
        val context = overlayContext ?: return false
        val params = fullParams ?: return false
        val current = fullManager ?: return false
        try {
            if (accessibilityLayer) {
                val target = typingManager() ?: error("no overlay window manager")
                current.removeViewImmediate(root)
                val typingParams = fullScreenParams(params.width, params.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, focusable = true)
                try { target.addView(root, typingParams); fullParams = typingParams; fullManager = target }
                catch (error: RuntimeException) {
                    val back = fullScreenParams(params.width, params.height, overlayWindowType(), focusable = false)
                    current.addView(root, back); fullParams = back; raiseIsland(); throw error
                }
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                current.updateViewLayout(root, params)
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("DshIsland", "typing window failed", error)
            fullParams?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED }
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
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { keyboardDismissed = true; syncTyping(); true } else false
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(preview) }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.argb(230, 28, 28, 30)); cornerRadius = dp(18).toFloat() }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(proxy, LinearLayout.LayoutParams(1, 1))
            addView(pillButton("收起键盘") { keyboardDismissed = true; syncTyping() }, LinearLayout.LayoutParams(dp(96), dp(40)))
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusId != 0) resources.getDimensionPixelSize(statusId) else dp(32)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = statusBar + dp(60); marginStart = dp(12); marginEnd = dp(12)
        })
        typingBar = bar; keyboard = proxy; typing = true; imeShown = false
        proxy.post { proxy.showKeyboard() }
        root.viewTreeObserver.addOnWindowFocusChangeListener(typingFocus)
        return true
    }

    /** Leave typing; [reattach] puts the picture back on the island's layer and makes it unfocusable again. */
    private fun stopTyping(reattach: Boolean = true) {
        if (!typing) return
        typing = false; imeShown = false; imeBottom = 0
        fullView?.viewTreeObserver?.removeOnWindowFocusChangeListener(typingFocus)
        keyboard?.hideKeyboard()
        typingBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        typingBar = null; keyboard = null
        updateShift()
        val root = fullView ?: return
        val params = fullParams ?: return
        if (!reattach) return
        runCatching {
            if (accessibilityLayer && fullManager !== windowManager) {
                fullManager?.removeViewImmediate(root)
                val back = fullScreenParams(params.width, params.height, overlayWindowType(), focusable = false)
                windowManager?.addView(root, back); fullParams = back; fullManager = windowManager
                raiseIsland()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                fullManager?.updateViewLayout(root, params)
            }
        }.onFailure { android.util.Log.w("DshIsland", "leaving typing failed", it) }
    }

    /** Keep the focused field above the keyboard by moving the picture up; touches follow the same offset. */
    private fun updateShift() {
        val root = fullView
        val field = CoworkConnection.field.value
        val info = LiveVideo.info.value
        var shift = 0f
        if (typing && root != null && field?.bounds != null && info != null && imeBottom > 0 && root.height > 0) {
            val bottom = field.bounds[3] / info.height.toFloat() * root.height
            shift = (bottom + dp(16) - (root.height - imeBottom)).coerceIn(0f, root.height * 0.6f)
        }
        contentShift = shift
        fullImage?.translationY = -shift; fullVideo?.translationY = -shift
    }
    private fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun cell() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
    /** The broker allows one viewer; the shared reference count keeps Activity and island from fighting for it. */
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
    private fun buildNotification(snapshot: IslandSnapshot): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.island_notification)
            .setContentTitle(if (snapshot.task == null) "DSH 任务岛 · 已开启待命" else "DSH 任务岛 · ${snapshot.statusLabel}")
            .setContentText(if (snapshot.task == null) "等待 Cowork 任务；点击隐藏可结束待命" else if (snapshot.pendingAction != null) "等待任务服务确认操作" else "点击返回应用查看；任务岛不会自动批准操作")
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.island_notification)
                .setContentTitle("DSH 任务岛").setContentText("解锁后查看任务状态").build())
        Intent(this, dev.dsh.pocket.cowork.CoworkActivity::class.java).let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            launch.putExtra(EXTRA_TASK_ID, snapshot.task?.taskId)
            launch.putExtra(EXTRA_REVISION, snapshot.task?.revision ?: -1)
            builder.setContentIntent(PendingIntent.getActivity(this, 10, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        snapshot.task?.let { task ->
            if (snapshot.backendConnected && snapshot.pendingAction == null) {
                if (IslandPolicy.allows(task, IslandAction.PAUSE)) builder.addAction(
                    Notification.Action.Builder(null, "暂停", actionIntent(task, IslandAction.PAUSE)).build(),
                )
                if (IslandPolicy.allows(task, IslandAction.STOP)) builder.addAction(
                    Notification.Action.Builder(null, "停止", actionIntent(task, IslandAction.STOP)).build(),
                )
            }
        }
        val dismiss = PendingIntent.getService(this, 11,
            Intent(this, IslandOverlayService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(Notification.Action.Builder(null, "隐藏", dismiss).build())
        return builder.build()
    }

    private fun actionIntent(task: IslandTaskUpdate, action: IslandAction): PendingIntent {
        val intent = Intent(this, IslandOverlayService::class.java).setAction(ACTION_CONTROL)
            .setData(Uri.Builder().scheme("dsh-island").authority("control").appendPath(task.taskId)
                .appendPath(task.execution?.instanceId.orEmpty()).appendPath(task.execution?.executionId.orEmpty())
                .appendPath(task.revision.toString()).appendPath(action.name).build())
            .putExtra(EXTRA_TASK_ID, task.taskId).putExtra(EXTRA_REVISION, task.revision)
            .putExtra("instanceId", task.execution?.instanceId).putExtra("executionId", task.execution?.executionId)
            .putExtra(EXTRA_CONTROL, action.name)
        return PendingIntent.getService(this, action.ordinal + 20, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dp(value: Int): Int = (value * (overlayContext ?: this).resources.displayMetrics.density).toInt()

    private fun removeOverlay() {
        closeFullScreen()
        syncLive(false)
        liveView = null
        panel?.let { view -> runCatching { windowManager?.removeViewImmediate(view) } }
        panel = null
        lastRendered = null
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        IslandRuntime.report(IslandRuntime.availability.value.copy(surface = IslandSurface.HIDDEN))
        super.onDestroy()
    }

    companion object {
        /** WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION (hidden API value). */
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040
        /** The full-screen picture must out-rank the island preview as the decoder's output. */
        private const val FULLSCREEN_SINK_PRIORITY = 10
        private const val EDGE_GESTURE_DP = 24
        private const val TYPING_LABEL = "typing-label"
        private const val GESTURE_REACH_DP = 56
        const val EXTRA_TASK_ID = "dev.dsh.pocket.island.TASK_ID"
        const val EXTRA_REVISION = "dev.dsh.pocket.island.REVISION"
        private const val EXTRA_CONTROL = "dev.dsh.pocket.island.CONTROL"
        private const val ACTION_CONTROL = "dev.dsh.pocket.island.CONTROL"
        private const val ACTION_DISMISS = "dev.dsh.pocket.island.DISMISS"
        private const val CHANNEL_ID = "dsh_task_island"
        private const val NOTIFICATION_ID = 7401
        private val DIM = Color.rgb(152, 152, 160)
    }
}







