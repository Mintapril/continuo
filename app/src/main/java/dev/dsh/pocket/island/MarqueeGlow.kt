package dev.dsh.pocket.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The shared colours of the breathing glow: the intervention frame and the task island use the same palette. */
internal object GlowPalette {
    /** Calm aurora: cyan, blue, violet, rose. Cool tones read as "working quietly" and stay easy on the eyes. */
    val colors = intArrayOf(
        0xFF35D6F0.toInt(), 0xFF4C8DFF.toInt(), 0xFFA45CFF.toInt(), 0xFFFF6FB5.toInt(),
    )
    /** Breathing is sampled at about 15 Hz: slow swell, so more frames would only cost power. */
    const val STEP_MS = 66L
    const val BREATH_MS = 4200L

    /** 0..1 smooth in/out swell. */
    fun breath(nowMs: Long): Float {
        val x = (nowMs % BREATH_MS).toFloat() / BREATH_MS
        return (1f - kotlin.math.cos(2.0 * Math.PI * x).toFloat()) / 2f
    }

    /** Gradient stops for a SweepGradient: the palette sampled twice per colour, closed back to the first stop. */
    fun ring(): IntArray {
        val n = colors.size * 2
        return IntArray(n + 1) { if (it == n) at(0f) else at(it.toFloat() / n) }
    }

    /** Cyclic colour at [t] in [0,1): piecewise-linear with a smoothstep so hues blend without visible joints. */
    fun at(t: Float): Int {
        val n = colors.size
        val pos = (t - floor(t)) * n
        val i = min(pos.toInt(), n - 1)
        val f = pos - i
        val s = f * f * (3f - 2f * f)
        val a = colors[i]; val b = colors[(i + 1) % n]
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * s)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Breathing colour frame around the screen edges while a person operates the owned display. Four thin windows (one per
 * edge, not one full-screen window: a 1080x2414 transparent buffer would be composited every frame). Each strip shows a
 * STATIC pre-rendered gradient whose colour runs continuously around the whole perimeter; the "breath" is only the
 * strip's alpha, swelling and fading on a hardware layer at ~15 Hz, so the app draws nothing per frame and the
 * compositor just re-blends four small layers. The windows never take touches.
 */
internal class EdgeGlow(private val context: Context, private val density: Float) {
    private val depth = (26 * density).toInt()
    private val views = ArrayList<Strip>()
    private var manager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun profile(distance: Float): Float {
        val d = distance / density
        val core = 0.9f * exp(-d / 1.6f)
        val glow = 0.6f * exp(-d / 7f)
        val fade = (1f - distance / depth).coerceIn(0f, 1f)
        return min(1f, core + glow) * fade
    }

    /** edge: 0 top, 1 right, 2 bottom, 3 left; [start] is the perimeter offset of the strip's first pixel. */
    private fun tile(edge: Int, length: Int, start: Int, perimeter: Int): Bitmap {
        val horizontal = edge == 0 || edge == 2
        val w = if (horizontal) length else depth
        val h = if (horizontal) depth else length
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val along = if (horizontal) x else y
            val across = if (horizontal) y else x
            val distance = when (edge) { 0, 3 -> across.toFloat(); else -> (depth - 1 - across).toFloat() }
            // top and right run with the perimeter, bottom and left against it: one closed clockwise ring of colour.
            val pos = if (edge == 0 || edge == 1) start + along else start + (length - 1 - along)
            val rgb = GlowPalette.at(pos / perimeter.toFloat())
            val a = (profile(distance) * 255f).toInt().coerceIn(0, 255)
            pixels[y * w + x] = (a shl 24) or (rgb and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private inner class Strip(val bitmap: Bitmap) : View(context) {
        private val paint = Paint().apply { isFilterBitmap = true }
        init {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setLayerType(LAYER_TYPE_HARDWARE, null)
            alpha = 0f
        }
        override fun onDraw(canvas: Canvas) { canvas.drawBitmap(bitmap, 0f, 0f, paint) }
    }

    private var startedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (views.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            val fadeIn = ((now - startedAt) / 320f).coerceIn(0f, 1f)
            val a = fadeIn * (0.35f + 0.65f * GlowPalette.breath(now))
            for (v in views) v.alpha = a
            handler.postDelayed(this, GlowPalette.STEP_MS)
        }
    }

    /** Adds the four strip windows around a [width] x [height] screen. Returns false if none could be shown. */
    fun show(
        wm: WindowManager,
        width: Int,
        height: Int,
        refreshRate: Float,
        type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    ): Boolean {
        hide()
        manager = wm
        val side = max(0, height - 2 * depth)
        val perimeter = 2 * width + 2 * side + 4 * depth
        // edge, window x, y, w, h, strip length, perimeter offset of the first pixel
        val specs = listOf(
            intArrayOf(0, 0, 0, width, depth, width, 0),
            intArrayOf(1, width - depth, depth, depth, side, side, width),
            intArrayOf(2, 0, height - depth, width, depth, width, width + side + depth),
            intArrayOf(3, 0, depth, depth, side, side, 2 * width + side + 2 * depth),
        )
        for (s in specs) {
            val strip = Strip(tile(s[0], s[5], s[6], perimeter))
            val params = WindowManager.LayoutParams(
                s[3], s[4], type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = s[1]; y = s[2]
                title = "DSH 介入边框"
                preferredRefreshRate = refreshRate
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try { wm.addView(strip, params); views.add(strip) } catch (_: RuntimeException) { /* BadToken/display gone: show what could be added */ }
        }
        if (views.isNotEmpty()) { startedAt = SystemClock.uptimeMillis(); handler.removeCallbacks(tick); handler.post(tick) }
        return views.isNotEmpty()
    }

    fun hide() {
        handler.removeCallbacks(tick)
        val wm = manager
        for (v in views) { if (wm != null) runCatching { wm.removeViewImmediate(v) } }
        views.clear()
    }
}