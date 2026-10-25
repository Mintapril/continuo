package dev.dsh.pocket.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.avatar.Stickers
import dev.dsh.pocket.cowork.StatusCard.Mood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos

/** How long one cycle of each mood's motion takes (the design page's CSS durations). */
private fun period(mood: Mood): Int = when (mood) {
    Mood.IDLE -> 3400; Mood.WORK -> 1600; Mood.LISTEN -> 2600; Mood.CALL -> 1400; Mood.ACK -> 1300
    Mood.HAPPY -> 1100; Mood.SLEEP -> 4800; Mood.EAT -> 700
}

/** 0 → 1 → 0 over one cycle, eased like CSS ease-in-out. */
private fun swell(t: Float) = ((1 - cos(2 * PI * t)) / 2).toFloat()

/** Piecewise-linear keyframes: [frames] are (time, value) pairs sorted by time, from 0 to 1. */
private fun keyed(t: Float, vararg frames: Pair<Float, Float>): Float {
    for (i in 1 until frames.size) {
        val (t0, v0) = frames[i - 1]; val (t1, v1) = frames[i]
        if (t <= t1) return v0 + (v1 - v0) * ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
    }
    return frames.last().second
}

private data class Motion(val sx: Float = 1f, val sy: Float = 1f, val ty: Float = 0f, val rot: Float = 0f)

/** The whole sticker's motion per mood; [ty] is a fraction of its height. Mirrors avatars.css. */
private fun motion(mood: Mood, t: Float): Motion {
    val w = swell(t)
    return when (mood) {
        Mood.IDLE -> Motion(1 + 0.012f * w, 1 + 0.025f * w)
        Mood.WORK -> Motion(ty = -0.012f * w)
        Mood.LISTEN -> Motion(rot = -1.5f - 3f * w)
        Mood.CALL -> Motion(rot = keyed(t, 0f to 0f, .1f to -3f, .2f to 3f, .3f to -2f, .4f to 1.5f, .5f to 0f, 1f to 0f))
        Mood.ACK -> Motion(keyed(t, 0f to 1f, .25f to 1.01f, .45f to 1.03f, .6f to 1f, 1f to 1f), keyed(t, 0f to 1f, .25f to .99f, .45f to .96f, .6f to 1f, 1f to 1f),
            keyed(t, 0f to 0f, .25f to -.03f, .45f to 0f, 1f to 0f))
        Mood.HAPPY -> Motion(keyed(t, 0f to 1f, .35f to .98f, .6f to 1.04f, .75f to 1f, 1f to 1f), keyed(t, 0f to 1f, .35f to 1.03f, .6f to .95f, .75f to 1f, 1f to 1f),
            keyed(t, 0f to 0f, .35f to -.05f, .6f to 0f, 1f to 0f))
        Mood.SLEEP -> Motion(1 + 0.02f * w, 1 + 0.035f * w, 0.005f * w)
        Mood.EAT -> Motion(1 + 0.015f * w, 1 - 0.015f * w, 0.008f * w)
    }
}

private val Yellow = Color(0xFFFFC531); private val Blue = Color(0xFF4AA8FF); private val Rose = Color(0xFFFF5F78)
private val Note = Color(0xFF5AA7E8); private val Wave = Color(0xFFFF6B7A); private val Zz = Color(0xFF2A3A8A)

/** The effects around the sticker (hint lines, notes, drops, sparkles, hearts, z, +), drawn here so they move. */
private fun DrawScope.effects(mood: Mood, t: Float, tick: Float) {
    val k = size.minDimension / 100f
    fun at(x: Float, y: Float) = Offset(x * k, y * k)
    val pulse = 0.35f + 0.65f * swell(tick)
    fun lines(x: Float, y: Float) = translate(x * k, y * k) {
        rotate(-20f, Offset.Zero) {
            for ((a, b) in listOf(Offset(-4f, -12f) to Offset(-6f, -20f), Offset(4f, -11f) to Offset(8f, -18f), Offset(10f, -4f) to Offset(17f, -7f)))
                drawLine(Yellow.copy(alpha = pulse), a * (k / 1.6f), b * (k / 1.6f), strokeWidth = 4.2f * k / 1.6f, cap = StrokeCap.Round)
        }
    }
    fun sparkle(x: Float, y: Float, scale: Float, color: Color, alpha: Float) {
        val r = 10f * k / 1.6f * scale
        val p = Path().apply {
            moveTo(0f, -r); quadraticTo(r * .2f, -r * .2f, r, 0f); quadraticTo(r * .2f, r * .2f, 0f, r)
            quadraticTo(-r * .2f, r * .2f, -r, 0f); quadraticTo(-r * .2f, -r * .2f, 0f, -r); close()
        }
        translate(x * k, y * k) { drawPath(p, color.copy(alpha = alpha)) }
    }
    fun heart(x: Float, y: Float, scale: Float, phase: Float) {
        val life = (phase % 1f)
        val alpha = if (life < .2f) life / .2f else 1f - (life - .2f) / .8f
        val r = 9f * k / 1.6f * scale * (0.6f + 0.45f * life.coerceAtMost(.2f) / .2f)
        val rise = (10f - 36f * life) * k / 1.6f
        val p = Path().apply {
            moveTo(0f, -.3f * r); cubicTo(-.2f * r, -.8f * r, -r, -.7f * r, -r, -.1f * r); cubicTo(-r, .4f * r, -.3f * r, .7f * r, 0f, r)
            cubicTo(.3f * r, .7f * r, r, .4f * r, r, -.1f * r); cubicTo(r, -.7f * r, .2f * r, -.8f * r, 0f, -.3f * r); close()
        }
        translate(x * k, y * k + rise) { drawPath(p, Rose.copy(alpha = alpha.coerceIn(0f, 1f))) }
    }
    fun text(value: String, x: Float, y: Float, sizeUnits: Float, color: Color, phase: Float) {
        val life = phase % 1f
        val alpha = if (life < .25f) life / .25f else 1f - (life - .25f) / .75f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb(); textSize = sizeUnits * k; isFakeBoldText = true
        }
        drawContext.canvas.nativeCanvas.drawText(value, x * k, y * k + (8f - 26f * life) * k / 1.6f, paint)
    }
    when (mood) {
        Mood.WORK -> lines(14f, 30f)
        Mood.LISTEN -> { lines(14f, 30f); text("♪", 84f, 24f, 12f, Note, tick) }
        Mood.CALL -> {
            val fall = (t * 2f) % 1f
            for ((x, y, s) in listOf(Triple(13f, 22f, 1.1f), Triple(20f, 15f, .8f))) {
                val r = 6f * k / 1.6f * s
                val p = Path().apply { moveTo(0f, -2 * r); cubicTo(-r, -r / 3, -r, 2 * r / 3, 0f, r); cubicTo(r, 2 * r / 3, r, -r / 3, 0f, -2 * r); close() }
                translate(x * k, y * k + fall * 14f * k / 1.6f) { drawPath(p, Blue.copy(alpha = 1f - fall)) }
            }
            val wave = Stroke(2.4f * k, cap = StrokeCap.Round)
            val a = Path().apply { moveTo(9f * k, 45f * k); cubicTo(5f * k, 52f * k, 5f * k, 60f * k, 9f * k, 66f * k) }
            val b = Path().apply { moveTo(4f * k, 49f * k); cubicTo(1f * k, 54f * k, 1f * k, 59f * k, 4f * k, 63f * k) }
            drawPath(a, Wave.copy(alpha = pulse), style = wave); drawPath(b, Wave.copy(alpha = pulse), style = Stroke(2f * k, cap = StrokeCap.Round))
        }
        Mood.ACK -> { sparkle(12f, 30f, 1.1f, Yellow, pulse); sparkle(88f, 26f, .8f, Yellow, pulse); sparkle(90f, 62f, .6f, Yellow, pulse) }
        Mood.HAPPY -> { heart(12f, 34f, 1.3f, tick); heart(88f, 22f, 1.2f, tick + .33f); heart(90f, 52f, .95f, tick + .66f) }
        Mood.SLEEP -> { text("Z", 74f, 30f, 11f, Zz, tick); text("z", 82f, 21f, 8f, Zz, tick + .23f); text("z", 88f, 14f, 6f, Zz, tick + .46f) }
        Mood.EAT -> for ((x, y, s) in listOf(Triple(10f, 46f, 1.2f), Triple(16f, 38f, .8f))) {
            val r = 6f * k / 1.6f * s
            drawLine(Rose.copy(alpha = pulse), at(x, y) - Offset(r, 0f), at(x, y) + Offset(r, 0f), strokeWidth = 3.2f * k / 1.6f, cap = StrokeCap.Round)
            drawLine(Rose.copy(alpha = pulse), at(x, y) - Offset(0f, r), at(x, y) + Offset(0f, r), strokeWidth = 3.2f * k / 1.6f, cap = StrokeCap.Round)
        }
        Mood.IDLE -> {}
    }
}

/**
 * Its face: the chibi sticker of [avatar] in the state for [mood], breathing, bobbing or hopping as the design page
 * does, blinking now and then while idle, with its effects around it. [head] crops to the head on a soft disc (small
 * round places); [animate] false (or the system's reduced motion) keeps it still.
 */
@Composable fun Avatar(avatar: String, mood: Mood, name: String, modifier: Modifier = Modifier, size: Dp = 48.dp, head: Boolean = false, animate: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(16, 1024)
    val moving = animate && !reduceMotion()
    val disc = (if (Pocket.dark) Color(0xFF2E3238) else Color(0xFFE8ECF2)).toArgb()
    // The last face stays up while the next one renders (a new mood never flashes the empty disc); they cross-fade.
    var face by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatar, mood, px, head, disc) {
        withContext(Dispatchers.Default) { Stickers.render(context, avatar, mood.key, px, head, background = if (head) disc else null)?.asImageBitmap() }?.let { face = it }
    }
    val blink by produceState<ImageBitmap?>(null, avatar, mood, px, head, moving) {
        value = if (mood != Mood.IDLE || !moving) null
            else withContext(Dispatchers.Default) { Stickers.render(context, avatar, "blink", px, head, background = if (head) disc else null)?.asImageBitmap() }
    }
    Box(modifier.size(size).semantics { contentDescription = name }) {
        val image = face
        if (image == null) { Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(disc).copy(alpha = 0.6f))); return@Box }
        if (!moving) {
            Crossfade(image, Modifier.fillMaxSize(), animationSpec = tween(200), label = "face") { Image(it, null, Modifier.fillMaxSize()) }
            if (!head) Canvas(Modifier.fillMaxSize()) { effects(mood, 0f, 0.5f) }
            return@Box
        }
        val transition = rememberInfiniteTransition(label = "avatar")
        val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(period(mood), easing = LinearEasing), RepeatMode.Restart), label = "motion")
        val tick by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mood == Mood.HAPPY) 2600 else if (mood == Mood.SLEEP) 3000 else 1100, easing = LinearEasing), RepeatMode.Restart), label = "fx")
        val wink by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "blink")
        val m = motion(mood, t)
        Crossfade(image, Modifier.fillMaxSize(), animationSpec = tween(220), label = "face") { drawn ->
            val shown = if (drawn === image) blink?.takeIf { wink in 0.92f..0.96f } ?: drawn else drawn
            Image(shown, null, Modifier.fillMaxSize().graphicsLayer {
                if (!head) {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = m.sx; scaleY = m.sy; rotationZ = m.rot; translationY = m.ty * this.size.height
                }
            })
        }
        if (!head) Canvas(Modifier.fillMaxSize()) { effects(mood, t, tick) }
    }
}
