@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.dsh.pocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.BuildConfig
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

internal enum class Screen { HOME, TASKS, SETTINGS, CONNECTION, COWORK, APPEARANCE, RUNTIME, MEMORY, ABOUT }
private val SETTINGS_PAGES = setOf(Screen.CONNECTION, Screen.COWORK, Screen.APPEARANCE, Screen.RUNTIME, Screen.MEMORY, Screen.ABOUT)

@Composable fun PocketApp(vm: PocketViewModel, open: String? = null, consumeOpen: () -> Unit = {}) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(open) { if (open == "cowork") { if (vm.selected != null) vm.leaveChat(); screen = Screen.COWORK; consumeOpen() } }
    LaunchedEffect(vm.notice) { if (vm.notice.isNotBlank()) { snackbar.showSnackbar(vm.notice, withDismissAction = true); vm.clearNotice() } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(!drawer.isOpen && vm.selected != null) { vm.leaveChat() }
    BackHandler(!drawer.isOpen && vm.selected == null && screen != Screen.HOME) { screen = if (screen in SETTINGS_PAGES) Screen.SETTINGS else Screen.HOME }
    val openDrawer: () -> Unit = { scope.launch { drawer.open() } }
    fun go(target: Screen) { if (vm.selected != null) vm.leaveChat(); screen = target; scope.launch { drawer.close() } }
    CompositionLocalProvider(LocalHaptics provides vm.haptics) {
        ModalNavigationDrawer(drawerState = drawer, scrimColor = Color.Black.copy(alpha = .32f), drawerContent = {
            Drawer(vm, screen,
                newTask = { mode -> vm.changeMode(mode); go(Screen.HOME) },
                tasks = { mode -> vm.changeMode(mode); go(Screen.TASKS) },
                open = { id -> vm.openSession(id); scope.launch { drawer.close() } },
                settings = { go(Screen.SETTINGS) })
        }) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AnimatedContent((vm.selected != null) to screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { (chat, target) ->
                    if (chat) ChatScreen(vm, openDrawer)
                    else when (target) {
                        Screen.HOME -> Home(vm, openDrawer) { screen = Screen.TASKS }
                        Screen.TASKS -> Tasks(vm, openDrawer) { screen = Screen.HOME }
                        Screen.SETTINGS -> Settings(vm, openDrawer) { screen = it }
                        Screen.CONNECTION -> ConnectionSettings(vm) { screen = Screen.SETTINGS }
                        Screen.COWORK -> CoworkSettings(vm) { screen = Screen.SETTINGS }
                        Screen.APPEARANCE -> AppearanceSettings(vm) { screen = Screen.SETTINGS }
                        Screen.ABOUT -> AboutSettings { screen = Screen.SETTINGS }
                        Screen.RUNTIME -> Runtime(vm) { screen = Screen.SETTINGS }
                        Screen.MEMORY -> MemorySettings(vm) { screen = Screen.SETTINGS }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 96.dp)) { data ->
                    Surface(Modifier.padding(horizontal = 20.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            IconButton({ data.dismiss() }, Modifier.size(40.dp)) { GlyphIcon(Glyph.Close, "关闭提示", size = 16.dp) }
                        }
                    }
                }
            }
        }
        vm.pending.firstOrNull()?.let { key(it.text("eventId")) { PendingRequest(vm, it) } }
    }
}

// ───────────────────────────── Drawer ─────────────────────────────

@Composable private fun Drawer(vm: PocketViewModel, screen: Screen, newTask: (TaskMode) -> Unit, tasks: (TaskMode) -> Unit, open: (String) -> Unit, settings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    val pendingAgents = vm.pending.map { it.text("agentId") }.toSet()
    val recents = vm.sessions.filter { it.text("sessionId") !in vm.archived && !it.optBoolean("blank") && (query.isBlank() || sessionTitle(it).contains(query.trim(), true)) }
        .sortedByDescending { it.text("sessionId") in vm.pinned }
    ModalDrawerSheet(Modifier.width(312.dp), drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = if (Pocket.dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface, drawerTonalElevation = 0.dp) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.weight(1f), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Search, null, size = 17.dp, tint = Pocket.muted); Spacer(Modifier.width(8.dp))
                        BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(Pocket.accent), decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索任务", style = MaterialTheme.typography.bodyMedium, color = Pocket.faint); inner() } })
                    }
                }
            }
            DrawerItem("新任务", Glyph.Plus, selected = false, ink = true) { newTask(vm.mode) }
            DrawerItem("Cowork", Glyph.Cowork, selected = screen == Screen.TASKS && vm.mode == TaskMode.COWORK && vm.selected == null,
                badge = if (!vm.modeAvailable(TaskMode.COWORK)) "未配置" else null) { tasks(TaskMode.COWORK) }
            DrawerItem("Code", Glyph.Code, selected = screen == Screen.TASKS && vm.mode == TaskMode.CODE && vm.selected == null) { tasks(TaskMode.CODE) }
            Text("最近", Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                items(recents, key = { it.text("sessionId") }) { row ->
                    val id = row.text("sessionId"); val active = vm.selected == id
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .combinedClickable(onClick = { open(id) }, onLongClick = { menu = row }).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(sessionTitle(row), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                        if (id in vm.pinned) { Spacer(Modifier.width(6.dp)); GlyphIcon(Glyph.Pin, "已置顶", size = 13.dp, tint = Pocket.faint) }
                        when {
                            needsInput(row, pendingAgents) -> { Spacer(Modifier.width(8.dp)); ContinuoCursor(height = 13.dp, color = Pocket.warning, mode = CursorMode.STEADY) }
                            row.optBoolean("running") -> { Spacer(Modifier.width(8.dp)); ContinuoCursor(height = 13.dp) }
                        }
                    }
                }
                if (recents.isEmpty()) item { Text(if (vm.connected) "还没有任务" else "连接 DSH 后显示任务", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Pocket.faint) }
            }
            HorizontalDivider(color = Pocket.hairline)
            Row(Modifier.fillMaxWidth().clickable(onClick = settings).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ContinuoMark(size = 34.dp, mode = if (vm.connected) CursorMode.STEADY else CursorMode.STEADY, cursor = if (vm.connected) Pocket.accent else Pocket.faint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("本机 DSH", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (vm.connected) Pocket.success else if (vm.connecting) Pocket.warning else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(6.dp))
                        Text(if (vm.connected) "已连接 · 127.0.0.1:3080" else if (vm.connecting) "正在连接…" else "未连接", style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
                    }
                }
                GlyphIcon(Glyph.Settings, "设置", size = 20.dp, tint = Pocket.muted)
            }
        }
    }
    menu?.let { row -> SessionMenu(vm, row, { menu = null }) }
}

@Composable private fun DrawerItem(title: String, icon: ImageVector, selected: Boolean, ink: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
        .clickable(role = Role.Button) { haptic(); onClick() }.semantics { this.selected = selected }.heightIn(min = 46.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (ink) Surface(Modifier.size(26.dp), shape = CircleShape, color = Pocket.accent) { Box(contentAlignment = Alignment.Center) { GlyphIcon(icon, null, size = 16.dp, tint = Color.White) } }
        else Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { GlyphIcon(icon, null, size = 20.dp) }
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (ink) Pocket.accentPressed.takeIf { !Pocket.dark } ?: Pocket.accent else MaterialTheme.colorScheme.onSurface)
        badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pocket.faint) }
    }
}

// ───────────────────────────── Home (start screen) ─────────────────────────────

@Composable private fun ModeSwitch(vm: PocketViewModel) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(3.dp)) {
            TaskMode.entries.forEach { mode ->
                val active = vm.mode == mode
                val haptic = rememberHaptic()
                Surface(onClick = { haptic(); vm.changeMode(mode) }, shape = CircleShape,
                    color = if (active) (if (Pocket.dark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White) else Color.Transparent,
                    shadowElevation = if (active && !Pocket.dark) 1.dp else 0.dp, modifier = Modifier.semantics { selected = active }) {
                    Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(if (mode == TaskMode.CODE) Glyph.Code else Glyph.Cowork, null, size = 16.dp, tint = if (active) MaterialTheme.colorScheme.onSurface else Pocket.muted)
                        Spacer(Modifier.width(6.dp))
                        Text(mode.title, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.onSurface else Pocket.muted)
                    }
                }
            }
        }
    }
}

@Composable private fun Home(vm: PocketViewModel, openDrawer: () -> Unit, openTasks: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val seed = rememberSaveable { (0..997).random() }
    val now = remember { Calendar.getInstance() }
    val title = remember(seed) { greeting(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.DAY_OF_WEEK), seed) }
    val available = vm.modeAvailable(vm.mode)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.Menu, "打开菜单", onClick = openDrawer)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ModeSwitch(vm) }
            CircleButton(Glyph.List, "${vm.mode.title} 任务", onClick = openTasks)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(24.dp))
            ContinuoMark(size = 44.dp, mode = if (vm.starting) CursorMode.BLINK else CursorMode.STEADY)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, fontFamily = Fonts.serif, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(when (vm.mode) {
                TaskMode.CODE -> "在 DSH 中编写、调试与运行代码"
                TaskMode.COWORK -> "DSH 将在手机副屏上替你完成操作"
            }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
            if (vm.connected && !available) {
                Spacer(Modifier.height(20.dp))
                InsetGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.Info, null, size = 18.dp, tint = Pocket.accent); Spacer(Modifier.width(8.dp))
                            Text("${vm.mode.title} 尚未配置", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(if (vm.presets == null) "无法读取 DSH 的模式列表，请稍后重试。"
                            else "DSH 中还没有名为 “${vm.mode.preset}” 的模式。建立该模式后，这里即可开始 ${vm.mode.title} 任务。",
                            style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (!vm.connected) ConnectCard(vm, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val placeholder = when {
            !available -> "${vm.mode.title} 尚未配置"
            vm.mode == TaskMode.CODE -> "描述任务或提出问题"
            else -> "让 DSH 在副屏上替你做什么？"
        }
        Composer(vm, placeholder, Modifier.imePadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), enabled = available, onSend = { vm.startTask() }) {
            if (vm.mode == TaskMode.CODE) {
                val workspace = vm.workspaces.firstOrNull { it.text("workspaceId") == vm.homeWorkspace }
                Pill(workspace?.let { it.text("title").ifBlank { workspaceLabel(it.text("path")) } } ?: "workspace", leading = Glyph.Folder) { sheet = "workspace" }
            }
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

@Composable private fun ConnectCard(vm: PocketViewModel, modifier: Modifier = Modifier) {
    var manual by remember { mutableStateOf(false) }
    InsetGroup(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (vm.connecting) Pocket.warning else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(8.dp))
                Text(if (vm.connecting) "正在连接本机 DSH…" else "未连接到本机 DSH", style = MaterialTheme.typography.titleSmall)
            }
            Text("聊天连接无需 Root；首次可通过 KernelSU 读取本机令牌，或手动输入。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Root 连接", Modifier.weight(1f), enabled = !vm.connecting) { vm.connectRoot() }
                SecondaryButton("输入令牌", Modifier.weight(1f), enabled = !vm.connecting) { manual = true }
            }
        }
    }
    if (manual) InputDialog("输入连接令牌", "DSH Web 的访问令牌，只保存在本机加密存储中", "", { manual = false }, secret = true) { vm.connectManual(it); manual = false }
}

// ───────────────────────────── Task list (Code / Cowork) ─────────────────────────────

@Composable private fun Tasks(vm: PocketViewModel, openDrawer: () -> Unit, newTask: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(SessionFilter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pendingAgents = vm.pending.map { it.text("agentId") }.toSet()
    val now = System.currentTimeMillis()
    val rows = vm.sessions.filter { sessionMode(it) == vm.mode && matchesFilter(it, filter, vm.archived, pendingAgents) && !(it.optBoolean("blank") && filter == SessionFilter.ALL && it.text("sessionId") !in vm.pinned) }
        .filter { query.isBlank() || sessionTitle(it).contains(query.trim(), true) || it.text("cwd").contains(query.trim(), true) }
        .sortedWith(compareByDescending<JSONObject> { it.text("sessionId") in vm.pinned }.thenByDescending { it.optLong("updatedAt") })
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.Menu, "打开菜单", onClick = openDrawer)
            Spacer(Modifier.weight(1f))
            CircleButton(Glyph.Plus, "新建 ${vm.mode.title} 任务", onClick = newTask)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.mode.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
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
                if (vm.mode == TaskMode.COWORK && !vm.modeAvailable(TaskMode.COWORK)) item {
                    InsetGroup(Modifier.padding(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cowork 尚未配置", style = MaterialTheme.typography.titleSmall)
                            Text("在 DSH 中建立 “cowork” 模式后，即可从首页开始操控副屏的任务。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                        }
                    }
                }
                items(rows, key = { it.text("sessionId") }) { row -> TaskRow(vm, row, now, pendingAgents) { menu = row } }
                if (rows.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        GlyphIcon(if (vm.mode == TaskMode.CODE) Glyph.Code else Glyph.Cowork, null, size = 28.dp, tint = Pocket.faint)
                        Spacer(Modifier.height(10.dp))
                        Text(when { !vm.connected -> "连接 DSH 后显示任务"; query.isNotBlank() -> "没有匹配的任务"; filter != SessionFilter.ALL -> "没有${filter.label}的任务"; else -> "还没有 ${vm.mode.title} 任务" },
                            style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                    }
                }
            }
        }
    }
    menu?.let { row -> SessionMenu(vm, row, { menu = null }) }
}

@Composable private fun TaskRow(vm: PocketViewModel, row: JSONObject, now: Long, pendingAgents: Set<String>, more: () -> Unit) {
    val id = row.text("sessionId")
    val running = row.optBoolean("running"); val waiting = needsInput(row, pendingAgents)
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.openSession(id) }, onLongClick = { haptic(); more() })) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (id in vm.pinned) { GlyphIcon(Glyph.Pin, "已置顶", size = 14.dp, tint = Pocket.faint); Spacer(Modifier.width(5.dp)) }
                    Text(sessionTitle(row), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Pocket.success.copy(alpha = .12f)) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.Folder, null, size = 12.dp, tint = Pocket.success); Spacer(Modifier.width(4.dp))
                            Text(workspaceLabel(row.text("cwd")), style = MaterialTheme.typography.labelSmall, color = Pocket.success, maxLines = 1)
                        }
                    }
                    presetTag(row)?.let { Spacer(Modifier.width(6.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = Pocket.faint) }
                    Spacer(Modifier.width(8.dp))
                    Text(relativeTime(row.optLong("updatedAt"), now), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                    if (waiting || running) {
                        Spacer(Modifier.width(10.dp)); ContinuoCursor(height = 12.dp, color = if (waiting) Pocket.warning else Pocket.accent, mode = if (waiting) CursorMode.STEADY else CursorMode.BLINK); Spacer(Modifier.width(6.dp))
                        Text(if (waiting) "需要输入" else "运行中", style = MaterialTheme.typography.labelSmall, color = if (waiting) Pocket.warning else Pocket.accent)
                    }
                }
            }
            IconButton(more, Modifier.size(40.dp)) { GlyphIcon(Glyph.More, "任务选项", size = 18.dp, tint = Pocket.muted) }
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = Pocket.hairline)
    }
}

/** Session actions: rename, pin, archive. Archive never deletes records or files. */
@Composable internal fun SessionMenu(vm: PocketViewModel, row: JSONObject, dismiss: () -> Unit, inChat: Boolean = false, openJobs: (() -> Unit)? = null, openPermission: (() -> Unit)? = null) {
    val id = row.text("sessionId")
    var rename by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    val archived = id in vm.archived; val pinned = id in vm.pinned
    if (!rename && !archive) PocketSheet(dismiss) {
        SheetHeader(sessionTitle(row), listOf(sessionMode(row).title, workspaceLabel(row.text("cwd"))).joinToString(" · "))
        if (inChat) {
            openPermission?.let { SheetRow("工具权限", permissionLabel(vm.currentPermission()), Glyph.Shield) { it() } }
            SheetRow("工作区已锁定", row.text("cwd"), Glyph.Folder, enabled = false) {}
            openJobs?.let { SheetRow("后台任务", icon = Glyph.Terminal) { it() } }
        }
        SheetRow("重命名", icon = Glyph.Pencil) { rename = true }
        SheetRow(if (pinned) "取消置顶" else "置顶", icon = Glyph.Pin) { vm.pin(id, unpin = pinned); dismiss() }
        SheetRow(if (archived) "恢复" else "归档", icon = Glyph.Archive) { if (archived) { vm.archive(id, restore = true); dismiss() } else archive = true }
    }
    if (rename) InputDialog("重命名任务", "新的标题", sessionTitle(row), { rename = false; dismiss() }) { vm.rename(id, it); rename = false; dismiss() }
    if (archive) ConfirmDialog("归档此任务？", "不会删除会话记录或工作区文件，可在“已归档”中恢复。运行中的任务请先停止。", "归档", dismiss = { archive = false; dismiss() }) {
        vm.archive(id); archive = false; dismiss()
    }
}

// ───────────────────────────── Runtime ─────────────────────────────

@Composable internal fun Runtime(vm: PocketViewModel, back: () -> Unit) {
    var confirm by remember { mutableStateOf<RuntimeController.Action?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.ArrowLeft, "返回", onClick = back)
            Text("运行环境", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(40.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            SectionLabel("状态")
            InsetGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.runtimeBusy) Spinner(size = 14.dp, color = Pocket.accent) else StatusDot(if (vm.connected) Pocket.success else Pocket.faint)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.runtimeBusy) "正在执行…" else if (vm.connected) "DSH 正在运行" else "DSH 未连接", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton({ vm.runtime(RuntimeController.Action.STATUS) }, enabled = !vm.runtimeBusy) { Text("刷新", color = Pocket.accent, style = MaterialTheme.typography.labelLarge) }
                    }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Pocket.codeBackground) {
                        Text(vm.runtimeStatus, Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(12.dp), fontFamily = Fonts.mono,
                            style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                    }
                }
            }
            SectionLabel("DSH 服务")
            InsetGroup {
                GroupRow("启动 DSH", icon = Glyph.Play, enabled = !vm.runtimeBusy, chevron = false, onClick = { vm.runtime(RuntimeController.Action.START) })
                GroupDivider()
                GroupRow("重启 DSH 与 Node", icon = Glyph.Restart, enabled = !vm.runtimeBusy, chevron = false, onClick = { confirm = RuntimeController.Action.RESTART })
                GroupDivider()
                GroupRow("停止 DSH", icon = Glyph.Power, enabled = !vm.runtimeBusy, chevron = false, tint = MaterialTheme.colorScheme.error, onClick = { confirm = RuntimeController.Action.STOP })
            }
            SectionLabel("Linux 环境")
            InsetGroup {
                GroupRow("重建 Linux 环境", icon = Glyph.Layers, subtitle = "停止 DSH，确认无残留进程后重建私有挂载并启动", enabled = !vm.runtimeBusy, chevron = false,
                    onClick = { confirm = RuntimeController.Action.RECREATE_ENVIRONMENT })
            }
            Text("这里的 Linux 是 Ubuntu chroot，不是独立虚拟机，也不是安全边界。所有操作只调用固定的 pocket-control.sh 动作，不执行任意命令。",
                Modifier.padding(horizontal = 8.dp, vertical = 16.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Spacer(Modifier.height(24.dp))
        }
    }
    confirm?.let { action ->
        val (title, text) = when (action) {
            RuntimeController.Action.RESTART -> "重启 DSH？" to "正在运行的任务会被中断，重启后自动重新连接。"
            RuntimeController.Action.STOP -> "停止 DSH？" to "所有任务都会中断，应用将断开连接，直到再次启动。"
            else -> "重建 Linux 环境？" to "将停止 DSH 并重建私有挂载后再次启动；文件与凭据保留。若仍有后台进程在 chroot 中运行，操作会被拒绝。"
        }
        ConfirmDialog(title, text, "继续", destructive = action != RuntimeController.Action.RESTART, dismiss = { confirm = null }) { vm.runtime(action); confirm = null }
    }
}
