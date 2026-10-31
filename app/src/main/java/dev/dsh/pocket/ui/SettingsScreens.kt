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
import dev.dsh.notifykeep.ChatKeepPolicy
import dev.dsh.pocket.cowork.ChatKeeperRuntime
import dev.dsh.pocket.cowork.ChatKeeperStatus
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.IdentityRules
import dev.dsh.pocket.data.IdentityStore
import dev.dsh.pocket.data.RuntimeController
import dev.dsh.pocket.data.TaskMode
import dev.dsh.pocket.data.UnattendedModels
import dev.dsh.pocket.data.objects
import dev.dsh.pocket.island.IslandAccessibilityService
import dev.dsh.pocket.island.IslandLayerKeeper
import dev.dsh.pocket.island.IslandRuntime
import dev.dsh.pocket.notify.NotifyCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Settings are a hub plus one page per topic. The hub only shows live status; changes happen on the topic pages.

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

private fun notificationsGranted(context: android.content.Context) =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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
                    Text("名字、头像、性格和用的模型", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
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
            GroupRow("通知叫醒", icon = Glyph.Filter, subtitle = when {
                !notifyOn -> "没开"
                vm.jevConfigured == false -> "开着，但还没设 Jev 密钥"
                else -> "开着：有意义的通知会叫它去看"
            }, onClick = { navigate(Screen.NOTIFY) })
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
            val refresh = rememberResumeTick()
            val missing = remember(refresh) {
                listOfNotNull("通知".takeIf { !notificationsGranted(context) }, "悬浮窗".takeIf { !AndroidSettings.canDrawOverlays(context) })
            }
            GroupRow("权限与入口", icon = Glyph.Shield, subtitle = if (missing.isEmpty()) "通知、悬浮窗、快捷开关、系统助手" else "没允许：${missing.joinToString("、")}",
                onClick = { navigate(Screen.ENTRY) }, trailing = { if (missing.isNotEmpty()) { StatusDot(Pocket.warning); Spacer(Modifier.width(6.dp)) } })
            GroupDivider()
            GroupRow("本机 DSH", icon = Glyph.Server, subtitle = "连接、启动与 Linux 环境", onClick = { navigate(Screen.DSH) }, trailing = {
                StatusDot(if (vm.connected) Pocket.success else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(6.dp))
                Text(dshStatus(vm), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
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

@Composable internal fun IdentitySettings(vm: PocketViewModel, back: () -> Unit, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    var naming by remember { mutableStateOf(false) }
    val name = identity.shown
    val unattended = vm.unattended
    var modelFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.connected) { if (vm.connected) { vm.unattendedRefresh(); vm.characterRefresh(); if (vm.catalog.objects("groups").isEmpty()) vm.refresh() } }
    SettingsPage(name, back) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Crossfade(identity.avatar, animationSpec = tween(260), label = "avatar") { key -> Avatar(key, StatusCard.Mood.HAPPY, identity.shown, size = 140.dp) }
            Spacer(Modifier.height(10.dp))
            Text(identity.shown, style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif)
            TextButton({ naming = true }) { Text(if (identity.name.isBlank()) "给它起个名字" else "改名字", color = Pocket.accent) }
        }
        SectionLabel("头像")
        AvatarPicker(identity.avatar, identity.shown) { IdentityStore.setAvatar(context, it) }
        Footnote("名字会告诉它（写进给它的设定），通知里也用这个名字；它在各个应用里的昵称不变。头像只存在这台手机上。")
        CharacterRows(vm, name, open)

        SectionLabel("${name}用的模型")
        InsetGroup {
            if (unattended == null) {
                GroupRow(if (vm.unattendedError.isBlank()) "正在读取…" else "读不到设置", icon = Glyph.Warning, chevron = false,
                    subtitle = vm.unattendedError.ifBlank { null }?.let { "$it（手机上的 Cowork 插件可能还是旧版）" }, onClick = { vm.unattendedRefresh() })
            } else {
                GroupRow("你叫${name}时", icon = Glyph.Chat, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.OWNER), vm.catalog),
                    onClick = { modelFor = UnattendedModels.OWNER })
                GroupDivider()
                GroupRow("自己的时间", icon = Glyph.Clock, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.PATROL), vm.catalog),
                    onClick = { modelFor = UnattendedModels.PATROL })
                GroupDivider()
                GroupRow("被通知叫醒", icon = Glyph.Filter, subtitle = UnattendedModels.label(UnattendedModels.selection(unattended, UnattendedModels.NOTIFY), vm.catalog),
                    onClick = { modelFor = UnattendedModels.NOTIFY })
            }
        }
        Footnote("“你叫${name}时”管你们俩的对话里的每一轮，包括在那里到点的便条；自己的时间（以前的巡视也算）和被通知叫醒设了自己的就用自己的。没设的用会话自己的模型（新会话是 DSH 的默认模型）。改这里不动 DSH 的默认模型。")
    }
    modelFor?.let { kind ->
        ModelSheet(vm.catalog, UnattendedModels.selection(unattended, kind), { modelFor = null }, title = when (kind) { UnattendedModels.PATROL -> "自己的时间用的模型"; UnattendedModels.OWNER -> "你叫${name}时用的模型"; else -> "被通知叫醒时用的模型" },
            clear = { vm.setUnattendedModel(kind, null, null, null); modelFor = null }) { p, m, e -> vm.setUnattendedModel(kind, p, m, e); modelFor = null }
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

// ───────────────────────────── Local DSH ─────────────────────────────

/** 本机 DSH: whether the app is connected, and the fixed runtime actions for when it is not. */
@Composable internal fun DshSettings(vm: PocketViewModel, back: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<RuntimeController.Action?>(null) }
    LaunchedEffect(Unit) { if (!vm.runtimeBusy) vm.runtime(RuntimeController.Action.STATUS) }
    SettingsPage("本机 DSH", back) {
        SectionLabel("连接")
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

        SectionLabel("运行状态")
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
        Footnote("这里的 Linux 是 Ubuntu chroot，不是独立虚拟机，也不是安全边界。启动、停止和重建都只调用固定的 pocket-control.sh 动作，不执行任意命令，需要 KernelSU 授权。")

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
    confirm?.let { action ->
        val (title, text) = when (action) {
            RuntimeController.Action.RESTART -> "重启 DSH？" to "正在运行的任务会被中断，重启后自动重新连接。"
            RuntimeController.Action.STOP -> "停止 DSH？" to "所有任务都会中断，应用将断开连接，直到再次启动。"
            else -> "重建 Linux 环境？" to "将停止 DSH 并重建私有挂载后再次启动；文件与凭据保留。若仍有后台进程在 chroot 中运行，操作会被拒绝。"
        }
        ConfirmDialog(title, text, "继续", destructive = action != RuntimeController.Action.RESTART, dismiss = { confirm = null }) { vm.runtime(action); confirm = null }
    }
}

// ───────────────────────────── Its phone ─────────────────────────────

/** 它的手机: the link to the broker that holds its screen, and which of its apps stay online. */
@Composable internal fun CoworkSettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val link by CoworkConnection.connection.collectAsState()
    val executorError by CoworkConnection.error.collectAsState()
    var auto by remember { mutableStateOf(CoworkControllerService.autoConnect(context)) }
    var rebuildDialog by remember { mutableStateOf(false) }
    val (executorColor, executorText) = executorSummary(link)
    val name = IdentityStore.flow(context).collectAsState().value.shown

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
            ToggleRow("自动连接", auto, icon = Glyph.Link, subtitle = "打开应用或有事要做时自动连上") { auto = it; CoworkControllerService.setAutoConnect(context, it) }
            GroupDivider()
            GroupRow(if (vm.rebuilding) "正在重建副屏…" else "重建副屏", icon = Glyph.Refresh, subtitle = "副屏卡住或出错时用：重启执行器、换一块新的副屏",
                chevron = false, enabled = !vm.rebuilding, onClick = { rebuildDialog = true },
                trailing = { if (vm.rebuilding) Spinner(size = 14.dp, color = Pocket.accent) })
        }
        Footnote("它住在工作资料里，用一块你看不见的副屏；你的应用和数据它碰不到。工作资料里的应用由它像普通人一样用；花钱、账号安全和清空聊天记录仍然要你。" +
            "连接需要 KernelSU 对 Continuo 的授权；上滑清除最近任务会被部分系统强行结束，请在最近任务里锁定 Continuo。")

        KeepOnline(vm, name)
    }

    if (rebuildDialog) ConfirmDialog("重建副屏？", "执行器会重启，换一块新的副屏。${name}正在做的事会停下，副屏上开着的应用会关掉，用到时再打开；记忆、便条和对话都不受影响。", "重建",
        dismiss = { rebuildDialog = false }) { rebuildDialog = false; vm.rebuildScreen() }
}

// ───────────────────────────── Permissions and ways in ─────────────────────────────

/** 权限与入口: what Continuo needs on this phone to show itself, and the ways to call it from outside the app. */
@Composable internal fun EntrySettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var overlayDialog by remember { mutableStateOf(false) }
    val refresh = rememberResumeTick()
    var settle by remember { mutableIntStateOf(0) }
    val overlayGranted = remember(refresh, settle) { AndroidSettings.canDrawOverlays(context) }
    val aboveStatusBar = remember(refresh, settle) { IslandAccessibilityService.available }
    var keepLayer by remember { mutableStateOf(IslandLayerKeeper.enabled(context)) }
    val notificationsGranted = remember(refresh, settle) { notificationsGranted(context) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settle++; if (!granted) vm.showError("通知权限没开：它的状态卡和“找你”都看不到")
    }
    val name = IdentityStore.flow(context).collectAsState().value.shown

    SettingsPage("权限与入口", back) {
        SectionLabel("权限")
        InsetGroup {
            GroupRow("通知", icon = Glyph.Vibrate, subtitle = "它的状态卡、“找你”和便条回执", value = if (notificationsGranted) "已允许" else "未允许",
                chevron = !notificationsGranted, onClick = if (notificationsGranted) null else ({ if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }))
            GroupDivider()
            GroupRow("显示在其他应用上层", icon = Glyph.Shield, subtitle = "叫${name}的半屏、看它的手机、接手时用", value = if (overlayGranted) "已允许" else "未允许",
                onClick = { if (overlayGranted) context.startActivity(IslandRuntime.overlaySettingsIntent(context)) else overlayDialog = true })
            GroupDivider()
            ToggleRow("盖住状态栏（可选）", keepLayer, icon = Glyph.ArrowUp, subtitle = when {
                aboveStatusBar && keepLayer -> "已生效；被系统关闭后会用 root 自动重新开启"
                aboveStatusBar -> "已生效；被系统关闭后不会自动恢复"
                keepLayer -> "正在用 root 开启无障碍服务…"
                else -> "没开：看它的手机时，状态栏的时间和图标会盖在画面上"
            }) { on ->
                keepLayer = on
                scope.launch {
                    IslandLayerKeeper.setEnabled(context, on).onFailure {
                        keepLayer = false; IslandLayerKeeper.setEnabled(context, false)
                        vm.showError("无法用 root 开启无障碍服务：${it.message}。请检查 KernelSU 对 Continuo 的授权")
                    }
                    delay(1_500); settle++ // the system binds the service a moment after the setting changes
                }
            }
        }
        Footnote("“盖住状态栏”借无障碍服务的窗口层级，它什么都不读。应用不会自行授予任何权限，没允许的点一下去系统设置里开。")

        SectionLabel("叫${name}的入口")
        InsetGroup {
            GroupRow("快捷开关“叫$name”", icon = Glyph.Plus, subtitle = "下拉快捷设置里一点就叫它", chevron = false, tint = Pocket.accent,
                onClick = { addTile(context, name) { vm.showError(it) } })
            GroupDivider()
            val assistant = remember(refresh) { dev.dsh.pocket.assist.AssistService.isAssistant(context) }
            GroupRow("当系统的助手", icon = Glyph.Mic, value = if (assistant) "已设" else "没设",
                subtitle = if (assistant) "系统的助手手势会叫$name，不读你屏幕上的内容" + (if (overlayGranted) "" else "；要先允许显示在其他应用上层")
                    else "在系统设置的“数字助理应用”里选 Continuo",
                onClick = { dev.dsh.pocket.assist.AssistService.settingsIntent(context)?.let { context.startActivity(it) } ?: vm.showError("这台手机找不到选择助手的设置页") })
        }
        Footnote("也可以长按电源键、从分享菜单或桌面快捷方式叫它。")
    }

    if (overlayDialog) ConfirmDialog("允许显示在其他应用上层", "将打开 Android 系统设置，请手动允许 Continuo。应用不会自行授予任何权限。", "前往设置", dismiss = { overlayDialog = false }) {
        overlayDialog = false; context.startActivity(IslandRuntime.overlaySettingsIntent(context))
    }
}

/** 收消息: keeps the listed work-profile apps' own processes alive (see [ChatKeeperRuntime]); the list lives on the phone. */
@Composable private fun KeepOnline(vm: PocketViewModel, name: String) {
    val link by CoworkConnection.connection.collectAsState()
    val apps by CoworkConnection.apps.collectAsState()
    val refresh = rememberResumeTick()
    var picking by remember { mutableStateOf(false) }
    // Connecting installs the keeper and opens the way to its phone's app list, so read both again once connected.
    LaunchedEffect(refresh, link == CoworkLinkState.CONNECTED) {
        vm.chatKeeperRefresh()
        if (link == CoworkLinkState.CONNECTED) try { CoworkConnection.refreshApps() } catch (e: Exception) { if (e is CancellationException) throw e }
    }
    val keeper = vm.chatKeeper
    val installError = ChatKeeperRuntime.installError
    val label = { pkg: String -> apps.firstOrNull { it.packageName == pkg }?.label ?: ChatKeeperStatus.KNOWN[pkg] ?: pkg }
    val usable = keeper != null && keeper.switchable && !vm.chatKeeperBusy
    SectionLabel("收消息")
    InsetGroup {
        if (installError != null) {
            GroupRow("保活没装上", icon = Glyph.Warning, subtitle = "$installError。点一下重试", chevron = false, enabled = !vm.chatKeeperBusy,
                onClick = { vm.chatKeeperInstall() }, trailing = { if (vm.chatKeeperBusy) Spinner(size = 14.dp, color = Pocket.accent) })
            GroupDivider()
        }
        ToggleRow("后台保持在线", keeper?.on ?: false, icon = Glyph.Power, enabled = keeper?.switchable ?: false, busy = vm.chatKeeperBusy || keeper == null,
            subtitle = keeper?.summary(label) ?: "正在读取…") { vm.setChatKeeper(it) }
        GroupDivider()
        GroupRow("保持哪些应用", icon = Glyph.List, enabled = usable, onClick = { picking = true },
            subtitle = keeper?.packages?.let { if (it.isEmpty()) "没选" else it.joinToString("、", transform = label) })
    }
    Footnote("工作资料里的应用收不到系统推送，进程被系统清掉后就收不到消息、也不会叫醒$name。打开后，选中的应用在运行时由 root 标成重要进程，" +
        "系统清后台时一般会跳过它们，副屏重建也不影响；只保持主进程和推送进程。没在运行的不会自动打开（手机重启后要等它或你打开一次）。每个应用都会一直占着内存，只选要收消息的。")
    if (picking && keeper != null) {
        val listed = keeper.packages
        val rows = apps.map { PickerApp(it.packageName, it.label) } +
            listed.filter { p -> apps.none { it.packageName == p } }.map { PickerApp(it, label(it), if (apps.isEmpty()) it else "没装在它的手机上") }
        AppPickerSheet("保持哪些应用", if (apps.isEmpty()) "连上它的手机后才能从它装的应用里选；现在只能去掉已选的" else null,
            rows, listed.toSet(), dismiss = { picking = false }, max = ChatKeepPolicy.MAX_PACKAGES) { picked ->
            picking = false
            if (picked != listed) vm.setChatKeeperApps(picked)
        }
    }
}

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
            ToggleRow("触感反馈", vm.haptics, icon = Glyph.Vibrate) { vm.changeHaptics(it) }
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
