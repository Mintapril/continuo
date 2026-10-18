package dev.dsh.pocket.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val Pocket: PocketColors @Composable @ReadOnlyComposable get() = LocalPocketColors.current
val Fonts: PocketFonts @Composable @ReadOnlyComposable get() = LocalPocketFonts.current

/** True when the user disabled animations (Settings → animator duration scale 0): cursors stay static. */
@Composable fun reduceMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Continuo cursor alpha: blink (executing), breathe (planning) or steady. Never reaches 0. */
enum class CursorMode { STEADY, BLINK, BREATHE }
@Composable fun cursorAlpha(mode: CursorMode): Float {
    if (mode == CursorMode.STEADY || reduceMotion()) return 1f
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by if (mode == CursorMode.BLINK) transition.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 1200; 1f at 660; .15f at 780; .15f at 1080; 1f at 1200
    }), label = "blink") else transition.animateFloat(1f, .35f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    return alpha
}

/** The Continuo cursor: a bar 1:6.7 with ~17% radius (CHANGES.md R2). Wait indicator, status mark and caret all in one. */
@Composable fun ContinuoCursor(modifier: Modifier = Modifier, height: Dp = 14.dp, color: Color = Pocket.accent, mode: CursorMode = CursorMode.BLINK) {
    val alpha = cursorAlpha(mode)
    val width = height / 6.7f
    Canvas(modifier.size(width, height)) {
        drawRoundRect(color.copy(alpha = alpha), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .17f))
    }
}

/**
 * Brand mark (R2 "合环", C centre (47,50), viewBox 17 20 58.5 60): open C (ink) + cursor 6×40 at x 69.5, y 30 (accent).
 * [cursor] lets callers recolour the cursor to a state colour.
 */
@Composable fun ContinuoMark(modifier: Modifier = Modifier, size: Dp = 32.dp, color: Color = Pocket.ink, cursor: Color = Pocket.accent, mode: CursorMode = CursorMode.STEADY) {
    val alpha = cursorAlpha(mode)
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 60f
        val ox = (this.size.width - 58.5f * k) / 2f - 17f * k
        val oy = (this.size.height - 60f * k) / 2f - 20f * k
        fun x(v: Float) = ox + v * k
        fun y(v: Float) = oy + v * k
        val c = Path().apply {
            moveTo(x(66.28f), y(27.02f))
            arcTo(androidx.compose.ui.geometry.Rect(x(17f), y(20f), x(77f), y(80f)), -50f, -260f, false)
            lineTo(x(58.57f), y(63.79f))
            arcTo(androidx.compose.ui.geometry.Rect(x(29f), y(32f), x(65f), y(68f)), 50f, 260f, false)
            close()
        }
        drawPath(c, color)
        drawRoundRect(cursor.copy(alpha = alpha), androidx.compose.ui.geometry.Offset(x(69.5f), y(30f)), androidx.compose.ui.geometry.Size(6f * k, 40f * k),
            androidx.compose.ui.geometry.CornerRadius(1f * k))
    }
}
/** Thin 1.6-stroke outline icons drawn from SVG path data (24×24 viewport). */
object Glyph {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(PathParser().parsePathString(d).toNodes(), fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
    val Menu = icon("menu", "M4 8h16", "M4 16h10")
    val Plus = icon("plus", "M12 5v14", "M5 12h14")
    val Close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val ArrowUp = icon("up", "M12 19V5", "M6 11l6-6 6 6")
    val ArrowLeft = icon("left", "M19 12H5", "M11 6l-6 6 6 6")
    val ArrowDown = icon("down", "M12 5v14", "M6 13l6 6 6-6")
    val ChevronDown = icon("chevD", "M7 10l5 5 5-5")
    val ChevronRight = icon("chevR", "M10 7l5 5-5 5")
    val ChevronUp = icon("chevU", "M7 14l5-5 5 5")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Copy = icon("copy", "M9 9h10v10H9z", "M5 15V5h10")
    val Retry = icon("retry", "M4 12a8 8 0 1 0 2.4-5.7", "M4 4v4.5h4.5")
    val Stop = icon("stop", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", filled = true)
    val Mic = icon("mic", "M12 4a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V7a3 3 0 0 1 3-3z", "M6 11a6 6 0 0 0 12 0", "M12 17v3")
    val Paperclip = icon("clip", "M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8.1-8.1a3.3 3.3 0 0 1 4.7 4.7l-8.1 8.1a1.6 1.6 0 0 1-2.3-2.3l7.4-7.4")
    val Camera = icon("camera", "M4 8h3l2-2.5h6L17 8h3v11H4z", "M12 16.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
    val Image = icon("image", "M4 5h16v14H4z", "M4 16l5-5 4 4 2.5-2.5L20 17", "M15 9.5h.01")
    val Folder = icon("folder", "M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2.5h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 17z")
    val Code = icon("code", "M8.5 7.5L4 12l4.5 4.5", "M15.5 7.5L20 12l-4.5 4.5", "M13.5 5l-3 14")
    val Cowork = icon("cowork", "M7 3.5h10a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z", "M10.5 17.5h3", "M9 9.5l2 2 4-4")
    val Chat = icon("chat", "M5 5h14v10H9l-4 4z")
    val Search = icon("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-4-4")
    val Settings = icon("settings", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M19.4 13.5l1.6 1.2-2 3.4-1.9-.7a7 7 0 0 1-1.7 1l-.3 2h-4l-.3-2a7 7 0 0 1-1.7-1l-1.9.7-2-3.4 1.6-1.2a7 7 0 0 1 0-3L3 9.3l2-3.4 1.9.7a7 7 0 0 1 1.7-1l.3-2h4l.3 2a7 7 0 0 1 1.7 1l1.9-.7 2 3.4-1.6 1.2a7 7 0 0 1 0 3z")
    val More = icon("more", "M6 12h.01", "M12 12h.01", "M18 12h.01")
    val Terminal = icon("terminal", "M4 5h16v14H4z", "M7.5 9.5l3 2.5-3 2.5", "M12.5 15h4")
    val Globe = icon("globe", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M4 12h16", "M12 4c2.5 2.5 2.5 13.5 0 16", "M12 4c-2.5 2.5-2.5 13.5 0 16")
    val File = icon("file", "M7 3.5h7l4.5 4.5v12.5H7z", "M14 3.5V8h4.5")
    val Pencil = icon("pencil", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Phone = icon("phone", "M8 3.5h8a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5z", "M11 17.5h2")
    val Question = icon("question", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M9.8 9.5a2.3 2.3 0 0 1 4.4.8c0 1.6-2.2 2-2.2 3.4", "M12 16.5h.01")
    val List = icon("list", "M9 7h11", "M9 12h11", "M9 17h11", "M4.5 7h.01", "M4.5 12h.01", "M4.5 17h.01")
    val Agent = icon("agent", "M12 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M5 20a7 7 0 0 1 14 0")
    val Tool = icon("tool", "M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.4 2.4-2.6-.4-.4-2.6z")
    val Shield = icon("shield", "M12 3.5l7 3v5.5c0 4-3 7.2-7 8.5-4-1.3-7-4.5-7-8.5V6.5z")
    val Hand = icon("hand", "M8 12V6.5a1.5 1.5 0 0 1 3 0V11", "M11 10V5a1.5 1.5 0 0 1 3 0v6", "M14 10.5V7a1.5 1.5 0 0 1 3 0v6.5a6.5 6.5 0 0 1-6.5 6.5c-2.5 0-4-1.3-5.4-3.4L3.6 14a1.5 1.5 0 0 1 2.5-1.6L8 15")
    val Clock = icon("clock", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 8v4l2.5 2")
    val Archive = icon("archive", "M4 5h16v4H4z", "M5.5 9v10h13V9", "M10 13h4")
    val Pin = icon("pin", "M9 4h6l-1 6 3 3v1.5H7V13l3-3z", "M12 14.5V20")
    val Trash = icon("trash", "M5 7h14", "M9.5 7V4.5h5V7", "M7 7l1 13h8l1-13")
    val Server = icon("server", "M4 5h16v6H4z", "M4 13h16v6H4z", "M7.5 8h.01", "M7.5 16h.01")
    val Power = icon("power", "M12 4v8", "M7.2 6.8a7 7 0 1 0 9.6 0")
    val Link = icon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Key = icon("key", "M8 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M12 12h8", "M17 12v3", "M20 12v2")
    val Palette = icon("palette", "M12 20a8 8 0 1 1 8-8c0 2-1.5 3-3 3h-2a1.5 1.5 0 0 0-1 2.6c.6.6.3 2.4-2 2.4z", "M8 11h.01", "M10.5 7.5h.01", "M15 8h.01")
    val Vibrate = icon("vibrate", "M8.5 5h7v14h-7z", "M4.5 9v6", "M19.5 9v6")
    val Info = icon("info", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 11v5", "M12 8h.01")
    val Brain = icon("brain", "M9 4.5a3 3 0 0 0-3 3 3 3 0 0 0-2 5 3 3 0 0 0 2 5 3 3 0 0 0 3 2V4.5z", "M15 4.5a3 3 0 0 1 3 3 3 3 0 0 1 2 5 3 3 0 0 1-2 5 3 3 0 0 1-3 2V4.5z")
    val Warning = icon("warning", "M12 4l9 16H3z", "M12 10v4", "M12 17h.01")
    val ThumbUp = icon("thumbUp", "M7 10v10H4V10z", "M7 10l4-6.5a2 2 0 0 1 3 2L13.5 9H19a1.5 1.5 0 0 1 1.5 1.8l-1.4 7.4A2.2 2.2 0 0 1 17 20H7")
    val Refresh = icon("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5V9H15")
    val Restart = icon("restart", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", "M4.5 4.5V9H9")
    val Layers = icon("layers", "M12 4l8 4-8 4-8-4z", "M4 12l8 4 8-4", "M4 16l8 4 8-4")
    val Play = icon("play", "M8 5.5v13l10.5-6.5z")
    val Filter = icon("filter", "M4 6h16", "M7 12h10", "M10 18h4")
    val Eye = icon("eye", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z")
}

@Composable fun GlyphIcon(icon: ImageVector, description: String?, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = LocalContentColor.current) {
    Icon(icon, description, modifier.size(size), tint = tint)
}

/** 40dp grey circular icon button used in the top bars. */
@Composable fun CircleButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 40.dp,
                             container: Color = MaterialTheme.colorScheme.surfaceContainer, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Surface(onClick = { haptic(); onClick() }, enabled = enabled, shape = CircleShape, color = container, modifier = modifier.size(size).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) { GlyphIcon(icon, null, size = if (size >= 40.dp) 20.dp else 18.dp, tint = if (enabled) tint else tint.copy(alpha = .38f)) }
    }
}

/** Haptic tick on deliberate actions; respects the in-app setting. */
@Composable fun rememberHaptic(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHaptics.current
    return remember(feedback, enabled) { { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}
val LocalHaptics = staticCompositionLocalOf { true }

/** Pill chip used in the composer footer and filters. */
@Composable fun Pill(text: String, modifier: Modifier = Modifier, leading: ImageVector? = null, trailing: ImageVector? = Glyph.ChevronDown, selected: Boolean = false,
                     enabled: Boolean = true, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Surface(onClick = { haptic(); onClick() }, enabled = enabled, shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, Pocket.hairline), modifier = modifier.heightIn(min = 32.dp)) {
        Row(Modifier.padding(start = if (leading != null) 9.dp else 12.dp, end = if (trailing != null) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { GlyphIcon(it, null, size = 15.dp); Spacer(Modifier.width(5.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            trailing?.let { Spacer(Modifier.width(3.dp)); GlyphIcon(it, null, size = 14.dp) }
        }
    }
}

/** Grouped inset card for settings-style lists. */
@Composable fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest.takeIf { !Pocket.dark } ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, Pocket.hairline)) { Column(content = content) }
}
@Composable fun GroupRow(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null, value: String? = null, enabled: Boolean = true,
                         tint: Color = MaterialTheme.colorScheme.onSurface, chevron: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val row = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it }
    Row(modifier.then(row).heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { GlyphIcon(it, null, size = 20.dp, tint = if (enabled) tint else tint.copy(alpha = .38f)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) tint else tint.copy(alpha = .38f))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pocket.muted) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) }
        trailing?.invoke()
        if (chevron && onClick != null) { Spacer(Modifier.width(4.dp)); GlyphIcon(Glyph.ChevronRight, null, size = 18.dp, tint = Pocket.faint) }
    }
}
@Composable fun GroupDivider(inset: Dp = 50.dp) { HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Pocket.hairline) }
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
}

/** Status dot used in session rows. */
@Composable fun StatusDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val alpha by if (pulse) rememberInfiniteTransition(label = "dot").animateFloat(.35f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a") else remember { mutableFloatStateOf(1f) }
    Canvas(modifier.size(8.dp)) { drawCircle(color.copy(alpha = alpha)) }
}

/** Sheet-header with centred title, matching the reference app's sheets. */
@Composable fun SheetHeader(title: String, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pocket.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

@Composable fun pocketSheetColors() = MaterialTheme.colorScheme.surface

/** Waiting indicator — Continuo has no spinners; the blinking cursor stands in. [size] is the cursor height. */
@Composable fun Spinner(modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = Pocket.accent) {
    ContinuoCursor(modifier, height = maxOf(size, 12.dp), color = color, mode = CursorMode.BLINK)
}
@Composable fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
