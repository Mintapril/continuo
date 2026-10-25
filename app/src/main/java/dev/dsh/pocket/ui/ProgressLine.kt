package dev.dsh.pocket.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.data.Progress
import dev.dsh.pocket.data.TranscriptRow
import kotlinx.coroutines.delay

/**
 * An app's name from its package: the broker names the work-profile apps (the owner's own user cannot see their
 * packages; read once it is linked, as the list is dropped on every disconnect), else this user's package manager.
 */
@Composable internal fun rememberAppLabel(): (String) -> String {
    val context = LocalContext.current
    val apps by CoworkConnection.apps.collectAsState()
    val link by CoworkConnection.connection.collectAsState()
    LaunchedEffect(link) { if (link == CoworkLinkState.CONNECTED && CoworkConnection.apps.value.isEmpty()) runCatching { CoworkConnection.refreshApps() } }
    return remember(apps) { { pkg: String -> apps.firstOrNull { it.packageName == pkg }?.label ?: appLabel(context, pkg) } }
}

/** How long one step keeps its words before they change to show it is still going. */
private const val LASTING_MS = 4_200L

/**
 * What it is doing now, in words that change with each step of the running turn and every few seconds while one step
 * lasts (see [Progress]); null when nothing runs. [app] is the app on its screen when this turn holds it.
 */
@Composable internal fun rememberProgress(rows: List<TranscriptRow>, running: Boolean, queued: Boolean, sent: Boolean, app: String?): String? {
    val label = rememberAppLabel()
    val now = remember(rows, running, queued, sent) { Progress.now(rows, running, queued, sent) }
    val book = remember { Progress.Phrasebook() }
    var words by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(now?.key) {
        val step = now ?: run { book.reset(); words = null; return@LaunchedEffect }
        val where = step.target?.let(label) ?: app
        words = book.pick(step.step, where)
        while (true) { delay(LASTING_MS); words = book.pick(step.step, where, lasting = true) }
    }
    return if (now == null) null else words
}

/** The progress words with a small pulsing mark; a new phrase slides in from below. */
@Composable internal fun ProgressLine(words: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Pulse(color)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(words, transitionSpec = {
            (slideInVertically(tween(260, easing = EaseOutCubic)) { it / 2 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(140))) using SizeTransform(clip = false)
        }, label = "progress") { shown ->
            Text(shown, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Three dots that breathe one after another. */
@Composable private fun Pulse(color: Color) {
    val time = rememberInfiniteTransition(label = "pulse")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by time.animateFloat(0.25f, 1f, infiniteRepeatable(keyframes {
                durationMillis = 1_200
                0.25f at 0 + i * 160
                1f at 300 + i * 160
                0.25f at 700 + i * 160
            }), label = "dot $i")
            Box(Modifier.size(5.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
        }
    }
}
