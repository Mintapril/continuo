package dev.dsh.pocket.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A black pill that sits over the front camera, like the iPhone "Dynamic Island".
 * The shape is drawn (not laid out) so expansion/contraction is a pure canvas spring animation;
 * the window is only resized at the start/end of a transition (never per frame).
 */
internal class IslandPillView(
    context: Context,
    private val g: Geometry,
    /** Ask the host to resize the overlay window to exactly (width, height) pixels. */
    private val resizeWindow: (Int, Int) -> Unit,
) : FrameLayout(context) {

    data class Geometry(
        val centerY: Int,       // vertical centre of the camera / status-bar band, or just below the bar (pillCenterY)
        val collapsedW: Int,
        val collapsedH: Int,
        val expandedW: Int,
        val maxBody: Int,
        val density: Float,
        /** Bottom edge of the system status-bar window; it sits above app overlays and swallows touches. */
        val band: Int,
        /** Width of the physical camera hole (or a nominal one) in the middle of the pill. */
        val holeW: Int = 0,
    ) {
        /** Extra width per side when the pill is elongated for a running task (iPhone live-activity style). */
        val elong: Int get() = (68 * density).toInt()
        /** Width of the usable header area on each side of the camera in the elongated pill. */
        val side: Int get() = ((collapsedW - holeW) / 2).coerceAtLeast(0) + elong
        val top: Int get() = (centerY - collapsedH / 2).coerceAtLeast(0)
        /** Bottom of the invisible touch zone just under the status bar: the only part of the collapsed island that can receive touches. */
        val lipBottom: Int get() = band + (10 * density).toInt()
        /** Bottom of the collapsed touch target: the lip, or the whole pill when it sits below the status bar. */
        val hitBottom: Int get() = max(lipBottom + (8 * density).toInt(), top + collapsedH)
        /** Top of the expanded title row: the status-bar bottom, or just under the pill's own row when it sits below the bar. */
        val titleTop: Int get() = if (top >= band) top + collapsedH + (4 * density).toInt() else band
        val margin: Int get() = (18 * density).toInt()
        val bottomMargin: Int get() = (36 * density).toInt()
    }

    var onToggle: () -> Unit = {}
    var onOutside: () -> Unit = {}
    /** While a person is operating the owned display (full screen) one tap on the island hands control back. */
    var interventionMode = false
        set(value) { if (value && !field) interventionSince = SystemClock.uptimeMillis(); field = value }
    /** Called for the tap that ends the intervention. */
    var onDoubleTap: () -> Unit = {}
    private var interventionSince = 0L
    private fun tapped() {
        if (!interventionMode) { onToggle(); return }
        // The tail of the double tap that started the intervention must not end it again at once.
        if (SystemClock.uptimeMillis() - interventionSince < INTERVENTION_TAP_GUARD_MS) return
        onDoubleTap()
    }

    /** Snap shut without animation (intervention starts): the island must not cover the full-screen picture. */
    fun collapseNow() {
        pAnim?.cancel(); pAnim = null
        expanded = false; p = 0f
        headHit.visibility = GONE
        scroller.visibility = INVISIBLE
        clearBody()
        resizeWindow(collapsedWindowWidth(), collapsedWindowHeight())
        afterP()
    }

    private fun dp(v: Float) = v * g.density
    private fun dp(v: Int) = (v * g.density).toInt()

    // --- animated state ---
    private var p = 0f            // expansion progress, 1 = fully expanded (may overshoot)
    private var appear = 0f       // entrance/exit scale, 1 = visible
    private var pulse = 0f        // transient "bounce" on state change
    private var bodyH = 0f        // displayed body height (animated when content changes)
    private var bodyTarget = 0
    private var expanded = false
    private var clearBodyAfterCollapse = false
    private var pAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var appearAnim: ValueAnimator? = null
    private var bodyAnim: ValueAnimator? = null
    /** Glyph ticker: a fixed-cadence loop, never one update per vsync. A ValueAnimator ticks every frame, so
     *  the ~20 dp status glyph redrew the whole overlay at the display refresh rate (120 Hz here) for as long
     *  as a task ran: measured 119 fps / 68% of one core. This samples the same 1100 ms cycle at 20 Hz. */
    private var tickerOn = false
    private var tickerStart = 0L
    private val tickerStep = object : Runnable {
        override fun run() {
            if (!tickerOn) return
            val elapsed = (SystemClock.uptimeMillis() - tickerStart) % GLYPH_PERIOD_MS
            glyph.phase = elapsed / GLYPH_PERIOD_MS.toFloat()
            glyph.invalidate()
            postDelayed(this, GLYPH_STEP_MS)
        }
    }
    private var dismissed = false

    // --- views ---
    private val glyph = GlyphView(context)
    private fun headerText(size: Float, bold: Boolean, gravityValue: Int) = TextView(context).apply {
        textSize = size; setTextColor(Color.WHITE); if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        gravity = gravityValue; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** Elongated pill: app on the left of the camera, latest key action on the right. */
    private val leftLabel = headerText(10.5f, true, Gravity.CENTER_VERTICAL or Gravity.START)
    private val rightLabel = headerText(10.5f, false, Gravity.CENTER_VERTICAL or Gravity.START).apply { setTextColor(Color.rgb(200, 200, 206)) }
    /** Expanded title (below the status-bar band, where touches work). */
    private val title = headerText(15f, true, Gravity.CENTER_VERTICAL or Gravity.START)
    private val headHit = View(context).apply { isClickable = true; isFocusable = false; visibility = GONE }
    private var elong = 0f            // 0 = camera-sized, 1 = elongated
    private var elongTarget = false
    private var elongAnim: ValueAnimator? = null
    private val hit = View(context).apply { isClickable = true; isFocusable = true }
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        visibility = INVISIBLE
        alpha = 0f
    }
    private var content: View? = null
    private var lastHeader: String? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    /** Breathing colour rim shown while a task exists, drawn behind the black pill. */
    private val glowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowShader = SweepGradient(0f, 0f, GlowPalette.ring(), null)
    private val glowMatrix = Matrix()
    private val glowRect = RectF()
    private var glowWanted = false
    private var glowOn = false
    private val glowStep = object : Runnable {
        override fun run() {
            if (!glowOn) return
            invalidate()
            postDelayed(this, GLOW_STEP_MS)
        }
    }
    private val rect = RectF()
    private val path = Path()

    init {
        setWillNotDraw(false)
        filterTouchesWhenObscured = true
        clipChildren = false
        addView(hit); addView(scroller); addView(headHit); addView(glyph); addView(leftLabel); addView(rightLabel); addView(title)
        hit.setOnClickListener { tapped() }
headHit.setOnClickListener { tapped() }
        IslandTouchGuard.protect(hit); IslandTouchGuard.protect(headHit)
    }

    // ---------- public API ----------

    /** Extra top padding so body content starts below the status-bar band (which swallows touches). */
    fun bodyInset(): Int = max(0, g.band - (g.top + g.collapsedH + dp(4))) + dp(48)

    fun collapsedWindowWidth() = g.collapsedW + (if (elongTarget) 2 * g.elong else 0) + 2 * g.margin
    fun collapsedWindowHeight() = max(g.top + g.collapsedH + g.margin, g.lipBottom + dp(8))
    private fun expandedWindowWidth() = max(g.expandedW, g.collapsedW + 2 * g.elong) + 2 * g.margin
    private fun expandedWindowHeight() = max(g.top + g.collapsedH + dp(4) + bodyTarget + g.bottomMargin, collapsedWindowHeight())

    /** Called only when the state, app or a key action changed; a change gives the pill a small spring bounce. */
    fun setHeader(state: IslandTaskState?, connected: Boolean, elongated: Boolean, left: String, right: String, titleText: String, description: String) {
        glyph.state = if (connected) state else null
        leftLabel.text = left; rightLabel.text = right; title.text = titleText
        val tint = if (connected) Color.WHITE else Color.rgb(255, 196, 92)
        leftLabel.setTextColor(tint); title.setTextColor(tint)
        hit.contentDescription = description
        val key = "$left|$right"
        if (lastHeader != null && lastHeader != key) bounce()
        lastHeader = key
        setElongated(elongated)
        syncGlow()
        measureHeader(); positionHeader()
        refreshTicker()
    }

    private fun setElongated(value: Boolean) {
        if (elongTarget == value) return
        elongTarget = value
        if (value && !expanded) resizeWindow(collapsedWindowWidth(), collapsedWindowHeight())
        elongAnim?.cancel()
        val from = elong; val to = if (value) 1f else 0f
        elongAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SPRING_SECONDS * 1000).toLong()
            interpolator = if (value) EXPAND else COLLAPSE
            addUpdateListener { elong = from + (to - from) * it.animatedValue as Float; positionHeader(); invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    elongAnim = null; elong = to; positionHeader(); invalidate()
                    if (!value && !expanded) resizeWindow(collapsedWindowWidth(), collapsedWindowHeight())
                }
            })
            start()
        }
    }

    /** null clears the body (deferred until the collapse animation has finished). */
    fun setBody(view: View?) {
        if (view == null) {
            if (p <= 0.001f && !expanded) clearBody() else clearBodyAfterCollapse = true
            return
        }
        clearBodyAfterCollapse = false
        content = view
        scroller.removeAllViews()
        scroller.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bodyW = g.expandedW
        view.measure(MeasureSpec.makeMeasureSpec(bodyW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val target = min(view.measuredHeight, g.maxBody)
        val grow = target != bodyTarget
        bodyTarget = target
        if (expanded) {
            resizeWindow(expandedWindowWidth(), expandedWindowHeight())
            if (grow) animateBody(target.toFloat()) else bodyH = target.toFloat()
        } else bodyH = target.toFloat()
        requestLayout(); invalidate()
    }

    fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value && (pAnim != null || (if (value) p >= 1f else p <= 0f))) return
        expanded = value
        pAnim?.cancel()
        val target = if (value) 1f else 0f
        if (value) {
            resizeWindow(expandedWindowWidth(), expandedWindowHeight())
            scroller.visibility = VISIBLE
        }
        headHit.visibility = if (value) VISIBLE else GONE
        if (!animate) { p = target; afterP(); return }
        val from = p
        pAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SPRING_SECONDS * 1000).toLong()
            interpolator = if (value) EXPAND else COLLAPSE
            addUpdateListener { p = from + (target - from) * it.animatedValue as Float; afterP() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    pAnim = null; p = target; afterP()
                    if (!value) {
                        resizeWindow(collapsedWindowWidth(), collapsedWindowHeight())
                        scroller.visibility = INVISIBLE
                        if (clearBodyAfterCollapse) clearBody()
                    }
                }
            })
            start()
        }
    }

    /** Pop in from the camera with a spring. */
    fun appear() {
        appear = 0f
        appearAnim?.cancel()
        appearAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SPRING_SECONDS * 1000).toLong()
            interpolator = EXPAND
            addUpdateListener { appear = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Shrink back into the camera, then run [done]. */
    fun dismiss(done: () -> Unit) {
        if (dismissed) return
        dismissed = true
        pAnim?.cancel(); appearAnim?.cancel()
        val from = appear
        appearAnim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 260
            interpolator = android.view.animation.AccelerateInterpolator(1.6f)
            addUpdateListener { appear = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { done() } })
            start()
        }
    }

    // ---------- animation helpers ----------

    private fun bounce() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SPRING_SECONDS * 1000).toLong()
            interpolator = BOUNCE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate(); positionHeader() }
            start()
        }
    }

    private fun animateBody(target: Float) {
        bodyAnim?.cancel()
        val from = bodyH
        bodyAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SPRING_SECONDS * 1000).toLong()
            interpolator = EXPAND
            addUpdateListener { bodyH = from + (target - from) * it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun afterP() {
        val a = ((p - 0.28f) / 0.55f).coerceIn(0f, 1f)
        scroller.alpha = a
        scroller.visibility = if (a > 0.01f || expanded) VISIBLE else INVISIBLE
        positionHeader(); invalidate()
    }

    private fun clearBody() {
        scroller.removeAllViews(); content = null; bodyTarget = 0; bodyH = 0f; clearBodyAfterCollapse = false
    }

    private fun refreshTicker() {
        val animated = glyph.animates && isAttachedToWindow && windowVisibility == VISIBLE
        if (animated && !tickerOn) {
            tickerOn = true
            tickerStart = SystemClock.uptimeMillis()
            postDelayed(tickerStep, GLYPH_STEP_MS)
        } else if (!animated) {
            tickerOn = false
            removeCallbacks(tickerStep)
            glyph.invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshTicker(); syncGlow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); refreshTicker(); syncGlow() }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        listOf(pAnim, pulseAnim, appearAnim, bodyAnim, elongAnim).forEach { it?.cancel() }
        tickerOn = false
        removeCallbacks(tickerStep)
        glowOn = false
        removeCallbacks(glowStep)
    }

    // ---------- geometry ----------

    private fun shapeW(): Float {
        val closed = g.collapsedW + 2 * g.elong * elong
        val base = closed + (max(g.expandedW.toFloat(), closed) - closed) * p + pulse * dp(14f)
        return max(base, g.collapsedW * 0.9f)
    }

    private fun shapeH(): Float {
        val open = max(p, 0f)
        return g.collapsedH + pulse * dp(3f) + (dp(4f) + bodyH) * open
    }

    private fun radius(h: Float): Float {
        val t = p.coerceIn(0f, 1f)
        val r = (g.collapsedH / 2f) + (dp(30f) - g.collapsedH / 2f) * t
        return min(r, h / 2f)
    }

    private fun measureHeader() {
        val h = MeasureSpec.makeMeasureSpec(g.collapsedH, MeasureSpec.EXACTLY)
        glyph.measure(MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY), h)
        leftLabel.measure(MeasureSpec.makeMeasureSpec(max(g.side - dp(11) - dp(24) - dp(6), dp(20)), MeasureSpec.AT_MOST), h)
        rightLabel.measure(MeasureSpec.makeMeasureSpec(min(g.side - dp(6), dp(84)), MeasureSpec.AT_MOST), h)
        title.measure(MeasureSpec.makeMeasureSpec(max(g.expandedW - dp(60) - dp(16), dp(40)), MeasureSpec.EXACTLY), h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        measureHeader()
        hit.measure(MeasureSpec.makeMeasureSpec(dp(104), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(g.hitBottom - g.top, 1), MeasureSpec.EXACTLY))
        scroller.measure(MeasureSpec.makeMeasureSpec(g.expandedW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(bodyTarget, 1), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val hy = g.top
        val hitHalf = dp(52)
        hit.layout(w / 2 - hitHalf, hy, w / 2 + hitHalf, g.hitBottom)
        glyph.layout(0, hy, glyph.measuredWidth, hy + g.collapsedH)
        leftLabel.layout(0, hy, leftLabel.measuredWidth, hy + g.collapsedH)
        rightLabel.layout(0, hy, rightLabel.measuredWidth, hy + g.collapsedH)
        title.layout(0, hy, title.measuredWidth, hy + g.collapsedH)
        headHit.layout(sx0(w), g.titleTop, sx0(w) + g.expandedW, g.titleTop + dp(46))
        val sx = (w - g.expandedW) / 2
        val sy = hy + g.collapsedH + dp(4)
        scroller.layout(sx, sy, sx + g.expandedW, sy + max(bodyTarget, 1))
        positionHeader()
    }

    private fun sx0(w: Int) = (w - g.expandedW) / 2

    private fun positionHeader() {
        if (width == 0) return
        val cx = width / 2f
        val w = shapeW()
        val side = dp(11f)
        val open = p.coerceIn(0f, 1.15f)
        val left = cx - w / 2f
        // Collapsed: glyph left / label right of the camera. Expanded: both move below the status bar, left-aligned.
        val gx0 = left + side
        val gx1 = left + dp(26f)
        val lx0 = gx0 + dp(24f)
        val lx1 = gx1 + dp(26f)
        val gX = gx0 + (gx1 - gx0) * open
        glyph.translationX = gX
        leftLabel.translationX = lx0
        rightLabel.translationX = cx + g.holeW / 2f + dp(6f)
        title.translationX = lx1
        val dy = g.titleTop + dp(24f) - (g.top + g.collapsedH / 2f)
        val ty = max(dy, 0f) * open
        glyph.translationY = ty
        title.translationY = max(dy, 0f)
        val fade = (1f - open * 3f).coerceIn(0f, 1f)
        leftLabel.alpha = elong * fade; rightLabel.alpha = elong * fade
        title.alpha = ((open - 0.35f) / 0.4f).coerceIn(0f, 1f)
        title.visibility = if (title.alpha > 0.01f) VISIBLE else INVISIBLE
        leftLabel.visibility = if (leftLabel.alpha > 0.01f) VISIBLE else INVISIBLE
        rightLabel.visibility = if (rightLabel.alpha > 0.01f) VISIBLE else INVISIBLE
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (appear <= 0.002f) return
        val w = shapeW(); val h = shapeH()
        val cx = width / 2f
        val left = cx - w / 2f
        val top = g.top.toFloat()
        rect.set(left, top, left + w, top + h)
        val rad = radius(h)
        canvas.save()
        val s = (0.28f + 0.72f * appear)
        canvas.scale(s, s, cx, top + g.collapsedH / 2f)
        if (elong > 0.01f && glowWanted) drawGlow(canvas, left, top, w, h, rad, cx)
        paint.alpha = (appear * 4f).coerceIn(0f, 1f).let { (it * 255).toInt() }
        canvas.drawRoundRect(rect, rad, rad, paint)
        // No visible tab: the touch zone below the status-bar band (Geometry.lipBottom) is invisible; see the hit view.
        rect.set(left, top, left + w, top + h)
        path.reset(); path.addRoundRect(rect, rad, rad, Path.Direction.CW)
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private fun syncGlow() {
        glowWanted = elongTarget
        val run = elongTarget && isAttachedToWindow && windowVisibility == VISIBLE
        if (run && !glowOn) { glowOn = true; removeCallbacks(glowStep); post(glowStep) }
        else if (!run && glowOn) { glowOn = false; removeCallbacks(glowStep) }
        invalidate()
    }

    /**
     * A soft halo and a bright rim in breathing colour behind the black pill. A SweepGradient shader rotated by a
     * matrix and stroked along the pill outline: a handful of vertices per frame, no layers, no clipping, drawn at
     * [GLOW_STEP_MS] (about 30 Hz) rather than every vsync, which is what keeps the always-on effect cheap.
     */
    private fun drawGlow(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, rad: Float, cx: Float) {
        val cy = top + h / 2f
        val fade = elong.coerceIn(0f, 1f) * (appear * 4f).coerceIn(0f, 1f)
        // Breathing: a fixed colour ring whose strength swells and fades slowly; sampled at ~15 Hz.
        val breath = GlowPalette.breath(SystemClock.uptimeMillis())
        glowMatrix.setTranslate(cx, cy)
        glowShader.setLocalMatrix(glowMatrix)
        val rimWidth = dp(2.5f)
        val haloWidth = dp(9f)
        glowRim.shader = glowShader; glowRim.strokeWidth = rimWidth; glowRim.alpha = (fade * (0.45f + 0.55f * breath) * 255).toInt()
        glowHalo.shader = glowShader; glowHalo.strokeWidth = haloWidth; glowHalo.alpha = (fade * (0.12f + 0.38f * breath) * 255).toInt()
        glowRect.set(left - rimWidth / 2f, top - rimWidth / 2f, left + w + rimWidth / 2f, top + h + rimWidth / 2f)
        val halo = RectF(glowRect).apply { inset(-haloWidth / 2f + rimWidth / 2f, -haloWidth / 2f + rimWidth / 2f) }
        canvas.drawRoundRect(halo, rad + haloWidth / 2f, rad + haloWidth / 2f, glowHalo)
        canvas.drawRoundRect(glowRect, rad + rimWidth / 2f, rad + rimWidth / 2f, glowRim)
    }
    // ---------- touch ----------

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (IslandTouchGuard.isObscured(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && expanded) {
            val w = shapeW(); val h = shapeH(); val cx = width / 2f
            val inside = event.x >= cx - w / 2f && event.x <= cx + w / 2f && event.y >= g.top && event.y <= g.top + h
            if (!inside) { onOutside(); return true }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && expanded) onOutside()
        return super.onTouchEvent(event)
    }

    // ---------- status glyph ----------

    private class GlyphView(context: Context) : View(context) {
        var state: IslandTaskState? = null
            set(value) { field = value; invalidate() }
        var phase = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val animates: Boolean get() = when (state) {
            IslandTaskState.RUNNING, IslandTaskState.WAITING, IslandTaskState.APPROVAL,
            IslandTaskState.PAUSING, IslandTaskState.STOPPING, null -> true
            else -> false
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f
            val s = state
            when (s) {
                IslandTaskState.RUNNING -> {
                    p.color = Color.rgb(48, 209, 88)
                    val bars = 4; val bw = 2.6f * d; val gap = 2.2f * d
                    val total = bars * bw + (bars - 1) * gap
                    for (i in 0 until bars) {
                        val a = 0.5f + 0.5f * sin(2 * PI * (phase + i * 0.19f)).toFloat()
                        val bh = (4f + 10f * a) * d
                        val x = cx - total / 2f + i * (bw + gap)
                        c.drawRoundRect(x, cy - bh / 2f, x + bw, cy + bh / 2f, bw / 2f, bw / 2f, p)
                    }
                }
                null -> { // connection lost: slow blink
                    p.color = Color.rgb(255, 196, 92)
                    p.alpha = (110 + 145 * (0.5f + 0.5f * sin(2 * PI * phase).toFloat())).toInt()
                    c.drawCircle(cx, cy, 4.2f * d, p)
                }
                else -> {
                    val color = when (s) {
                        IslandTaskState.WAITING, IslandTaskState.APPROVAL -> Color.rgb(255, 214, 10)
                        IslandTaskState.PAUSING, IslandTaskState.STOPPING, IslandTaskState.PAUSED -> Color.rgb(255, 159, 10)
                        IslandTaskState.ERROR -> Color.rgb(255, 69, 58)
                        IslandTaskState.COMPLETED -> Color.rgb(48, 209, 88)
                        else -> Color.rgb(142, 142, 147)
                    }
                    p.color = color
                    val breathing = s == IslandTaskState.WAITING || s == IslandTaskState.APPROVAL ||
                        s == IslandTaskState.PAUSING || s == IslandTaskState.STOPPING
                    val k = if (breathing) 1f + 0.22f * sin(2 * PI * phase).toFloat() else 1f
                    p.alpha = 255
                    c.drawCircle(cx, cy, 4.6f * d * k, p)
                    if (breathing) {
                        p.alpha = (70 * (1f - (k - 0.78f) / 0.44f)).toInt().coerceIn(0, 90)
                        c.drawCircle(cx, cy, 7.2f * d * k, p)
                    }
                }
            }
        }
    }

    private class Spring(private val damping: Float, private val freqHz: Float) : TimeInterpolator {
        override fun getInterpolation(f: Float): Float {
            if (f >= 1f) return 1f
            if (f <= 0f) return 0f
            val t = f * SPRING_SECONDS
            val w0 = (2 * PI * freqHz).toFloat()
            val wd = w0 * sqrt(1f - damping * damping)
            val decay = exp(-damping * w0 * t)
            return 1f - decay * (cos(wd * t) + (damping * w0 / wd) * sin(wd * t))
        }
    }

    private class Bounce : TimeInterpolator {
        private val spring = Spring(0.45f, 3.4f)
        override fun getInterpolation(f: Float): Float = 1f - spring.getInterpolation(f)
    }

    companion object {
        private const val SPRING_SECONDS = 0.8f
        /** One glyph cycle; the replaced ValueAnimator used the same 1100 ms duration. */
        private const val GLYPH_PERIOD_MS = 1100L
        /** Glyph sampling cadence: 20 Hz instead of one redraw per vsync. */
        private const val GLYPH_STEP_MS = 50L
        /** One turn of the island's colour rim. */
        private const val GLOW_STEP_MS = GlowPalette.STEP_MS
        private const val INTERVENTION_TAP_GUARD_MS = 700L
        private val EXPAND = Spring(0.62f, 3.0f)   // visible overshoot, like iOS
        private val COLLAPSE = Spring(0.78f, 3.4f) // tighter on the way back
        private val BOUNCE = Bounce()
    }
}




