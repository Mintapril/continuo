@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.JevFocus
import dev.dsh.pocket.notify.NotifyCenter
import dev.dsh.pocket.notify.NotifyDefaults
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkLinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Notification triggers (cowork-plugin/src/notify.mjs): every app's notifications join one batch that goes to Jev; a
// batch it judges meaningful wakes a Cowork session, and the model decides what to do. No per-app rules of the owner:
// the model keeps a Jev focus per app itself (listed here, and the owner can clear one).

/** An app's name when this user can see the package (the owner's own apps), else its package name. */
internal fun appLabel(context: android.content.Context, packageName: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrNull()?.ifBlank { null } ?: packageName

@Composable internal fun NotifySettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    remember { NotifyCenter.init(context) }
    val on by NotifyCenter.enabled.collectAsState()
    val excluded by NotifyCenter.excluded.collectAsState()
    val log by NotifyCenter.log.collectAsState()
    val quiet by NotifyCenter.quietSeconds.collectAsState()
    val reading by NotifyCenter.reading.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var ingress by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(refresh) { ingress = null; ingress = withContext(Dispatchers.IO) { NotifyCenter.ingressListening() } }
    LaunchedEffect(vm.connected) { if (vm.connected) { vm.jevRefresh(); vm.jevFocusRefresh() } }
    var keyDialog by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<JevFocus.Entry?>(null) }
    var excludeDialog by remember { mutableStateOf(false) }
    var excludePicker by remember { mutableStateOf(false) }
    var quietDialog by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    SettingsPage("通知叫醒", back) {
        SectionLabel("开关")
        InsetGroup {
            ToggleRow("把通知交给 Jev 判断", on, icon = Glyph.Filter, subtitle = when {
                !on -> "关闭：不读取任何通知"
                vm.jevConfigured == false -> "开着，但还没设 Jev 密钥：什么都不会叫醒它"
                else -> "有实际意义时叫醒它，由它决定做什么"
            }) { NotifyCenter.setEnabled(it) }
            GroupDivider()
            GroupRow("Jev 密钥（OpenRouter）", icon = Glyph.Key, subtitle = if (vm.connected) "保存在本机 DSH 的凭据存储里" else "需先连接本机 DSH",
                value = when (vm.jevConfigured) { true -> "已配置"; false -> "未配置"; null -> "未知" }, enabled = vm.connected, onClick = { keyDialog = true })
            if (vm.jevConfigured == true) {
                GroupDivider()
                GroupRow("移除密钥", icon = Glyph.Trash, tint = MaterialTheme.colorScheme.error, chevron = false, enabled = vm.connected, onClick = { vm.jevClear() })
            }
            GroupDivider()
            GroupRow("不送去判断的应用", icon = Glyph.Shield, subtitle = if (excluded.isEmpty()) "这些应用的通知不读取、不外发" else excluded.sorted().joinToString("、") { appLabel(context, it) },
                value = if (excluded.isEmpty()) "无" else "${excluded.size} 个", onClick = { excludePicker = true })
            GroupDivider()
            GroupRow("合并等待", icon = Glyph.Clock, subtitle = "最后一条通知后再等这么久，才整批交给 Jev",
                value = "$quiet 秒", onClick = { quietDialog = true })
        }
        Footnote("开启后，除排除的应用外，所有应用的通知标题和正文都会经 OpenRouter 发给 TypeSafe 的 Jev（typesafe/jev-1.14）判断；" +
            "判为有实际意义的那几条再作为“他人写的、不可信的内容”交给 Cowork 会话，也就会发到模型服务；被拦下的那些也会在模型下次被这个应用唤起或巡视时给它看（一天内）。验证码、银行、私密聊天也不例外，建议把这类应用加入排除。Jev 无法判断（无密钥、超时等）时不唤起。")

        SectionLabel("状态")
        InsetGroup {
            GroupRow("读取通知", icon = Glyph.Eye, subtitle = "由 Cowork 执行器（root）读取，锁屏时也不会被系统冻结", chevron = false,
                value = when (reading) { true -> "已生效"; false -> "同步设置中"; null -> "执行器未连接" })
            GroupDivider()
            GroupRow("通知入口", icon = Glyph.Link, subtitle = "Cowork 插件 · 127.0.0.1:3082", chevron = false, onClick = { refresh++ }, trailing = {
                if (ingress == null) Spinner(size = 14.dp) else StatusDot(if (ingress == true) Pocket.success else Pocket.faint)
                Spacer(Modifier.width(6.dp))
                Text(when (ingress) { true -> "可用"; false -> "未就绪"; null -> "检查中" }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
        }
        Footnote(if (ingress == false) "通知入口未就绪：DSH 没有运行，或手机上的 Cowork 插件还没有部署通知触发。点一下重新检查。"
            else "收到通知后先等 $quiet 秒；期间任何应用的新通知都并进同一批并重新计时，$quiet 秒没有新通知就整批交给 Jev" +
                "（一直不停时最多攒 ${NotifyDefaults.MAX_WAIT_MS / 60_000} 分钟）。Jev 逐条判断，判为有意义的那几条交给它（你们俩的那个会话）；它正在自己忙时直接插进去，你们在聊时等这一轮聊完。")

        SectionLabel("Jev 关注说明")
        InsetGroup {
            val focus = vm.jevFocus
            when {
                !vm.connected -> GroupRow("需先连接本机 DSH", icon = Glyph.Filter, chevron = false)
                focus == null && vm.jevFocusError.isNotBlank() -> GroupRow("读取失败", icon = Glyph.Filter, subtitle = vm.jevFocusError, chevron = false, onClick = { vm.jevFocusRefresh() })
                focus == null -> GroupRow("读取中", icon = Glyph.Filter, chevron = false)
                focus.isEmpty() -> GroupRow("还没有", icon = Glyph.Filter, subtitle = "所有应用都用 Jev 的默认问题", chevron = false)
                else -> focus.forEachIndexed { index, entry ->
                    if (index > 0) GroupDivider()
                    GroupRow(appLabel(context, entry.packageName) + if (entry.profile == "personal") "（个人）" else "", icon = Glyph.Filter, subtitle = entry.text,
                        value = clock.format(Date(entry.at)) + JevFocus.byLabel(entry.by).let { if (it.isEmpty()) "" else " · $it" }, onClick = { clearing = entry })
                }
            }
        }
        Footnote("关注说明由 Cowork 自己写，补在 Jev 对这个应用的问题后面，决定这个应用的哪些通知值得叫醒它；无论怎么写，私聊和 @ 这个账号的消息都算，广告都不算。" +
            "Jev 拦下的通知只留在 DSH 的内存里，模型下次被这个应用唤起或巡视时会看到。无人值守时，模型只能改它正在用的那个应用，每个应用 6 小时最多改一次；点一条可以清除，清除后 6 小时内它不能再改。")

        SectionLabel("最近记录")
        InsetGroup {
            if (log.isEmpty()) GroupRow("暂无记录", icon = Glyph.Clock, chevron = false)
            log.take(20).forEachIndexed { index, entry ->
                if (index > 0) GroupDivider()
                GroupRow(clock.format(Date(entry.at)), icon = Glyph.Clock, subtitle = entry.text, chevron = false)
            }
        }
        Footnote("记录只保存在执行器的内存里，执行器重启后清空；合并与节流的计数也会重新开始。")
    }

    if (keyDialog) InputDialog("Jev 密钥（OpenRouter）", "OpenRouter 的 API Key（sk-or-…）；只转交给本机 DSH 保存，应用不留存", "", { keyDialog = false }, secret = true) {
        vm.jevSave(it); keyDialog = false
    }
    if (quietDialog) ChoiceDialog({ quietDialog = false }) {
        SheetHeader("合并等待", "最后一条通知后等多久再交给 Jev；越短越及时，越长越容易把连发的消息并成一批")
        NotifyDefaults.QUIET_CHOICES.forEach { seconds ->
            SheetRow("$seconds 秒", if (seconds == NotifyDefaults.DEFAULT_QUIET_SECONDS) "默认" else null, Glyph.Clock, checked = seconds == quiet) {
                NotifyCenter.setQuietSeconds(seconds); quietDialog = false
            }
        }
    }
    clearing?.let { entry ->
        ChoiceDialog({ clearing = null }) {
            SheetHeader(appLabel(context, entry.packageName), entry.text)
            SheetRow("清除这条关注说明", "Jev 对这个应用恢复默认问题", Glyph.Trash, tint = MaterialTheme.colorScheme.error) {
                vm.clearJevFocus(entry.app); clearing = null
            }
        }
    }
    if (excludePicker) {
        val work by CoworkConnection.apps.collectAsState()
        var personal by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        LaunchedEffect(Unit) {
            personal = withContext(Dispatchers.IO) { launcherApps(context) }
            if (CoworkConnection.connection.value == CoworkLinkState.CONNECTED)
                try { CoworkConnection.refreshApps() } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        AppPickerSheet("不送去判断的应用", "这些应用的通知不读取、不外发；你的手机和它的手机上的同一个应用一起排除",
            excludeRows(personal, work.map { it.packageName to it.label }, excluded) { appLabel(context, it) }, excluded, dismiss = { excludePicker = false },
            extra = { item(key = "typed") { SheetRow("手动输入包名", "列表里没有的应用", Glyph.Pencil) { excludePicker = false; excludeDialog = true } } }) {
            NotifyCenter.setExcluded(it.toSet()); excludePicker = false
        }
    }
    if (excludeDialog) InputDialog("不送去判断的应用", "输入包名，用逗号分隔，例如 com.android.bankapp；留空表示不排除", excluded.sorted().joinToString(","),
        { excludeDialog = false }, allowBlank = true) { text ->
        NotifyCenter.setExcluded(NotifyDefaults.words(text).toSet()); excludeDialog = false
    }
}

/** The owner's own apps with a launcher icon (needs the launcher `<queries>` entry in the manifest). */
private fun launcherApps(context: android.content.Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
}

/** One row per package across both phones (exclusion matches the package in both); what is already excluded comes first. */
internal fun excludeRows(personal: List<Pair<String, String>>, work: List<Pair<String, String>>, excluded: Set<String>, fallback: (String) -> String): List<PickerApp> {
    val labels = LinkedHashMap<String, String>()
    (personal + work).forEach { (pkg, label) -> labels.putIfAbsent(pkg, label) }
    excluded.forEach { labels.putIfAbsent(it, fallback(it)) }
    val mine = personal.map { it.first }.toSet(); val its = work.map { it.first }.toSet()
    return labels.filterKeys { it != NotifyDefaults.OWN_PACKAGE }.map { (pkg, label) ->
        PickerApp(pkg, label, when {
            pkg in mine && pkg in its -> "你的手机和它的手机"
            pkg in mine -> "你的手机"
            pkg in its -> "它的手机"
            else -> pkg
        })
    }.sortedWith(compareBy<PickerApp>({ it.packageName !in excluded }, { it.label }))
}
