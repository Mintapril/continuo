package dev.dsh.pocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.ScheduleFormat
import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import dev.dsh.pocket.data.objects
import dev.dsh.pocket.data.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class AutomationFilter(val label: String) { ALL("全部"), ACTIVE("已开启"), ENDED("未运行") }

/**
 * DSH's own automation (dsh-schedule): the switch for the optional bundle, every stored task across sessions, and
 * per task its rule, next run, saved runs, edits and deletion. A new task is drafted as a request to the Cowork model,
 * because the host creates tasks only through the model's schedule_create.
 */
@Composable internal fun AutomationScreen(vm: PocketViewModel, openDrawer: () -> Unit, draft: (String) -> Unit) {
    val center = vm.schedules
    LaunchedEffect(vm.connected) { if (vm.connected) vm.schedulesRefresh() }
    var filter by remember { mutableStateOf(AutomationFilter.ALL) }
    var detail by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var confirmDelete by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    val readOnly = center.bundle?.text("readOnlyReason").orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.Menu, "打开菜单", onClick = openDrawer)
            Text("自动化任务", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            CircleButton(Glyph.Plus, "新建自动化任务", enabled = center.service == true, onClick = { creating = true })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp)) {
            SectionLabel("功能")
            InsetGroup {
                when {
                    !vm.connected -> GroupRow("连接 DSH 后可用", icon = Glyph.Clock, chevron = false)
                    !center.bundleRead -> GroupRow("正在读取…", icon = Glyph.Clock, chevron = false, trailing = { Spinner() })
                    center.bundle == null && center.bundleError.isBlank() -> GroupRow("本机 DSH 没有自动化任务组件", icon = Glyph.Clock, chevron = false,
                        subtitle = "插件列表里找不到包含 dsh-schedule 的组件（dsh-experimental-schedule-bundle）。")
                    else -> GroupRow("启用自动化任务", icon = Glyph.Clock, chevron = false,
                        subtitle = when {
                            center.bundleError.isNotBlank() -> "插件列表读取失败：${center.bundleError}"
                            readOnly.isNotBlank() -> "此组件由 profile 配置管理，不能在这里开关"
                            else -> "DSH 实验功能：在会话中按时间或周期安排任务，到期后发回原会话执行"
                        },
                        trailing = {
                            if (center.busy) Spinner(size = 14.dp)
                            else if (center.bundle != null) Switch(center.enabled == true, { vm.schedulesEnable(it) }, enabled = readOnly.isBlank(),
                                colors = SwitchDefaults.colors(checkedTrackColor = Pocket.accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = Pocket.hairline))
                        })
                }
            }
            Footnote("开启后，Cowork 和 Code 会话都能用 schedule_create 等 4 个工具创建一次性、间隔、每天、每周或 cron 任务；每次请求会多带这 4 个工具说明和一条当前时间。关闭后已存的任务留在本机，但不再执行。")
            if (center.service == true) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutomationFilter.entries.forEach { f -> Pill(f.label, selected = filter == f, trailing = null) { filter = f } }
                }
                val rows = center.tasks.filter { when (filter) { AutomationFilter.ALL -> true; AutomationFilter.ACTIVE -> it.text("status") == "active"; AutomationFilter.ENDED -> it.text("status") != "active" } }
                if (rows.isEmpty()) InsetGroup {
                    GroupRow(if (filter == AutomationFilter.ALL) "还没有自动化任务" else "没有${filter.label}的任务", icon = Glyph.Clock, chevron = false,
                        subtitle = if (filter == AutomationFilter.ALL) "点右上角 + 写下要做的事和时间，或直接在 Cowork 任务里说“每天 9 点帮我……”" else null)
                }
                rows.forEach { entry -> TaskCard(vm, entry) { detail = entry.text("id") } }
                Footnote("任务由手机上的 DSH 执行：DSH 必须在运行。到期时若另一个 Cowork 任务正占用副屏，或执行器未连接，这次运行会失败，DSH 不会自动重试；周期任务等下一次。Cowork 的定时运行不能新建、修改或删除任务，高影响操作仍需你确认。")
            } else if (center.error.isNotBlank()) {
                InsetGroup { GroupRow("任务目录读取失败", icon = Glyph.Warning, chevron = false, subtitle = center.error, onClick = { vm.schedulesRefresh() }) }
            }
        }
    }
    // A task that left the catalog (deleted here or elsewhere) closes its sheet.
    val shown = detail?.let { id -> center.tasks.firstOrNull { it.text("id") == id } }
    LaunchedEffect(detail, shown == null) { if (detail != null && shown == null) detail = null }
    shown?.let { entry ->
        TaskDetailSheet(vm, entry, dismiss = { detail = null },
            edit = { editing = entry; detail = null }, runs = { history = entry; detail = null }, remove = { confirmDelete = entry; detail = null },
            open = { vm.openSession(entry.text("sessionId")); detail = null })
    }
    editing?.let { entry -> EditTaskSheet(vm, entry) { editing = null } }
    history?.let { entry -> HistorySheet(vm, entry) { history = null } }
    confirmDelete?.let { entry ->
        ConfirmDialog("删除自动化任务", "「${entry.text("title")}」和它保存的运行记录会被永久删除，之后不再执行；原会话和已发出的消息保留。", "删除", destructive = true,
            dismiss = { confirmDelete = null }) { vm.scheduleDelete(entry); confirmDelete = null }
    }
    if (creating) NewTaskSheet(dismiss = { creating = false }) { text -> creating = false; draft(text) }
}

private fun sessionName(vm: PocketViewModel, id: String) = vm.sessions.firstOrNull { it.text("sessionId") == id }?.let { sessionTitle(it) } ?: "会话 ${id.take(8)}"

@Composable private fun TaskCard(vm: PocketViewModel, entry: JSONObject, open: () -> Unit) {
    val active = entry.text("status") == "active"
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.takeIf { !Pocket.dark } ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, Pocket.hairline)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Clock, null, size = 16.dp, tint = if (active) Pocket.accent else Pocket.faint); Spacer(Modifier.width(8.dp))
                Text(entry.text("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (active) "已开启" else "未运行", style = MaterialTheme.typography.labelSmall, color = if (active) Pocket.success else Pocket.faint)
            }
            Text(ScheduleFormat.frequency(entry), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
            Text(if (active) "下次 ${ScheduleFormat.localTime(entry.text("scheduledAt"))} · ${ScheduleFormat.relative(entry.text("scheduledAt"))}" else "不再执行",
                style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Text(sessionName(vm, entry.text("sessionId")), style = MaterialTheme.typography.labelSmall, color = Pocket.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun TaskDetailSheet(vm: PocketViewModel, entry: JSONObject, dismiss: () -> Unit, edit: () -> Unit, runs: () -> Unit, remove: () -> Unit, open: () -> Unit) {
    val active = entry.text("status") == "active"
    PocketSheet(dismiss) {
        SheetHeader(entry.text("title"), ScheduleFormat.frequency(entry))
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            DetailLine("状态", if (active) "已开启" else "未运行（不再执行，可查看和删除）")
            if (active) DetailLine("下次运行", "${ScheduleFormat.localTime(entry.text("scheduledAt"))}（${ScheduleFormat.relative(entry.text("scheduledAt"))}）")
            entry.text("timeZone").takeIf { it.isNotBlank() }?.let { DetailLine("时区", it) }
            if (entry.text("kind") == "cron") DetailLine("表达式", entry.text("expression"))
            entry.optJSONObject("lastDelivery")?.let { DetailLine("上次发出", ScheduleFormat.localTime(it.text("deliveredAt"))) }
            DetailLine("会话", sessionName(vm, entry.text("sessionId")))
            Text("到时要做", Modifier.padding(top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            Text(entry.text("prompt"), style = MaterialTheme.typography.bodyMedium)
            Text("“上次发出”只表示提醒已送进会话，不代表任务已完成；结果请在会话里查看。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(open) { Text("打开会话", color = Pocket.accent) }
            TextButton(edit, enabled = active) { Text("编辑", color = if (active) Pocket.accent else Pocket.faint) }
            TextButton(runs) { Text("运行记录", color = Pocket.accent) }
            Spacer(Modifier.weight(1f))
            TextButton(remove) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Kind pills and the fields of the chosen rule; shared by the edit and the new-task sheets. */
@Composable private fun TimingEditor(value: TimingDraft, change: (TimingDraft) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("once" to "仅一次", "daily" to "每天", "weekly" to "每周", "every" to "间隔", "cron" to "Cron").forEach { (kind, label) ->
            Pill(label, selected = value.kind == kind, trailing = null) { change(value.copy(kind = kind)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    val field = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    when (value.kind) {
        "once" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value.date, { change(value.copy(date = it)) }, Modifier.weight(1.4f), singleLine = true, label = { Text("日期 yyyy-MM-dd") }, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(value.time, { change(value.copy(time = it)) }, Modifier.weight(1f), singleLine = true, label = { Text("时间") }, shape = RoundedCornerShape(12.dp))
        }
        "daily" -> OutlinedTextField(value.time, { change(value.copy(time = it)) }, field, singleLine = true, label = { Text("时间 HH:mm") }, shape = RoundedCornerShape(12.dp))
        "weekly" -> {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, day ->
                    val number = index + 1
                    Pill(day, selected = number in value.days, trailing = null) { change(value.copy(days = if (number in value.days) value.days - number else value.days + number)) }
                }
            }
            OutlinedTextField(value.time, { change(value.copy(time = it)) }, field, singleLine = true, label = { Text("时间 HH:mm") }, shape = RoundedCornerShape(12.dp))
        }
        "every" -> OutlinedTextField(value.minutes, { change(value.copy(minutes = it.filter(Char::isDigit).take(6))) }, field, singleLine = true, label = { Text("每隔多少分钟") }, shape = RoundedCornerShape(12.dp))
        "cron" -> OutlinedTextField(value.cron, { change(value.copy(cron = it)) }, field, singleLine = true, label = { Text("Cron（分 时 日 月 周），如 0 9 * * 1-5") }, shape = RoundedCornerShape(12.dp))
    }
    if (value.kind != "every") OutlinedTextField(value.zone, { change(value.copy(zone = it.trim())) }, field, singleLine = true, label = { Text("时区（IANA）") }, shape = RoundedCornerShape(12.dp))
    value.problem()?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun EditTaskSheet(vm: PocketViewModel, entry: JSONObject, dismiss: () -> Unit) {
    var title by remember { mutableStateOf(entry.text("title")) }
    var prompt by remember { mutableStateOf(entry.text("prompt")) }
    val initial = remember { TimingDraft.of(entry) }
    var timing by remember { mutableStateOf(initial) }
    val timingChanged = timing != initial
    val valid = title.isNotBlank() && title.trim().length <= 120 && prompt.isNotBlank() && (!timingChanged || timing.problem() == null)
    PocketSheet(dismiss) {
        SheetHeader("编辑自动化任务", "修改后立即生效，会话不受影响")
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 240.dp), label = { Text("到时要做") }, shape = RoundedCornerShape(12.dp))
            Text("时间", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            TimingEditor(timing) { timing = it }
            if (!timingChanged) Text("时间未改动：保存时保留原来的下次运行时间。", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss) { Text("取消", color = Pocket.muted) }
                TextButton({
                    vm.scheduleUpdate(entry, title.trim().takeIf { it != entry.text("title") }, prompt.trim().takeIf { it != entry.text("prompt") },
                        if (timingChanged) timing.change() else null, saved = dismiss)
                }, enabled = valid) { Text("保存", color = if (valid) Pocket.accent else Pocket.faint) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun HistorySheet(vm: PocketViewModel, entry: JSONObject, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    fun load(before: String?) = scope.launch {
        loading = true
        try {
            val result = vm.schedules.history(entry, before)
            records = (if (before == null) emptyList() else records) + result.objects("records")
            next = result.text("nextBefore").ifBlank { null }; page = result
        } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.showError(e.message ?: "运行记录读取失败") }
        finally { loading = false }
    }
    LaunchedEffect(entry.text("id")) { load(null) }
    PocketSheet(dismiss) {
        SheetHeader("运行记录", entry.text("title"))
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (records.isEmpty() && !loading) Text("还没有运行过。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
            records.forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${ScheduleFormat.localTime(record.text("scheduledAt"))} 到期 · ${ScheduleFormat.localTime(record.text("deliveredAt"))} 发出",
                        style = MaterialTheme.typography.labelMedium)
                    record.text("prompt").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                HorizontalDivider(color = Pocket.hairline)
            }
            if (loading) Spinner(Modifier.padding(12.dp))
            else if (next != null) TextButton({ load(next) }) { Text("加载更早的记录", color = Pocket.accent) }
            page?.let { p ->
                val retention = p.optJSONObject("retention")
                if (next == null && (p.optBoolean("earlierRecordsPruned") || p.optBoolean("earlierRecordsUnavailable")))
                    Text("更早的记录不可用${retention?.let { "（保留最近 ${it.optInt("days")} 天、最多 ${it.optInt("records")} 条）" }.orEmpty()}。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            }
            Text("记录只表示提醒已送进会话；执行结果在会话里。", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        }
    }
}

@Composable private fun NewTaskSheet(dismiss: () -> Unit, draft: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf(TimingDraft()) }
    val valid = title.isNotBlank() && task.isNotBlank() && timing.problem() == null
    PocketSheet(dismiss) {
        SheetHeader("新建自动化任务", "生成一条 Cowork 请求，由模型创建任务")
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称，如“每日签到”") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 240.dp), label = { Text("到时要做什么") }, shape = RoundedCornerShape(12.dp))
            Text("时间", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            TimingEditor(timing) { timing = it }
            Text("点“写入新任务”后会回到首页，请求已填进 Cowork 输入框；检查后由你发送。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss) { Text("取消", color = Pocket.muted) }
                TextButton({ draft(ScheduleFormat.request(title, task, timing)) }, enabled = valid) { Text("写入新任务", color = if (valid) Pocket.accent else Pocket.faint) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
