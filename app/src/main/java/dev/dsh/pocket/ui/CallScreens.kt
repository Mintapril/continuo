package dev.dsh.pocket.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.*
import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val SheetInk = Color(0xFF101114)
private val SheetText = Color(0xFFECECEE)
private val SheetMuted = Color(0xFF9A9AA3)
private val SheetField = Color(0xFF1E1F24)

/**
 * Scrim + bottom sheet that a downward drag (or a tap above it, or back) puts away. The sheet slides in on its own
 * (the window opens without an animation) and slides out before [close] runs; [content] gets the same put-away.
 */
@Composable private fun HalfSheet(color: Color, close: () -> Unit, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 120.dp.toPx() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val ime = WindowInsets.ime
    // Putting it away asks the keyboard down first, so the system slides it out while the window is still there (taking
    // the window away with the keyboard up makes the keyboard vanish at once). The window goes once both are down.
    val dismiss = { focusManager.clearFocus(force = true); keyboard?.hide(); shown.targetState = false }
    LaunchedEffect(shown.targetState) {
        if (shown.targetState) return@LaunchedEffect
        withTimeoutOrNull(700) { snapshotFlow { shown.isIdle && ime.getBottom(density) == 0 }.first { it } }
        close()
    }
    val motion = rememberTransition(shown, "sheet")
    val scrim by motion.animateFloat({ tween(if (targetState) 220 else 180) }, "scrim") { if (it) 0.35f else 0f }
    val away by motion.animateFloat({ if (targetState) spring(dampingRatio = 0.86f, stiffness = 420f) else tween(190, easing = FastOutLinearInEasing) }, "away") { if (it) 0f else 1f }
    BackHandler(onBack = dismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).pointerInput(Unit) { detectTapGestures { dismiss() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { translationY = offset + away * (size.height + 48.dp.toPx()) }
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(color)
            .pointerInput(Unit) { detectTapGestures { } } // taps inside never reach the scrim
            .imePadding().navigationBarsPadding().animateContentSize()) {
            Box(Modifier.fillMaxWidth().height(28.dp).draggable(rememberDraggableState { offset = (offset + it).coerceAtLeast(0f) }, Orientation.Vertical,
                onDragStopped = { if (offset > limit) dismiss() else offset = 0f }), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.5f)))
            }
            content(dismiss)
        }
    }
}

/** Speech to text with the phone's recognizer, when it has one; the press-and-hold button sends what was heard. */
private class Voice(private val context: android.content.Context) : RecognitionListener {
    val available = SpeechRecognizer.isRecognitionAvailable(context)
    var listening by mutableStateOf(false); private set
    var heard by mutableStateOf(""); private set
    var level by mutableFloatStateOf(0f); private set
    private var recognizer: SpeechRecognizer? = null
    private var done: ((String) -> Unit)? = null
    private var failed: ((String) -> Unit)? = null
    fun start(onDone: (String) -> Unit, onFailed: (String) -> Unit) {
        if (!available) { onFailed("这台手机没有语音识别服务，可以用输入法自带的语音输入"); return }
        done = onDone; failed = onFailed; heard = ""
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this); recognizer = it }
        engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        listening = true
    }
    fun stop() { if (listening) recognizer?.stopListening() }
    fun cancel() { recognizer?.cancel(); listening = false; done = null }
    fun destroy() { recognizer?.destroy(); recognizer = null; listening = false }
    private fun words(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    override fun onPartialResults(partialResults: Bundle?) { words(partialResults).takeIf { it.isNotBlank() }?.let { heard = it } }
    override fun onResults(results: Bundle?) { listening = false; val text = words(results).ifBlank { heard }; done?.invoke(text); done = null }
    override fun onError(error: Int) {
        listening = false
        val text = heard
        if (text.isNotBlank() && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { done?.invoke(text); done = null; return }
        failed?.invoke(when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再说一次"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别要联网，现在连不上"
            else -> "语音识别出错了（$error），可以打字"
        }); done = null
    }
    override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

/** The latest exchange of the owner's conversation: what they said last, and its newest reply after that. */
private data class Exchange(val said: String, val reply: String, val notes: List<ChatLog.Note>)

private fun exchange(rows: List<TranscriptRow>): Exchange {
    // Its patrols and notifications run in this conversation too: only what followed your words, until something else
    // came in, is its answer to you.
    val part = ChatLog.lastOwnerPart(rows)
    val after = part.drop(1)
    val reply = after.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text.orEmpty()
    // A note it wrote down while you talked shows as a yellow note: that is a "later" thing, not done now.
    val notes = after.mapNotNull { ChatLog.noteOf(it) }
    return Exchange(part.firstOrNull()?.text.orEmpty(), reply, notes)
}

/**
 * 叫它: the owner talks to it right now, about anything (its one way to give it a task). Text by default; the mic
 * switches to press-and-hold speech. Only the latest exchange shows; the whole conversation stays in the app.
 * [askMic] asks for the microphone (the host decides how: a permission dialog, or the activity when there is none);
 * [startVoice] opens on speech. The clear button starts the conversation over in a fresh session (see clearOwner).
 * [onPending], when set, replaces the question / approval sheet (a window without an activity cannot open one).
 */
@Composable fun CallSheet(vm: PocketViewModel, shared: String?, close: () -> Unit, askMic: (granted: (Boolean) -> Unit) -> Unit, startVoice: Boolean = false,
                          onPending: (() -> Unit)? = null) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    val owner by OwnerConversation.flow(context).collectAsState()
    var sharedLeft by remember { mutableStateOf(shared) }
    LaunchedEffect(owner) { vm.openOwner(sharedLeft); sharedLeft = null }
    val card = rememberStatusCard(identity.shown)
    val link by CoworkConnection.connection.collectAsState()
    val status by CoworkConnection.status.collectAsState()
    val linked = link == CoworkLinkState.CONNECTED && status.optInt("displayId") > 0
    val ready = vm.selected == owner
    val talk = remember(vm.rows) { exchange(vm.rows) }
    val island by dev.dsh.pocket.island.IslandRuntime.store.snapshots.collectAsState()
    val waiting = vm.running && waitsForScreen(owner, true, TaskMode.COWORK, island.screenHolder)
    var voiceMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // The message on its way shows as 你说 at once, until the conversation has it, or the send failed (the retry line
    // takes over) or was refused (the words are back in the field). The field keeps its focus meanwhile: a field that
    // turns disabled or read-only ends its input session, the keyboard drops and the sheet jumps.
    var sent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sent, talk.said, talk.reply, vm.submitting, vm.uncertain, vm.running, vm.draft) {
        val text = sent ?: return@LaunchedEffect
        when {
            vm.submitting -> {}
            vm.uncertain -> sent = null
            talk.said.trim() == text && (vm.running || talk.reply.isNotBlank()) -> sent = null
            vm.draft.trim() == text -> sent = null
        }
    }
    LaunchedEffect(sent) { if (sent != null) { delay(20_000); sent = null } }
    fun send(text: String = vm.draft) {
        if (text.isBlank()) return
        error = ""; sent = text.trim(); vm.send()
    }
    val shownDraft = if (vm.submitting) "" else vm.draft
    val said = sent ?: talk.said
    // While a new message is on its way, the reply shown so far answered the one before.
    val reply = if (sent == null) talk.reply else ""
    val holding = island.screenHolder == owner && card.working
    val app = if (holding) status.optJSONObject("activity")?.optString("app")?.trim()?.ifEmpty { null } else null
    // What it is doing shows when it is about you; on its own (a patrol, notifications) the status card says so.
    val mine = remember(vm.rows) { ChatLog.ownerSpeaking(vm.rows) }
    val progress = rememberProgress(if (ready) vm.rows else emptyList(), vm.running && mine, waiting && mine, sent != null, app)
    var told by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val voice = remember { Voice(context) }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }
    val keyboard = LocalSoftwareKeyboardController.current
    fun wantVoice() {
        when {
            !voice.available -> error = "这台手机没有语音识别服务，可以用输入法自带的语音输入"
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> { keyboard?.hide(); voiceMode = true }
            else -> askMic { granted -> if (granted) voiceMode = true else error = "没有麦克风权限，先打字吧" }
        }
    }
    LaunchedEffect(Unit) { if (startVoice) wantVoice() }
    LaunchedEffect(vm.notice) { if (vm.notice.isNotBlank()) { error = vm.notice; vm.clearNotice() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(voiceMode, ready, confirmClear) { if (!voiceMode && ready && !confirmClear) { delay(120); runCatching { focus.requestFocus() }; keyboard?.show() } }
    val mood = when {
        card.asking -> StatusCard.Mood.CALL
        (vm.running || sent != null) && !waiting -> StatusCard.Mood.WORK
        else -> StatusCard.Mood.LISTEN
    }
    val moving = vm.schedules.tasks.count { it.text("sessionId") == owner && it.text("status") == "active" }
    HalfSheet(SheetInk, close) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(identity.avatar, mood, identity.shown, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(identity.shown, style = MaterialTheme.typography.titleMedium, color = SheetText)
                Text(when {
                    !vm.connected -> "正在过来…"
                    // Coarse here: the line under what you said tells the step.
                    waiting -> "在排队"
                    vm.running || sent != null -> if (holding) card.title.substringAfter(" · ") else "在忙"
                    card.working -> "${card.title.substringAfter(" · ")} · 在听"
                    else -> "在听"
                }, style = MaterialTheme.typography.bodySmall, color = SheetMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (ready && vm.rows.isNotEmpty() && !confirmClear) CircleButton(Glyph.Archive, "清空对话", container = Color.Transparent, tint = SheetMuted) {
                error = ""; told = ""
                if (vm.running) error = "${identity.shown}正在做事，等它做完再清空"
                else { keyboard?.hide(); voice.cancel(); voiceMode = false; vm.schedulesRefresh(); confirmClear = true }
            }
            if (linked) LivePreview(Modifier.padding(start = 6.dp).width(52.dp).pointerInput(Unit) { detectTapGestures { CoworkControllerService.show(context, CoworkControllerService.WATCH); dismiss() } })
        }
        AnimatedContent(confirmClear, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) }, label = "clear") { confirming ->
        if (confirming) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("清空这段对话？", style = MaterialTheme.typography.titleMedium, color = SheetText)
                Text("${identity.shown}会从头开始聊，刚才说的不再记得；记忆和日记都还在" +
                    (if (moving > 0) "，这段对话里还没到点的 $moving 张便条会搬到新对话。" else "。") + "旧对话会归档，在“设置 → 开发者”里还能看到。",
                    style = MaterialTheme.typography.bodyMedium, color = SheetMuted)
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9F8A))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface({ confirmClear = false; error = "" }, Modifier.weight(1f).height(48.dp), enabled = !clearing, shape = CircleShape, color = SheetField, contentColor = SheetText) {
                        Box(contentAlignment = Alignment.Center) { Text("算了", style = MaterialTheme.typography.titleSmall) }
                    }
                    Surface({
                        clearing = true; error = ""
                        vm.clearOwner { result ->
                            clearing = false
                            result.onSuccess { moved -> confirmClear = false; told = if (moved > 0) "清空了，$moved 张便条搬到了新对话" else "清空了" }
                                .onFailure { error = it.message ?: "没清空" }
                        }
                    }, Modifier.weight(1f).height(48.dp), enabled = !clearing, shape = CircleShape, color = Color.White, contentColor = Color.Black) {
                        Box(contentAlignment = Alignment.Center) { Text(if (clearing) "正在清空…" else "清空", style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        } else Column {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (said.isNotBlank()) {
                Text("你说", style = MaterialTheme.typography.labelSmall, color = SheetMuted)
                Text(said, style = MaterialTheme.typography.bodyMedium, color = SheetText.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (reply.isNotBlank()) {
                Text("${identity.shown}说", style = MaterialTheme.typography.labelSmall, color = SheetMuted)
                Text(reply, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp), fontWeight = FontWeight.Medium, color = SheetText)
            }
            // What it is doing, below what it said so far: a status line, not its words.
            progress?.let { ProgressLine(it, SheetMuted, MaterialTheme.typography.bodyMedium, Modifier.padding(top = 2.dp)) }
            talk.notes.forEach { note ->
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFFF4B8), contentColor = Color(0xFF5A4A00)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("记成了便条 · ${note.timing}", style = MaterialTheme.typography.labelSmall)
                        Text(note.words, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (said.isBlank() && reply.isBlank() && progress == null) Text(if (ready) "有什么事，跟${identity.shown}说吧。" else "正在叫${identity.shown}…",
                style = MaterialTheme.typography.bodyLarge, color = SheetMuted)
            if (told.isNotBlank()) Text(told, style = MaterialTheme.typography.bodySmall, color = SheetMuted)
            if (onPending != null && vm.pending.isNotEmpty()) Surface({ onPending() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = SheetField, contentColor = SheetText) {
                Text("${identity.shown}有事要你确认，点这里回答", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9F8A))
        }
        AnimatedContent(voiceMode, transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.96f)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
            label = "input") { speaking ->
        if (speaking) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Glyph.Keyboard, "改成打字", container = SheetField, tint = SheetText) { voice.cancel(); voiceMode = false }
                Spacer(Modifier.width(10.dp))
                val holding = voice.listening
                val lit by animateColorAsState(if (holding) Color.White else SheetField, tween(160), label = "talk")
                val loud by animateFloatAsState(if (holding) 1f + voice.level * 0.05f else 1f, spring(stiffness = 900f), label = "level")
                Box(Modifier.weight(1f).height(56.dp).graphicsLayer { scaleX = loud; scaleY = loud }.clip(CircleShape).background(lit)
                    .pointerInput(ready) {
                        detectTapGestures(onPress = {
                            if (!ready) return@detectTapGestures
                            error = ""
                            voice.start({ text -> if (text.isNotBlank()) { vm.updateDraft(text); send(text) } }, { error = it })
                            tryAwaitRelease()
                            voice.stop()
                        })
                    }, contentAlignment = Alignment.Center) {
                    Text(when { holding && voice.heard.isNotBlank() -> voice.heard; holding -> "在听，松开发送"; else -> "按住说" },
                        color = if (holding) Color.Black else SheetText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        } else Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(26.dp)).background(SheetField)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // While it sends, the field looks empty and stays enabled (see [sent]); the view model ignores typing then.
                BasicTextField(shownDraft, { vm.updateDraft(it) }, Modifier.weight(1f).heightIn(min = 40.dp, max = 140.dp).focusRequester(focus).padding(vertical = 9.dp),
                    enabled = ready && !(vm.uncertain && !vm.submitting), textStyle = MaterialTheme.typography.bodyLarge.copy(color = SheetText), cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner -> Box { if (shownDraft.isEmpty()) Text(if (vm.running) "插一句，它会马上看到" else "跟${identity.shown}说…", color = SheetMuted, style = MaterialTheme.typography.bodyLarge); inner() } })
                Crossfade(shownDraft.isBlank(), animationSpec = tween(150), label = "send") { empty ->
                    if (empty) CircleButton(Glyph.Mic, "改成说话", container = Color.Transparent, tint = SheetText) { wantVoice() }
                    else CircleButton(Glyph.ArrowUp, "发送", enabled = ready && !vm.submitting, container = Color.White, tint = Color.Black) { send() }
                }
            }
            if (vm.uncertain && !vm.submitting) TextButton({ sent = vm.draft.trim().ifEmpty { null }; vm.send(retry = true) }, Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                Text("上一句没确认发出去，点这里重发", color = Color(0xFFFFC46B))
            }
        }
        }
        }
        }
    }
    if (onPending == null) vm.pending.firstOrNull()?.let { key(it.text("eventId")) { PendingRequest(vm, it) } }
}

/**
 * 记便条: something for later, in the owner's words, at a time they must pick (a note without a time is not stored).
 * Stored at once by the plugin, no model turn; the receipt shows 贴上了, then 收到 with the time it heard.
 */
@Composable fun NoteSheet(vm: PocketViewModel, shared: String?, close: () -> Unit) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    var text by remember { mutableStateOf(shared.orEmpty()) }
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { LocalDateTime.now() }
    val quick = remember { Companion.quickTimes(now, zone) }
    var choice by remember { mutableStateOf<String?>(null) }
    var timing by remember { mutableStateOf<TimingDraft?>(null) }
    var posting by remember { mutableStateOf(false) }
    var posted by remember { mutableStateOf<JSONNotePosted?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(vm.connected) { if (vm.connected) vm.refresh() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val ink = Pocket.onSticky
    HalfSheet(Pocket.sticky, close) { dismiss ->
        AnimatedContent(posted, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false) }, label = "receipt") { receipt ->
        if (receipt != null) {
            var read by remember(receipt) { mutableStateOf(false) }
            LaunchedEffect(receipt) { delay(650); read = true; delay(2_400); dismiss() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(read, Modifier.weight(1f), transitionSpec = { (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }) togetherWith fadeOut(tween(120)) },
                        label = "read") { done -> Text(if (done) "✓✓ 收到" else "✓ 贴上了", style = MaterialTheme.typography.titleMedium, color = ink) }
                    AnimatedVisibility(read, enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 400f), initialScale = 0.4f) + fadeIn()) {
                        Avatar(identity.avatar, StatusCard.Mood.ACK, identity.shown, size = 56.dp)
                    }
                }
                Text(receipt.words, style = MaterialTheme.typography.bodyLarge, color = ink)
                Text(receipt.next, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.75f))
                AnimatedVisibility(read, enter = expandVertically() + fadeIn()) {
                    Text(receipt.reply, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else Column {
        Text("记便条", Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.titleMedium, color = ink)
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            BasicTextField(text, { text = it.take(4_000) }, Modifier.fillMaxWidth().heightIn(min = 72.dp).focusRequester(focus),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink), cursorBrush = SolidColor(ink),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text("以后要${identity.shown}做什么？原话照存", color = ink.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyLarge); inner() } })
            Text("什么时候（必选）", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.8f))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { (label, draft) -> NoteChip(label, choice == label) { choice = label; timing = draft } }
                NoteChip("每天…", choice == "daily") { choice = "daily"; timing = TimingDraft("daily", time = "08:00", zone = zone.id) }
                NoteChip("每周…", choice == "weekly") { choice = "weekly"; timing = TimingDraft("weekly", time = "09:00", zone = zone.id) }
                NoteChip("选时间", choice == "once") { choice = "once"; timing = Companion.once(LocalDate.now(zone).plusDays(1), "09:00", zone) }
            }
            val custom = timing
            AnimatedVisibility(custom != null && choice in setOf("daily", "weekly", "once"), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    if (custom != null) TimingEditor(custom, kinds = emptyList(), showZone = false) { timing = it }
                }
            }
            if (error.isNotBlank()) Text(error, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val chosen = timing
        val problem = chosen?.problem()
        val ok = text.isNotBlank() && chosen != null && problem == null && vm.connected && !posting
        Surface({
            val pick = chosen ?: return@Surface
            posting = true; error = ""
            vm.createNote(text, pick) { result ->
                posting = false
                result.onSuccess { record ->
                    posted = JSONNotePosted(text.trim(), "下次 ${Companion.nextText(record.text("scheduledAt")).ifBlank { Companion.whenText(pick) }} · ${noteSessionName(vm, record.text("sessionId"))}",
                        Companion.receipt(text, pick))
                }.onFailure { error = it.message ?: "没贴上" }
            }
        }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 52.dp), enabled = ok, shape = CircleShape,
            color = animateColorAsState(if (ok) ink else ink.copy(alpha = 0.25f), tween(200), label = "post").value, contentColor = Pocket.sticky) {
            Box(contentAlignment = Alignment.Center) {
                Text(when {
                    posting -> "正在贴…"
                    !vm.connected -> "正在连接…"
                    chosen == null -> "先选什么时候"
                    problem != null -> problem
                    else -> "贴上 · ${Companion.whenText(chosen)}"
                }, style = MaterialTheme.typography.titleSmall)
            }
        }
        }
        }
    }
}

private data class JSONNotePosted(val words: String, val next: String, val reply: String)

@Composable private fun NoteChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = Pocket.onSticky
    val fill by animateColorAsState(if (selected) ink else Color.Transparent, tween(180), label = "chip")
    val text by animateColorAsState(if (selected) Pocket.sticky else ink, tween(180), label = "chip ink")
    Surface(onClick, shape = CircleShape, color = fill, contentColor = text,
        border = androidx.compose.foundation.BorderStroke(1.dp, ink.copy(alpha = if (selected) 1f else 0.35f))) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
    }
}
