@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.dsh.pocket.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.*
import dev.dsh.pocket.island.IslandRuntime
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 开发者: every DSH conversation as it is (Code tasks, its conversations with you, each app's standing session, the
 * patrol), and new Code tasks with a workspace, model and permission. Kept for advanced use; the rest of the app never
 * shows a conversation list.
 */
@Composable internal fun DeveloperTasks(vm: PocketViewModel, back: () -> Unit, newTask: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(SessionFilter.ALL) }
    var mode by rememberSaveable { mutableStateOf(TaskMode.CODE) }
    var filterMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pendingAgents = vm.pending.map { it.text("agentId") }.toSet()
    val now = System.currentTimeMillis()
    val rows = vm.sessions.filter { sessionMode(it) == mode && matchesFilter(it, filter, vm.archived, pendingAgents) && !(it.optBoolean("blank") && filter == SessionFilter.ALL && it.text("sessionId") !in vm.pinned) }
        .filter { query.isBlank() || sessionTitle(it).contains(query.trim(), true) || it.text("cwd").contains(query.trim(), true) }
        .sortedWith(compareByDescending<JSONObject> { it.text("sessionId") in vm.pinned }.thenByDescending { it.optLong("updatedAt") })
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.ArrowLeft, "返回", onClick = back)
            Text("开发者", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            CircleButton(Glyph.Plus, "新建 Code 任务", onClick = { vm.changeMode(TaskMode.CODE); newTask() })
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tabs(if (mode == TaskMode.CODE) 0 else 1, listOf("Code", "Cowork 会话"), Modifier.weight(1f)) { mode = if (it == 0) TaskMode.CODE else TaskMode.COWORK }
            Spacer(Modifier.width(8.dp))
            Box {
                Pill(filter.label, leading = Glyph.Filter, onClick = { filterMenu = true })
                DropdownMenu(filterMenu, { filterMenu = false }, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface) {
                    SessionFilter.entries.forEach { f ->
                        DropdownMenuItem({ Text(f.label, style = MaterialTheme.typography.bodyLarge) }, { filter = f; filterMenu = false },
                            trailingIcon = { if (f == filter) GlyphIcon(Glyph.Check, null, size = 18.dp, tint = Pocket.accent) })
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Search, null, size = 17.dp, tint = Pocket.muted); Spacer(Modifier.width(8.dp))
                BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(Pocket.accent), decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索标题或目录", style = MaterialTheme.typography.bodyMedium, color = Pocket.faint); inner() } })
            }
        }
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; try { vm.refresh().join() } finally { refreshing = false } } }, Modifier.weight(1f).padding(top = 8.dp)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.text("sessionId") }) { row -> Box(Modifier.animateItem()) { TaskRow(vm, row, now, pendingAgents) { menu = row } } }
                if (rows.isEmpty()) item {
                    Text(when { !vm.connected -> "连接 DSH 后显示会话"; query.isNotBlank() -> "没有匹配的会话"; filter != SessionFilter.ALL -> "没有${filter.label}的会话"; else -> "还没有会话" },
                        Modifier.fillMaxWidth().padding(top = 72.dp), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
                }
            }
        }
    }
    menu?.let { row -> SessionMenu(vm, row, { menu = null }) }
}

@Composable private fun TaskRow(vm: PocketViewModel, row: JSONObject, now: Long, pendingAgents: Set<String>, more: () -> Unit) {
    val id = row.text("sessionId")
    val running = row.optBoolean("running"); val waiting = needsInput(row, pendingAgents)
    val island by IslandRuntime.store.snapshots.collectAsState()
    val queued = !waiting && waitsForScreen(id, running, sessionMode(row), island.screenHolder)
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.openSession(id) }, onLongClick = { haptic(); more() })) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (id in vm.pinned) { GlyphIcon(Glyph.Pin, "已置顶", size = 14.dp, tint = Pocket.faint); Spacer(Modifier.width(5.dp)) }
                    Text(sessionTitle(row), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(workspaceLabel(row.text("cwd")), style = MaterialTheme.typography.labelSmall, color = Pocket.success, maxLines = 1)
                    presetTag(row)?.let { Spacer(Modifier.width(6.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = Pocket.faint) }
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(row.optLong("updatedAt"), now), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                    if (waiting || running) {
                        val tint = when { waiting -> Pocket.warning; queued -> Pocket.muted; else -> Pocket.accent }
                        Spacer(Modifier.width(10.dp)); ContinuoCursor(height = 12.dp, color = tint, mode = if (waiting || queued) CursorMode.STEADY else CursorMode.BLINK); Spacer(Modifier.width(6.dp))
                        Text(when { waiting -> "需要输入"; queued -> "等副屏"; else -> "运行中" }, style = MaterialTheme.typography.labelSmall, color = tint)
                    }
                }
            }
            IconButton(more, Modifier.size(40.dp)) { GlyphIcon(Glyph.More, "选项", size = 18.dp, tint = Pocket.muted) }
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = Pocket.hairline)
    }
}

/** A new Code task: workspace, model and permission, then the first message. */
@Composable internal fun DeveloperNewTask(vm: PocketViewModel, back: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.mode != TaskMode.CODE) vm.changeMode(TaskMode.CODE) }
    val available = vm.modeAvailable(TaskMode.CODE)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.ArrowLeft, "返回", onClick = back)
            Text("新的 Code 任务", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            ContinuoMark(size = 44.dp, mode = if (vm.starting) CursorMode.BLINK else CursorMode.STEADY)
            Spacer(Modifier.height(14.dp))
            Text("在 DSH 中编写、调试与运行代码", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
        }
        if (!vm.connected) ConnectCard(vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Composer(vm, if (available) "描述任务或提出问题" else "Code 尚未配置", Modifier.imePadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            enabled = available, onSend = { vm.startTask() }) {
            val workspace = vm.workspaces.firstOrNull { it.text("workspaceId") == vm.homeWorkspace }
            Pill(workspace?.let { it.text("title").ifBlank { workspaceLabel(it.text("path")) } } ?: "workspace", leading = Glyph.Folder) { sheet = "workspace" }
            ModelPill(vm) { sheet = "model" }
            PermissionPill(vm) { sheet = "permission" }
        }
    }
    when (sheet) {
        "workspace" -> WorkspaceSheet(vm) { sheet = null }
        "model" -> ModelSheet(vm.catalog, vm.currentModel(), { sheet = null }) { p, m, e -> vm.selectModel(p, m, e); sheet = null }
        "permission" -> PermissionSheet(vm) { sheet = null }
    }
}
