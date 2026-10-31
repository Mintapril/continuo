package dev.dsh.pocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.ChatLog
import dev.dsh.pocket.data.Identity
import dev.dsh.pocket.data.OwnerConversation
import dev.dsh.pocket.data.TaskMode
import dev.dsh.pocket.data.waitsForScreen
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.island.IslandRuntime
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Fewer chat lines than this, with older history left, reads older pages on its own, at most [AUTO_PAGES] of them. */
private const val CHAT_FILL = 12
private const val AUTO_PAGES = 5

/**
 * 聊天记录: the owner's conversation with it (叫它), as a chat. The home page keeps it followed on its own stream (the view
 * model's selected session would open the full chat screen), so this tab shows it at once. Its thinking and tool calls
 * stay in 设置 → 开发者; what it did on its own in this conversation (its own time, notes, notifications) is in 事件, except
 * what it said to the owner there. While the owner looks at it ([visible], the app in front), the plugin is told they saw
 * its messages (ownerSeen), so it does not try to reach them another way.
 */
@Composable internal fun ChatPane(vm: PocketViewModel, identity: Identity, visible: Boolean = true) {
    val context = LocalContext.current
    val owner by OwnerConversation.flow(context).collectAsState()
    val exists = vm.sessions.any { it.optString("sessionId") == owner }
    val lines = remember(vm.ownerLog, vm.ownerRunning) { ChatLog.of(vm.ownerLog, vm.ownerRunning) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val looking = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && vm.connected && vm.ownerLogReady
    LaunchedEffect(looking, vm.ownerLog.size) { if (looking) vm.ownerSeen() }
    // What the running turn is doing, for its last line; the app only when this conversation holds its screen.
    val island by IslandRuntime.store.snapshots.collectAsState()
    val status by CoworkConnection.status.collectAsState()
    val holding = island.screenHolder == owner && status.optString("state") == "running"
    val progress = rememberProgress(vm.ownerLog, vm.ownerRunning, waitsForScreen(owner, vm.ownerRunning, TaskMode.COWORK, island.screenHolder), false,
        if (holding) status.optJSONObject("activity")?.optString("app")?.trim()?.ifEmpty { null } else null)
    // Laid out from the bottom (item 0 is the newest), so it opens at the latest line and older pages read in at the top
    // never move it. A new line scrolls down to it.
    val list = rememberLazyListState()
    LaunchedEffect(lines.lastOrNull()?.key, lines.lastOrNull()?.kind) { if (lines.isNotEmpty()) list.animateScrollToItem(0) }
    // Its patrols and notifications live in this conversation too and stay out of the chat: when the latest pages hold
    // little of what you two said, a few older pages are read in on their own; the button reads further.
    var autoPages by remember(owner) { mutableIntStateOf(0) }
    val reading = vm.ownerLogReady && vm.ownerLogMore && lines.size < CHAT_FILL && autoPages < AUTO_PAGES
    LaunchedEffect(vm.ownerLog.size, reading) { if (reading) { autoPages++; vm.ownerLogOlder() } }
    // The empty state stays out of the list: its animated avatar, faded out as a list item, left its layer behind.
    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 96.dp)
            Spacer(Modifier.height(14.dp))
            Text(when {
                !vm.connected -> "连上本机 DSH 后显示聊天记录"
                exists && (!vm.ownerLogReady || reading) -> "正在翻聊天记录…"
                vm.ownerLogMore -> "最近都是${identity.shown}自己在忙（自己逛、看通知），记在事件里。"
                else -> "还没和${identity.shown}说过话。\n点下面的“叫它”，跟它说点什么。"
            }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
            if (vm.ownerLogMore && !reading) TextButton({ vm.ownerLogOlder() }) { Text("更早的聊天", color = Pocket.accent) }
        }
        return
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            items(lines.asReversed(), key = { it.key }) { line -> ChatBubble(line, identity, progress, Modifier.animateItem()) }
            if (vm.ownerLogMore) item(key = "older") {
                TextButton({ vm.ownerLogOlder() }, Modifier.fillMaxWidth()) { Text("更早的聊天", color = Pocket.accent) }
            }
        }
    }
}

@Composable private fun ChatBubble(line: ChatLog.Line, identity: Identity, progress: String?, modifier: Modifier) {
    when (line.kind) {
        ChatLog.Kind.OWNER -> Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(Modifier.widthIn(max = 300.dp), shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = Pocket.userBubble) {
                Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        ChatLog.Kind.REPLY -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 32.dp, head = true)
            Spacer(Modifier.width(8.dp))
            Surface(Modifier.widthIn(max = 300.dp), shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
            }
        }
        ChatLog.Kind.REACH -> Column(modifier.fillMaxWidth()) {
            Text(line.label, Modifier.padding(start = 40.dp, bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Avatar(identity.avatar, StatusCard.Mood.CALL, identity.shown, size = 32.dp, head = true)
                Spacer(Modifier.width(8.dp))
                Surface(Modifier.widthIn(max = 300.dp), shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                }
            }
        }
        ChatLog.Kind.THINKING -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(identity.avatar, StatusCard.Mood.WORK, identity.shown, size = 32.dp, head = true)
            Spacer(Modifier.width(8.dp))
            ProgressLine(progress ?: "在想…", Pocket.muted, MaterialTheme.typography.bodyMedium)
        }
        ChatLog.Kind.NOTE -> Row(modifier.fillMaxWidth().padding(start = 40.dp)) {
            Surface(shape = RoundedCornerShape(6.dp), color = Pocket.sticky, contentColor = Pocket.onSticky) {
                Column(Modifier.padding(12.dp)) {
                    Text("记成了便条 · ${line.label}", style = MaterialTheme.typography.labelSmall)
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
