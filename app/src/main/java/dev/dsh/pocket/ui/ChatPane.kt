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

/**
 * 聊天记录: the owner's conversation with it (叫它), as a chat, followed on its own while this tab shows (the view
 * model's selected session would open the full chat screen). Its thinking and tool calls stay in 设置 → 开发者.
 */
@Composable internal fun ChatPane(vm: PocketViewModel, identity: Identity) {
    val context = LocalContext.current
    val owner by OwnerConversation.flow(context).collectAsState()
    val exists = vm.sessions.any { it.optString("sessionId") == owner }
    LaunchedEffect(owner, vm.connected, exists) { vm.followOwner() }
    DisposableEffect(Unit) { onDispose { vm.stopOwnerLog() } }
    val lines = remember(vm.ownerLog, vm.ownerRunning) { ChatLog.of(vm.ownerLog, vm.ownerRunning) }
    // What the running turn is doing, for its last line; the app only when this conversation holds its screen.
    val island by IslandRuntime.store.snapshots.collectAsState()
    val status by CoworkConnection.status.collectAsState()
    val holding = island.screenHolder == owner && status.optString("state") == "running"
    val progress = rememberProgress(vm.ownerLog, vm.ownerRunning, waitsForScreen(owner, vm.ownerRunning, TaskMode.COWORK, island.screenHolder), false,
        if (holding) status.optJSONObject("activity")?.optString("app")?.trim()?.ifEmpty { null } else null)
    val list = rememberLazyListState()
    // New lines at the end scroll to them; older ones loaded at the top keep the view where it is.
    LaunchedEffect(lines.lastOrNull()?.key, lines.lastOrNull()?.kind) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex + if (vm.ownerLogMore) 1 else 0) }
    // The empty state stays out of the list: its animated avatar, faded out as a list item, left its layer behind.
    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 96.dp)
            Spacer(Modifier.height(14.dp))
            Text(when {
                !vm.connected -> "连上本机 DSH 后显示聊天记录"
                exists && !vm.ownerLogReady -> "正在翻聊天记录…"
                else -> "还没和${identity.shown}说过话。\n点下面的“叫它”，跟它说点什么。"
            }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
        }
        return
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.ownerLogMore) item(key = "older") {
                TextButton({ vm.ownerLogOlder() }, Modifier.fillMaxWidth()) { Text("更早的聊天", color = Pocket.accent) }
            }
            items(lines, key = { it.key }) { line -> ChatBubble(line, identity, progress, Modifier.animateItem()) }
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
        ChatLog.Kind.LABEL -> Text("${line.label} · ${line.text}", modifier.fillMaxWidth().padding(vertical = 2.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = Pocket.faint, maxLines = 2)
    }
}
