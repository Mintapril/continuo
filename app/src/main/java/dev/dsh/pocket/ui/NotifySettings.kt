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
import dev.dsh.pocket.notify.NotifyCenter
import dev.dsh.pocket.notify.NotifyDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Notification triggers (cowork-plugin/src/notify.mjs): every app's notifications join one batch that goes to Jev; a
// batch it judges meaningful wakes a Cowork session, and the model decides what to do. No per-app rules.

@Composable private fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    androidx.compose.material3.Switch(checked, onChange, enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = Pocket.accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = Pocket.hairline))

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
    LaunchedEffect(vm.connected) { if (vm.connected) vm.jevRefresh() }
    var keyDialog by remember { mutableStateOf(false) }
    var excludeDialog by remember { mutableStateOf(false) }
    var quietDialog by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    SettingsPage("通知触发", back) {
        SectionLabel("开关")
        InsetGroup {
            GroupRow("把通知交给 Jev 判断", icon = Glyph.Filter, chevron = false,
                subtitle = if (on) "有实际意义时唤起 Cowork，由 AI 决定做什么" else "关闭：不读取任何通知",
                trailing = { Toggle(on) { NotifyCenter.setEnabled(it) } })
            GroupDivider()
            GroupRow("不送去判断的应用", icon = Glyph.Shield, subtitle = "这些应用的通知不读取、不外发",
                value = if (excluded.isEmpty()) "无" else "${excluded.size} 个", onClick = { excludeDialog = true })
            GroupDivider()
            GroupRow("合并等待", icon = Glyph.Clock, subtitle = "最后一条通知后再等这么久，才整批交给 Jev",
                value = "$quiet 秒", onClick = { quietDialog = true })
        }
        Footnote("开启后，除排除的应用外，所有应用的通知标题和正文都会经 OpenRouter 发给 TypeSafe 的 Jev（typesafe/jev-1.14）判断；" +
            "判为有实际意义的那几条再作为“他人写的、不可信的内容”交给 Cowork 会话，也就会发到模型服务。验证码、银行、私密聊天也不例外，建议把这类应用加入排除。Jev 无法判断（无密钥、超时等）时不唤起。")

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
            GroupDivider()
            GroupRow("Jev 密钥（OpenRouter）", icon = Glyph.Key, subtitle = if (vm.connected) "保存在本机 DSH 的凭据存储里" else "需先连接本机 DSH",
                value = when (vm.jevConfigured) { true -> "已配置"; false -> "未配置"; null -> "未知" }, enabled = vm.connected, onClick = { keyDialog = true })
            if (vm.jevConfigured == true) {
                GroupDivider()
                GroupRow("移除密钥", icon = Glyph.Trash, tint = MaterialTheme.colorScheme.error, chevron = false, enabled = vm.connected, onClick = { vm.jevClear() })
            }
        }
        Footnote(if (ingress == false) "通知入口未就绪：DSH 没有运行，或手机上的 Cowork 插件还没有部署通知触发。点一下重新检查。"
            else "收到通知后先等 $quiet 秒；期间任何应用的新通知都并进同一批并重新计时，$quiet 秒没有新通知就整批交给 Jev" +
                "（一直不停时最多攒 ${NotifyDefaults.MAX_WAIT_MS / 60_000} 分钟）。Jev 逐条判断，判为有意义的那几条交给该应用固定的 Cowork 会话；副屏正被任务占用时排队，空闲后开始。")

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
    if (excludeDialog) InputDialog("不送去判断的应用", "输入包名，用逗号分隔，例如 com.android.bankapp；留空表示不排除", excluded.sorted().joinToString(","),
        { excludeDialog = false }, allowBlank = true) { text ->
        NotifyCenter.setExcluded(NotifyDefaults.words(text).toSet()); excludeDialog = false
    }
}
