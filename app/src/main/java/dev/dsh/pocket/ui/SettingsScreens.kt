@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkActivity
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.data.TaskMode
import dev.dsh.pocket.island.IslandAccessibilityService
import dev.dsh.pocket.island.IslandRuntime
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable fun Footnote(text: String) =
    Text(text, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)

private fun dshStatus(vm: PocketViewModel) = if (vm.connected) "已连接" else if (vm.connecting) "连接中" else "未连接"

/** Colour + one-line description of the executor state, shared by the hub and the Cowork page. */
@Composable private fun executorSummary(link: CoworkLinkState): Pair<Color, String> = when (link) {
    CoworkLinkState.CONNECTED -> Pocket.success to "已自动连接"
    CoworkLinkState.STARTING, CoworkLinkState.AUTHENTICATING, CoworkLinkState.CONNECTING -> Pocket.warning to "正在自动连接…"
    CoworkLinkState.LOST -> Pocket.warning to "连接中断，正在恢复"
    CoworkLinkState.FAILED -> MaterialTheme.colorScheme.error to "自动连接失败"
    CoworkLinkState.DISCONNECTED -> Pocket.faint to "待命（有任务时自动启动）"
}

// ───────────────────────────── Hub ─────────────────────────────

@Composable internal fun Settings(vm: PocketViewModel, openDrawer: () -> Unit, navigate: (Screen) -> Unit) {
    val link by CoworkConnection.connection.collectAsState()
    val island by IslandRuntime.store.snapshots.collectAsState()
    val (executorColor, executorText) = executorSummary(link)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.Menu, "打开菜单", onClick = openDrawer)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text("设置", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineLarge)
            SectionLabel("设置")
            InsetGroup {
                GroupRow("连接与模式", icon = Glyph.Link, subtitle = "令牌、Root 重连、可用任务模式", onClick = { navigate(Screen.CONNECTION) }, trailing = {
                    StatusDot(if (vm.connected) Pocket.success else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(6.dp))
                    Text(dshStatus(vm), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                })
                GroupDivider()
                GroupRow("Cowork", icon = Glyph.Cowork, subtitle = executorText, onClick = { navigate(Screen.COWORK) }, trailing = {
                    StatusDot(executorColor, pulse = link.connecting); Spacer(Modifier.width(6.dp))
                    Text(if (island.preferences.enabled) "任务岛开" else "任务岛关", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                })
                GroupDivider()
                GroupRow("外观与反馈", icon = Glyph.Palette, value = when (vm.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, onClick = { navigate(Screen.APPEARANCE) })
                GroupDivider()
                GroupRow("记忆", icon = Glyph.Brain, subtitle = when {
                    vm.memory.available == false -> "插件未安装"
                    vm.memory.available == null -> "跨会话记忆的提取、注入与查看"
                    vm.memory.archivedTotal > 0 -> "${vm.memory.total} 条记忆，${vm.memory.archivedTotal} 条已归档"
                    else -> "${vm.memory.total} 条记忆"
                }, onClick = { navigate(Screen.MEMORY) })
                GroupDivider()
                GroupRow("运行环境", icon = Glyph.Server, subtitle = "DSH 与 Linux 环境，需要 KernelSU 授权", onClick = { navigate(Screen.RUNTIME) })
            }
            SectionLabel("其他")
            InsetGroup {
                GroupRow("关于", icon = Glyph.Info, value = BuildConfig.VERSION_NAME, onClick = { navigate(Screen.ABOUT) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────────── Connection ─────────────────────────────

@Composable internal fun ConnectionSettings(vm: PocketViewModel, back: () -> Unit) {
    var manual by remember { mutableStateOf(false) }
    SettingsPage("连接与模式", back) {
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
        SectionLabel("任务模式")
        InsetGroup {
            TaskMode.entries.forEachIndexed { index, mode ->
                if (index > 0) GroupDivider()
                GroupRow(mode.title, icon = if (mode == TaskMode.CODE) Glyph.Code else Glyph.Cowork, chevron = false,
                    subtitle = if (mode == TaskMode.CODE) "DSH 标准模式（standard）" else "工作资料副屏代办（cowork）",
                    value = when { vm.presets == null -> "未知"; vm.modeAvailable(mode) -> "可用"; else -> "未配置" })
            }
        }
    }
    if (manual) InputDialog("输入连接令牌", "DSH Web 的访问令牌，只保存在本机加密存储中", "", { manual = false }, secret = true) { vm.connectManual(it); manual = false }
}

// ───────────────────────────── Cowork ─────────────────────────────

private fun surfaceText(value: dev.dsh.pocket.island.IslandSurface) = when (value) {
    dev.dsh.pocket.island.IslandSurface.OVERLAY -> "悬浮窗"
    dev.dsh.pocket.island.IslandSurface.NOTIFICATION -> "通知"
    dev.dsh.pocket.island.IslandSurface.HIDDEN -> "隐藏"
}
private fun reasonText(value: dev.dsh.pocket.island.IslandSuppression) = when (value) {
    dev.dsh.pocket.island.IslandSuppression.NONE -> "正常"
    dev.dsh.pocket.island.IslandSuppression.NO_TASK -> "暂无任务"
    dev.dsh.pocket.island.IslandSuppression.DISABLED -> "已关闭"
    dev.dsh.pocket.island.IslandSuppression.USER_NOTIFICATION_ONLY -> "仅通知"
    dev.dsh.pocket.island.IslandSuppression.PERMISSION_MISSING -> "缺少悬浮窗权限"
    dev.dsh.pocket.island.IslandSuppression.DEVICE_LOCKED -> "设备已锁屏"
    dev.dsh.pocket.island.IslandSuppression.FOREGROUND_UNKNOWN, dev.dsh.pocket.island.IslandSuppression.FOREGROUND_STALE -> "无法判断前台应用"
    dev.dsh.pocket.island.IslandSuppression.EXCLUDED_APP -> "前台为免打扰应用"
    dev.dsh.pocket.island.IslandSuppression.GAME -> "前台为游戏"
}

@Composable internal fun CoworkSettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link by CoworkConnection.connection.collectAsState()
    val executorError by CoworkConnection.error.collectAsState()
    val island by IslandRuntime.store.snapshots.collectAsState()
    val availability by IslandRuntime.availability.collectAsState()
    val prefs = island.preferences
    var auto by remember { mutableStateOf(CoworkControllerService.autoConnect(context)) }
    var excludedDialog by remember { mutableStateOf(false) }
    var overlayDialog by remember { mutableStateOf(false) }
    // Permission state is re-read whenever the page returns to the foreground (e.g. from system settings).
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val overlayGranted = remember(refresh) { AndroidSettings.canDrawOverlays(context) }
    val aboveStatusBar = remember(refresh) { IslandAccessibilityService.available }
    var layerDialog by remember { mutableStateOf(false) }
    val notificationsGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++; if (!granted) vm.showError("通知权限未开启：任务状态和“需要你处理”的提醒将不可见")
    }
    val (executorColor, executorText) = executorSummary(link)

    SettingsPage("Cowork 与任务岛", back) {
        SectionLabel("执行器")
        InsetGroup {
            GroupRow("执行器状态", icon = Glyph.Cowork, subtitle = if (link == CoworkLinkState.FAILED) executorError.ifBlank { link.hint } else executorText,
                chevron = false, trailing = {
                    if (link.connecting) Spinner(size = 14.dp, color = Pocket.accent) else StatusDot(executorColor)
                })
            if (link in setOf(CoworkLinkState.FAILED, CoworkLinkState.LOST, CoworkLinkState.DISCONNECTED)) {
                GroupDivider()
                GroupRow("立即重试", icon = Glyph.Refresh, chevron = false, tint = Pocket.accent,
                    onClick = { runCatching { CoworkControllerService.startUserInitiated(context) }.onFailure { vm.showError(it.message ?: "无法启动执行器") } })
            }
            GroupDivider()
            GroupRow("自动连接执行器", icon = Glyph.Link, subtitle = "打开应用或发布任务时自动连接", chevron = false, trailing = {
                Switch(auto) { auto = it; CoworkControllerService.setAutoConnect(context, it) }
            })
        }
        Footnote("无需手动连接。任务请求到达时，若应用已被系统结束，执行器会尝试自行重新启动控制服务。需要 KernelSU 对 Continuo 的授权；上滑清除最近任务会被部分系统强行结束，请在系统里锁定本应用。")

        SectionLabel("任务岛")
        InsetGroup {
            GroupRow("启用任务岛", icon = Glyph.Layers, subtitle = "悬浮显示进度，可暂停、接管", chevron = false, trailing = {
                Switch(prefs.enabled && prefs.overlayEnabled) { on ->
                    if (on) {
                        IslandRuntime.store.configure(prefs.copy(enabled = true, overlayEnabled = true))
                        if (CoworkConnection.connection.value == CoworkLinkState.CONNECTED) IslandRuntime.startUserInitiated(context)
                    } else IslandRuntime.disable(context)
                }
            })
            GroupDivider()
            GroupRow("悬浮窗权限", icon = Glyph.Shield, subtitle = "显示在其他应用上层所必需", value = if (overlayGranted) "已允许" else "未允许",
                onClick = { if (overlayGranted) context.startActivity(IslandRuntime.overlaySettingsIntent(context)) else overlayDialog = true })
            GroupDivider()
            GroupRow("显示在状态栏上层", icon = Glyph.ArrowUp, subtitle = if (aboveStatusBar) "任务岛盖在摄像头和状态栏上" else "未开启：任务岛显示在状态栏下方",
                value = if (aboveStatusBar) "已开启" else "未开启",
                onClick = { if (aboveStatusBar) context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) else layerDialog = true })
            GroupDivider()
            GroupRow("通知权限", icon = Glyph.Vibrate, subtitle = "状态提醒与“需要你处理”", value = if (notificationsGranted) "已允许" else "未允许",
                chevron = !notificationsGranted, onClick = if (notificationsGranted) null else ({ if (Build.VERSION.SDK_INT >= 33) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }))
            GroupDivider()
            GroupRow("游戏中隐藏", icon = Glyph.Hand, subtitle = "前台是游戏时只保留通知", chevron = false, trailing = {
                Switch(prefs.hideInGames) { IslandRuntime.store.configure(prefs.copy(hideInGames = it)) }
            })
            GroupDivider()
            GroupRow("免打扰应用", icon = Glyph.Filter, subtitle = "这些应用在前台时不显示悬浮岛",
                value = if (prefs.excludedPackages.isEmpty()) "无" else "${prefs.excludedPackages.size} 个", onClick = { excludedDialog = true })
        }
        Footnote("当前显示方式：${surfaceText(availability.surface)}（${reasonText(availability.reason)}）。锁屏、游戏、免打扰应用或无法判断前台时自动降级为通知。")

        SectionLabel("控制")
        InsetGroup {
            GroupRow("Cowork 控制中心", icon = Glyph.Play, subtitle = "实时画面、接管与交还、暂停与停止、工作资料应用",
                onClick = { context.startActivity(Intent(context, CoworkActivity::class.java)) })
        }
        Footnote("工作资料应用无需逐项加入白名单；应用可访问不代表发送、支付、授权或删除许可。")
    }

    if (overlayDialog) ConfirmDialog("允许显示在其他应用上层", "将打开 Android 系统设置，请手动允许 Continuo。应用不会自行授予任何权限；未授权时只使用通知。", "前往设置", dismiss = { overlayDialog = false }) {
        overlayDialog = false; context.startActivity(IslandRuntime.overlaySettingsIntent(context))
    }
    if (layerDialog) ConfirmDialog("显示在状态栏上层",
        "普通悬浮窗位于状态栏下层，会被时间和图标挡住，所以任务岛目前显示在状态栏下方。在系统无障碍设置里开启“Continuo 任务岛层级”后，任务岛才能回到摄像头位置；这项服务不读取任何屏幕内容，只用于提升窗口层级。\n\n" +
            "如果开关是灰色或提示“受限设置”，先到 设置 → 应用 → Continuo（应用信息）→ 右上角 ⋮ → 允许受限设置，再回来开启。应用被强行停止（例如清理后台）后，这项服务可能被系统关闭，需要重新开启。",
        "前往设置", dismiss = { layerDialog = false }) {
        layerDialog = false; context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    if (excludedDialog) InputDialog("免打扰应用", "输入包名，用逗号分隔；留空表示不排除任何应用", prefs.excludedPackages.joinToString(","), { excludedDialog = false }, allowBlank = true) { text ->
        IslandRuntime.store.configure(prefs.copy(excludedPackages = text.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
        excludedDialog = false
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