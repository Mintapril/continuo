@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.pocket.BuildConfig
import dev.dsh.pocket.CallTileService
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.R
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.IdentityRules
import dev.dsh.pocket.data.IdentityStore
import dev.dsh.pocket.data.TaskMode
import dev.dsh.pocket.data.UnattendedModels
import dev.dsh.pocket.data.objects
import dev.dsh.pocket.island.IslandAccessibilityService
import dev.dsh.pocket.island.IslandLayerKeeper
import dev.dsh.pocket.island.IslandRuntime
import dev.dsh.pocket.notify.NotifyCenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Settings are a hub plus one page per topic. The hub only shows live status; changes happen on the topic pages.

@Composable private fun Switch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    androidx.compose.material3.Switch(checked, onChange, enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = Pocket.accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = Pocket.hairline))

/** Common frame of a settings sub page: back button, centred title, scrolling content. */
@Composable internal fun SettingsPage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.ArrowLeft, "返回", onClick = back)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(40.dp))
        }
        // Rows that come and go (a status that changes, a section that opens) move the rest smoothly.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).animateContentSize()) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable fun Footnote(text: String) =
    Text(text, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)

private fun dshStatus(vm: PocketViewModel) = if (vm.connected) "已连接" else if (vm.connecting) "连接中" else "未连接"

/** Colour + one-line description of the executor state, shared by the hub and the phone page. */
@Composable private fun executorSummary(link: CoworkLinkState): Pair<Color, String> = when (link) {
    CoworkLinkState.CONNECTED -> Pocket.success to "已连上它的手机"
    CoworkLinkState.STARTING, CoworkLinkState.AUTHENTICATING, CoworkLinkState.CONNECTING -> Pocket.warning to "正在连接它的手机…"
    CoworkLinkState.LOST -> Pocket.warning to "连接中断，正在恢复"
    CoworkLinkState.FAILED -> MaterialTheme.colorScheme.error to "连不上它的手机"
    CoworkLinkState.DISCONNECTED -> Pocket.faint to "待命（有事时自动连上）"
}

/** Re-read permission state whenever the page comes back to the front (e.g. from system settings). */
@Composable private fun rememberResumeTick(): Int {
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return refresh
}

// ───────────────────────────── Hub ─────────────────────────────

@Composable internal fun Settings(vm: PocketViewModel, back: () -> Unit, navigate: (Screen) -> Unit) {
    val link by CoworkConnection.connection.collectAsState()
    val (executorColor, executorText) = executorSummary(link)
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    remember { NotifyCenter.init(context) }
    val notifyOn by NotifyCenter.enabled.collectAsState()
    var developer by remember { mutableStateOf(false) }
    SettingsPage("设置", back) {
        Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.clickable(role = Role.Button) { navigate(Screen.IDENTITY) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(identity.shown, style = MaterialTheme.typography.titleLarge)
                    Text("名字和头像", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                }
                GlyphIcon(Glyph.ChevronRight, null, size = 18.dp, tint = Pocket.faint)
            }
        }
        SectionLabel("${identity.shown}能做什么")
        InsetGroup {
            GroupRow("它的手机", icon = Glyph.Phone, subtitle = executorText, onClick = { navigate(Screen.COWORK) }, trailing = {
                StatusDot(executorColor, pulse = link.connecting); Spacer(Modifier.width(6.dp))
            })
            GroupDivider()
            GroupRow("通知叫醒", icon = Glyph.Filter, subtitle = if (notifyOn) "开着：有意义的通知会叫它去看" else "没开", onClick = { navigate(Screen.NOTIFY) })
            GroupDivider()
            GroupRow("记忆", icon = Glyph.Brain, subtitle = when {
                vm.memory.available == false -> "插件未安装"
                vm.memory.available == null -> "它记得的人和事"
                vm.memory.archivedTotal > 0 -> "${vm.memory.total} 条记忆，${vm.memory.archivedTotal} 条已归档"
                else -> "${vm.memory.total} 条记忆"
            }, onClick = { navigate(Screen.MEMORY) })
        }
        SectionLabel("这台手机")
        InsetGroup {
            GroupRow("连接", icon = Glyph.Link, subtitle = "本机 DSH 的令牌与重连", onClick = { navigate(Screen.CONNECTION) }, trailing = {
                StatusDot(if (vm.connected) Pocket.success else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(6.dp))
                Text(dshStatus(vm), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
            GroupDivider()
            GroupRow("运行环境", icon = Glyph.Server, subtitle = "DSH 与 Linux 环境，需要 KernelSU 授权", onClick = { navigate(Screen.RUNTIME) })
            GroupDivider()
            GroupRow("外观与反馈", icon = Glyph.Palette, value = when (vm.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, onClick = { navigate(Screen.APPEARANCE) })
            GroupDivider()
            GroupRow("关于", icon = Glyph.Info, value = BuildConfig.VERSION_NAME, onClick = { navigate(Screen.ABOUT) })
        }
        Spacer(Modifier.height(12.dp))
        InsetGroup {
            val turn by animateFloatAsState(if (developer) 180f else 0f, tween(220), label = "chevron")
            GroupRow("开发者", icon = Glyph.Code, subtitle = if (developer) null else "Code 任务、全部会话", chevron = false,
                trailing = { GlyphIcon(Glyph.ChevronDown, null, Modifier.graphicsLayer { rotationZ = turn }, size = 16.dp, tint = Pocket.faint) }, onClick = { developer = !developer })
            AnimatedVisibility(developer, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { Column {
                GroupDivider()
                GroupRow("Code 任务与全部会话", icon = Glyph.Terminal, subtitle = "DSH 的原始会话列表，可新建 Code 任务", onClick = { navigate(Screen.DEVELOPER) })
                GroupDivider()
                GroupRow("重新走一遍新装引导", icon = Glyph.Restart, onClick = { navigate(Screen.ONBOARDING) })
            } }
        }
    }
}

// ───────────────────────────── Identity ─────────────────────────────

@Composable internal fun IdentitySettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    var naming by remember { mutableStateOf(false) }
    SettingsPage("名字和头像", back) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Crossfade(identity.avatar, animationSpec = tween(260), label = "avatar") { key -> Avatar(key, StatusCard.Mood.HAPPY, identity.shown, size = 140.dp) }
            Spacer(Modifier.height(10.dp))
            Text(identity.shown, style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif)
            TextButton({ naming = true }) { Text(if (identity.name.isBlank()) "给它起个名字" else "改名字", color = Pocket.accent) }
        }
        SectionLabel("头像")
        AvatarPicker(identity.avatar, identity.shown) { IdentityStore.setAvatar(context, it) }
        Footnote("名字会告诉它（写进给它的设定），通知里也用这个名字；它在各个应用里的昵称不变。头像只存在这台手机上。")
    }
    if (naming) InputDialog("它叫什么？", "最多 ${IdentityRules.MAX_NAME} 个字，不能叫“通奏”", identity.name, { naming = false }) { value ->
        val problem = IdentityRules.problem(value)
        if (problem != null) vm.showError(problem) else { vm.nameCompanion(value); naming = false }
    }
}

/** The stickers to choose from, three to a row; the chosen one is ringed. */
@Composable internal fun AvatarPicker(selected: String, name: String, pick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IdentityRules.AVATARS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val on = key == selected
                    val tile by animateColorAsState(if (on) Pocket.accentSubtle else MaterialTheme.colorScheme.surfaceContainerLow, tween(200), label = "sticker")
                    val grow by animateFloatAsState(if (on) 1f else 0.94f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "sticker size")
                    Box(Modifier.weight(1f).aspectRatio(1f).graphicsLayer { scaleX = grow; scaleY = grow }.clip(RoundedCornerShape(20.dp))
                        .background(tile)
                        .clickable(role = Role.RadioButton) { pick(key) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                        Avatar(key, StatusCard.Mood.IDLE, name, size = 88.dp, animate = on)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ───────────────────────────── Connection ─────────────────────────────

@Composable internal fun ConnectionSettings(vm: PocketViewModel, back: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    SettingsPage("连接", back) {
        SectionLabel("本机 DSH")
        InsetGroup {
            GroupRow("状态", icon = Glyph.Link, subtitle = "http://127.0.0.1:3080", chevron = false, trailing = {
                StatusDot(if (vm.connected) Pocket.success else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(6.dp))
                Text(dshStatus(vm), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
            GroupDivider()
            GroupRow("通过 Root 重新连接", icon = Glyph.Refresh, subtitle = "自动读取本机令牌", enabled = !vm.connecting, onClick = { vm.connectRoot() })
            GroupDivider()
            GroupRow("手动输入令牌", icon = Glyph.Key, subtitle = "保存在本机加密存储", enabled = !vm.connecting, onClick = { manual = true })
        }
        Footnote("打开应用时会自动使用已保存的令牌连接；只有令牌失效或 DSH 重新生成令牌时才需要在这里重连。")
        SectionLabel("DSH 里的模式")
        InsetGroup {
            TaskMode.entries.forEachIndexed { index, mode ->
                if (index > 0) GroupDivider()
                GroupRow(if (mode == TaskMode.CODE) "Code（开发者）" else "它的手机", icon = if (mode == TaskMode.CODE) Glyph.Code else Glyph.Phone, chevron = false,
                    subtitle = "预设 ${mode.preset}",
                    value = when { vm.presets == null -> "未知"; vm.modeAvailable(mode) -> "可用"; else -> "未配置" })
            }
        }
    }
    if (manual) InputDialog("输入连接令牌", "DSH Web 的访问令牌，只保存在本机加密存储中", "", { manual = false }, secret = true) { vm.connectManual(it); manual = false }
}

// ───────────────────────────── Its phone ─────────────────────────────

@Composable internal fun CoworkSettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by CoworkConnection.connection.collectAsState()
    val executorError by CoworkConnection.error.collectAsState()
    var auto by remember { mutableStateOf(CoworkControllerService.autoConnect(context)) }
    var overlayDialog by remember { mutableStateOf(false) }
    val refresh = rememberResumeTick()
    var settle by remember { mutableIntStateOf(0) }
    val overlayGranted = remember(refresh, settle) { AndroidSettings.canDrawOverlays(context) }
    val aboveStatusBar = remember(refresh, settle) { IslandAccessibilityService.available }
    var keepLayer by remember { mutableStateOf(IslandLayerKeeper.enabled(context)) }
    val notificationsGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settle++; if (!granted) vm.showError("通知权限没开：它的状态卡和“找你”都看不到")
    }
    val (executorColor, executorText) = executorSummary(link)
    val unattended = vm.unattended
    var modelFor by remember { mutableStateOf<String?>(null) }
    val name = IdentityStore.flow(context).collectAsState().value.shown
    LaunchedEffect(vm.connected) { if (vm.connected) { vm.unattendedRefresh(); if (vm.catalog.objects("groups").isEmpty()) vm.refresh() } }

    SettingsPage("它的手机", back) {
        SectionLabel("连接")
        InsetGroup {
            GroupRow("它的手机", icon = Glyph.Phone, subtitle = if (link == CoworkLinkState.FAILED) executorError.ifBlank { link.hint } else executorText,
                chevron = false, trailing = { if (link.connecting) Spinner(size = 14.dp, color = Pocket.accent) else StatusDot(executorColor) })
            if (link in setOf(CoworkLinkState.FAILED, CoworkLinkState.LOST, CoworkLinkState.DISCONNECTED)) {
                GroupDivider()
                GroupRow("立即重试", icon = Glyph.Refresh, chevron = false, tint = Pocket.accent,
                    onClick = { runCatching { CoworkControllerService.startUserInitiated(context) }.onFailure { vm.showError(it.message ?: "连不上它的手机") } })
            }
            GroupDivider()
            GroupRow("自动连接", icon = Glyph.Link, subtitle = "打开应用或有事要做时自动连上", chevron = false, trailing = {
                Switch(auto) { auto = it; CoworkControllerService.setAutoConnect(context, it) }
            })
        }
        Footnote("它住在工作资料里，用一块你看不见的副屏；你的应用和数据它碰不到。连接需要 KernelSU 对 Continuo 的授权；上滑清除最近任务会被部分系统强行结束，请在最近任务里锁定 Continuo。")

        SectionLabel("${name}用的模型")
        InsetGroup {
            if (unattended == null) {
                GroupRow(if (vm.unattendedError.isBlank()) "正在读取…" else "读不到设置", icon = Glyph.Warning, chevron = false,
                    subtitle = vm.unattendedError.ifBlank { null }?.let { "$it（手机上的 Cowork 插件可能还是旧版）" }, onClick = { vm.unattendedRefresh() })
            } else {
                GroupRow("你叫${name}时", icon = Glyph.Chat, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.OWNER), vm.catalog),
                    onClick = { modelFor = UnattendedModels.OWNER })
                GroupDivider()
                GroupRow("巡视", icon = Glyph.Clock, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.PATROL), vm.catalog),
                    onClick = { modelFor = UnattendedModels.PATROL })
                GroupDivider()
                GroupRow("被通知叫醒", icon = Glyph.Filter, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.NOTIFY), vm.catalog),
                    onClick = { modelFor = UnattendedModels.NOTIFY })
            }
        }
        Footnote("“你叫${name}时”管你们俩的对话里的每一轮，包括在那里到点的便条；巡视和被通知叫醒设了自己的就用自己的。没设的用会话自己的模型（新会话是 DSH 的默认模型）。改这里不动 DSH 的默认模型。")

        SectionLabel("放进你的手机")
        InsetGroup {
            GroupRow("通知", icon = Glyph.Vibrate, subtitle = "它的状态卡、“找你”和便条回执", value = if (notificationsGranted) "已允许" else "未允许",
                chevron = !notificationsGranted, onClick = if (notificationsGranted) null else ({ if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }))
            GroupDivider()
            GroupRow("显示在其他应用上层", icon = Glyph.Shield, subtitle = "看它的手机、接手时用", value = if (overlayGranted) "已允许" else "未允许",
                onClick = { if (overlayGranted) context.startActivity(IslandRuntime.overlaySettingsIntent(context)) else overlayDialog = true })
            GroupDivider()
            GroupRow("快捷开关“叫$name”", icon = Glyph.Plus, subtitle = "下拉快捷设置里一点就叫它", chevron = false, tint = Pocket.accent,
                onClick = { addTile(context, name) { vm.showError(it) } })
            GroupDivider()
            val assistant = remember(refresh) { dev.dsh.pocket.assist.AssistService.isAssistant(context) }
            GroupRow("当系统的助手", icon = Glyph.Mic, value = if (assistant) "已设" else "没设",
                subtitle = if (assistant) "系统的助手手势会叫$name，不读你屏幕上的内容" + (if (overlayGranted) "" else "；要先允许显示在其他应用上层")
                    else "在系统设置的“数字助理应用”里选 Continuo",
                onClick = { dev.dsh.pocket.assist.AssistService.settingsIntent(context)?.let { context.startActivity(it) } ?: vm.showError("这台手机找不到选择助手的设置页") })
            GroupDivider()
            GroupRow("盖住状态栏（可选）", icon = Glyph.ArrowUp, chevron = false, subtitle = when {
                aboveStatusBar && keepLayer -> "已生效；被系统关闭后会用 root 自动重新开启"
                aboveStatusBar -> "已生效；被系统关闭后不会自动恢复"
                keepLayer -> "正在用 root 开启无障碍服务…"
                else -> "没开：看它的手机时，状态栏的时间和图标会盖在画面上"
            }, trailing = {
                Switch(keepLayer) { on ->
                    keepLayer = on
                    scope.launch {
                        IslandLayerKeeper.setEnabled(context, on).onFailure {
                            keepLayer = false; IslandLayerKeeper.setEnabled(context, false)
                            vm.showError("无法用 root 开启无障碍服务：${it.message}。请检查 KernelSU 对 Continuo 的授权")
                        }
                        delay(1_500); settle++ // the system binds the service a moment after the setting changes
                    }
                }
            })
        }
        Footnote("“盖住状态栏”借无障碍服务的窗口层级，它什么都不读。工作资料里的应用由它像普通人一样用；花钱、账号安全和清空聊天记录仍然要你。")
    }

    modelFor?.let { kind ->
        ModelSheet(vm.catalog, UnattendedModels.selection(unattended, kind), { modelFor = null }, title = when (kind) { UnattendedModels.PATROL -> "巡视用的模型"; UnattendedModels.OWNER -> "你叫${name}时用的模型"; else -> "被通知叫醒时用的模型" },
            clear = { vm.setUnattendedModel(kind, null, null, null); modelFor = null }) { p, m, e -> vm.setUnattendedModel(kind, p, m, e); modelFor = null }
    }
    if (overlayDialog) ConfirmDialog("允许显示在其他应用上层", "将打开 Android 系统设置，请手动允许 Continuo。应用不会自行授予任何权限。", "前往设置", dismiss = { overlayDialog = false }) {
        overlayDialog = false; context.startActivity(IslandRuntime.overlaySettingsIntent(context))
    }
}

/** Asks the system to add the 叫它 tile (Android 13+ shows its own confirmation); older systems need the edit panel. */
internal fun addTile(context: android.content.Context, name: String, report: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < 33) { report("下拉快捷设置，点编辑，把“叫它”拖进去"); return }
    val manager = context.getSystemService(StatusBarManager::class.java) ?: run { report("这台手机不支持直接添加，请在快捷设置的编辑里添加"); return }
    manager.requestAddTileService(ComponentName(context, CallTileService::class.java), "叫$name", Icon.createWithResource(context, R.drawable.island_notification),
        ContextCompat.getMainExecutor(context)) { result ->
        when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> report("加好了：下拉快捷设置就能叫它")
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> report("快捷设置里已经有了")
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
            else -> report("没加上（$result），可以在快捷设置的编辑里手动添加")
        }
    }
}

// ───────────────────────────── Appearance ─────────────────────────────

@Composable internal fun AppearanceSettings(vm: PocketViewModel, back: () -> Unit) {
    SettingsPage("外观与反馈", back) {
        SectionLabel("主题")
        InsetGroup {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("light" to "浅色", "system" to "跟随系统", "dark" to "深色").forEach { (value, label) ->
                    val active = vm.theme == value
                    Surface(onClick = { vm.changeTheme(value) }, Modifier.weight(1f).semantics { selected = active }, shape = RoundedCornerShape(12.dp),
                        color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) MaterialTheme.colorScheme.onSurface else Pocket.hairline)) {
                        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ThemeSwatch(value); Spacer(Modifier.height(8.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        SectionLabel("反馈")
        InsetGroup {
            GroupRow("触感反馈", icon = Glyph.Vibrate, chevron = false, trailing = { Switch(vm.haptics) { vm.changeHaptics(it) } })
        }
    }
}

@Composable private fun ThemeSwatch(value: String) {
    val light = Color(0xFFECE9E1); val dark = Color(0xFF1B1D21)
    Surface(Modifier.size(width = 44.dp, height = 30.dp), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Pocket.hairline)) {
        Row {
            when (value) {
                "light" -> Box(Modifier.fillMaxSize().background(light))
                "dark" -> Box(Modifier.fillMaxSize().background(dark))
                else -> { Box(Modifier.weight(1f).fillMaxHeight().background(light)); Box(Modifier.weight(1f).fillMaxHeight().background(dark)) }
            }
        }
    }
}

// ───────────────────────────── About ─────────────────────────────

@Composable internal fun AboutSettings(back: () -> Unit) {
    var licenses by remember { mutableStateOf(false) }
    SettingsPage("关于", back) {
        Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ContinuoMark(size = 64.dp)
            Spacer(Modifier.height(14.dp))
            Text("Continuo", style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Text("通奏", style = MaterialTheme.typography.labelLarge, color = Pocket.muted, letterSpacing = 4.sp)
        }
        SectionLabel("应用")
        InsetGroup {
            GroupRow("版本", icon = Glyph.Info, value = BuildConfig.VERSION_NAME, chevron = false)
            GroupDivider()
            GroupRow("字体与许可", icon = Glyph.File, subtitle = "系统无衬线 · JetBrains Mono", onClick = { licenses = true })
        }
    }
    if (licenses) PocketSheet({ licenses = false }) {
        SheetHeader("字体与许可")
        val text = "界面：系统无衬线字体（Roboto / Noto Sans SC）\n代码与日志：JetBrains Mono（SIL Open Font License 1.1）\n字标：Cormorant Garamond Italic（SIL OFL 1.1），已转为轮廓\n\n界面不内置无衬线字体；中文由系统字体显示。"
        Text(text, Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
    }
}
