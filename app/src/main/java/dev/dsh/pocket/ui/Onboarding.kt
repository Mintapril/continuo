package dev.dsh.pocket.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dsh.pocket.CallActivity
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.IdentityRules
import dev.dsh.pocket.data.IdentityStore
import dev.dsh.pocket.data.RuntimeController
import dev.dsh.pocket.data.TaskMode
import dev.dsh.pocket.data.text
import dev.dsh.pocket.island.IslandRuntime
import dev.dsh.pocket.notify.NotifyCenter

private enum class Step { WELCOME, CHECK, NAME, FACE, ABILITIES, PERMISSIONS, HELLO }

/**
 * First run, in the order things are actually set up: what it is; checking its phone (Root, DSH, the plugin, the
 * work profile, the executor: DSH and the plugin are installed from a computer, so these are only checked); its
 * name and face; what it may do on its own; the permissions on the owner's phone; and the first time the owner calls
 * it. Every step can be changed later in the settings.
 */
@Composable internal fun Onboarding(vm: PocketViewModel, done: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    val identity by IdentityStore.flow(context).collectAsState()
    var name by rememberSaveable { mutableStateOf(identity.name) }
    var round by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(step != Step.WELCOME) { step = Step.entries[step.ordinal - 1] }
    val next = { step = Step.entries[(step.ordinal + 1).coerceAtMost(Step.entries.size - 1)] }
    val shown = IdentityRules.normalize(name).ifBlank { "它" }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Step.entries.forEach { s ->
                val fill by animateColorAsState(if (s.ordinal <= step.ordinal) Pocket.accent else Pocket.hairline, tween(300), label = "progress")
                Box(Modifier.weight(1f).padding(horizontal = 2.dp).height(3.dp).background(fill, CircleShape))
            }
        }
        AnimatedContent(step, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(tween(320, easing = EaseOutCubic)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(320, easing = EaseOutCubic)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160)))
        }, label = "step") { current ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            when (current) {
                Step.WELCOME -> {
                    Spacer(Modifier.height(32.dp))
                    Text("给 AI 一部它自己的手机", style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif)
                    Spacer(Modifier.height(16.dp))
                    listOf("它住在工作资料里，用一块你看不见的副屏，像普通人一样用自己的应用",
                        "你的应用和数据它碰不到；花钱、账号安全这类事一定找你",
                        "平时不用打开 Continuo：下拉通知栏就能看它在干什么、叫它、写便条",
                        "你随时能看它的手机，也能接过来自己操作").forEach { Bullet(it) }
                }
                Step.CHECK -> CheckStep(vm)
                Step.NAME -> {
                    Title("叫它什么？", "它会用这个名字称呼自己，通知里也用这个名字。")
                    val problem = if (name.isBlank()) null else IdentityRules.problem(name)
                    OutlinedTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名字") },
                        isError = problem != null, supportingText = { Text(problem ?: "最多 ${IdentityRules.MAX_NAME} 个字", color = if (problem != null) MaterialTheme.colorScheme.error else Pocket.faint) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), shape = RoundedCornerShape(14.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IdentityRules.suggestions(round).forEach { Pill(it, selected = it == name, trailing = null) { name = it } }
                        TextButton({ round++ }) { Text("换一批", color = Pocket.accent) }
                    }
                    Footnote("名字只在你和它之间用：它在各个应用里的昵称不变。")
                }
                Step.FACE -> {
                    Title("${shown}长什么样？", "头像只存在这台手机上，用在通知、叫它和日记里。")
                    AvatarPicker(identity.avatar, shown) { IdentityStore.setAvatar(context, it) }
                    Spacer(Modifier.height(16.dp))
                    Text("它的通知会这样显示：", style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(identity.avatar, StatusCard.Mood.WORK, shown, size = 40.dp, head = true)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("$shown · 在 B 站", style = MaterialTheme.typography.titleSmall)
                                Text("刚把推送关了", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                            }
                        }
                    }
                }
                Step.ABILITIES -> AbilitiesStep(vm, shown)
                Step.PERMISSIONS -> PermissionsStep(vm, shown)
                Step.HELLO -> {
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(identity.avatar, StatusCard.Mood.HAPPY, shown, size = 150.dp) }
                    Spacer(Modifier.height(18.dp))
                    Text("跟${shown}打个招呼", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("第一次叫它，说说你想让它先做什么。以后下拉通知栏、点快捷开关或者长按图标就能叫它。",
                        style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                    Text("它的手机现在还是空的：装应用它可以自己去应用商店装；注册、登录账号要手机号和验证码，得你来接手。",
                        style = MaterialTheme.typography.bodySmall, color = Pocket.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != Step.WELCOME && step != Step.HELLO) TextButton({ next() }) { Text("跳过", color = Pocket.muted) }
            Spacer(Modifier.weight(1f))
            val label = when (step) { Step.WELCOME -> "开始"; Step.CHECK -> "继续"; Step.HELLO -> "叫$shown"; else -> "下一步" }
            val nameOk = step != Step.NAME || IdentityRules.problem(name) == null
            Button({
                when (step) {
                    Step.NAME -> { vm.nameCompanion(name); next() }
                    Step.HELLO -> {
                        IdentityStore.finishOnboarding(context)
                        val named = IdentityStore.current(context).name
                        context.startActivity(CallActivity.call(context, if (named.isBlank()) "你好！" else "你好，以后你就叫${named}了。"))
                        done()
                    }
                    else -> next()
                }
            }, enabled = nameOk, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Pocket.ink, contentColor = Pocket.onInk),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) { Text(label) }
        }
    }
}

@Composable private fun Title(title: String, subtitle: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
    Spacer(Modifier.height(18.dp))
}

@Composable private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text("·", Modifier.width(18.dp), style = MaterialTheme.typography.bodyLarge, color = Pocket.accent, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun CheckRow(title: String, ok: Boolean?, detail: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(when (ok) { true -> Pocket.success; false -> Pocket.warning; null -> Pocket.hairline }, CircleShape), contentAlignment = Alignment.Center) {
            Text(when (ok) { true -> "✓"; false -> "!"; null -> "" }, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
        }
        action?.let { (label, run) -> TextButton(run) { Text(label, color = Pocket.accent) } }
    }
    HorizontalDivider(color = Pocket.hairline)
}

@Composable private fun CheckStep(vm: PocketViewModel) {
    val context = LocalContext.current
    val link by CoworkConnection.connection.collectAsState()
    val status by CoworkConnection.status.collectAsState()
    LaunchedEffect(Unit) { CoworkControllerService.ensureRunning(context) }
    Title("检查一下", "DSH 和 Cowork 插件要在电脑上装，这里只检查；DSH 没开可以在这里启动。")
    val executor = link == CoworkLinkState.CONNECTED
    CheckRow("DSH 在运行", if (vm.connected) true else if (vm.connecting) null else false,
        if (vm.connected) "已连接本机 DSH" else "连接本机 DSH 需要 Root 授权或令牌",
        if (vm.connected) null else (if (vm.runtimeBusy) "启动中…" else "启动") to { vm.runtime(RuntimeController.Action.START) })
    CheckRow("Cowork 插件", if (!vm.connected) null else vm.modeAvailable(TaskMode.COWORK),
        if (vm.modeAvailable(TaskMode.COWORK)) "已装好" else "没找到预设 cowork-phone，要在电脑上装插件（见 README「部署」）")
    CheckRow("它的手机（工作资料）", if (!executor) null else status.optInt("profileUserId", -1) > 0,
        if (status.optInt("profileUserId", -1) > 0) "已创建" else "还没有工作资料：接下来是系统的确认页，提到“组织管理”也没关系，这里没有公司服务器",
        if (executor && status.optInt("profileUserId", -1) <= 0) "创建" to {
            runCatching { context.startActivity(Intent().setComponent(ComponentName("dev.dsh.pocket.isolation", "dev.dsh.pocket.isolation.IsolationActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { vm.showError("没找到隔离应用（dev.dsh.pocket.isolation），要先装上它") }
        } else null)
    CheckRow("执行器", if (link.connecting) null else executor, if (executor) "已用 Root 启动并连上" else link.hint,
        if (executor || link.connecting) null else "启动" to { runCatching { CoworkControllerService.startUserInitiated(context) } })
    if (!vm.connected) { Spacer(Modifier.height(12.dp)); ConnectCard(vm) }
}

@Composable private fun AbilitiesStep(vm: PocketViewModel, name: String) {
    val context = LocalContext.current
    remember { NotifyCenter.init(context) }
    val notifyOn by NotifyCenter.enabled.collectAsState()
    var patrol by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(vm.connected) { if (vm.connected) vm.schedulesRefresh() }
    val patrolNote = vm.schedules.tasks.firstOrNull { it.text("status") == "active" && it.text("prompt").trimStart().startsWith("[巡视]") }
    val hasPatrol = patrolNote != null
    Title("${name}能自己做什么？", "都可以以后在设置里改。")
    InsetGroup {
        GroupRow("通知叫醒", icon = Glyph.Filter, subtitle = "有意义的通知（私聊、@它）会叫它去看；银行和验证码类应用可以在设置里排除", chevron = false, trailing = {
            Switch(notifyOn, { NotifyCenter.setEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Pocket.accent))
        })
        GroupDivider()
        GroupRow("巡视", icon = Glyph.Clock, subtitle = if (patrolNote != null) "开着：${dev.dsh.pocket.data.Companion.frequency(patrolNote)}" else "每次挑两三个应用逛逛。会花模型的钱，可以以后再开", chevron = false, trailing = {
            if (hasPatrol) Text("已开", color = Pocket.success, style = MaterialTheme.typography.labelLarge)
            else TextButton({ patrol = true; vm.startPatrol { patrol = null; it.onFailure { e -> vm.showError(e.message ?: "巡视没有贴上") } } }, enabled = vm.connected && patrol == null) {
                Text(if (patrol == true) "正在贴…" else "开", color = Pocket.accent)
            }
        })
    }
    Footnote("判断通知要不要叫醒它，用的是 OpenRouter 上的小模型（Jev），密钥只存在本机 DSH 里，在设置的“通知叫醒”里填。")
}

@Composable private fun PermissionsStep(vm: PocketViewModel, name: String) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notifications = remember(tick) { Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val overlay = remember(tick) { AndroidSettings.canDrawOverlays(context) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    Title("几个权限", "放进你的手机：它找你、你看它的手机都靠这些。")
    CheckRow("通知", notifications, "它的状态卡、它找你、便条回执", if (notifications) null else "允许" to { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) })
    CheckRow("显示在其他应用上层", overlay, "看它的手机、接手", if (overlay) null else "去开" to { context.startActivity(IslandRuntime.overlaySettingsIntent(context)) })
    CheckRow("快捷开关“叫$name”", null, "下拉快捷设置里一点就叫它", "添加" to { addTile(context, name) { vm.showError(it) } })
    CheckRow("在最近任务里锁定 Continuo", null, "免得系统清后台时把它的连接一起清掉：打开最近任务，长按 Continuo，点锁定")
}
