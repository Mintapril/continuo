package dev.dsh.pocket.ui

import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.Companion
import dev.dsh.pocket.data.Identity
import dev.dsh.pocket.data.ScheduleFormat
import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import dev.dsh.pocket.data.isAppSession
import dev.dsh.pocket.data.objects
import dev.dsh.pocket.data.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Whose note: one the owner wrote (unmarked), one it left itself ([便条]), or a patrol ([巡视]). */
private fun ownNote(entry: JSONObject) = entry.text("prompt").trimStart().startsWith("[便条]")
private fun patrolNote(entry: JSONObject) = entry.text("prompt").trimStart().startsWith(ScheduleFormat.PATROL_MARKER)
/** The words on the sticky, without the markers. */
private fun noteWords(entry: JSONObject) = entry.text("prompt").trim().removePrefix("[便条]").removePrefix(ScheduleFormat.PATROL_MARKER).trim()
    .ifBlank { entry.text("title") }

/**
 * Every note (便条): the owner's, the ones it left itself and the patrol, as yellow stickies, soonest first; the ones
 * that will not run again folded away. A tap opens a note: 改, 撤下, its deliveries, the conversation it belongs to.
 * New notes come from 留便条 (the note sheet), stored at once and in the owner's words.
 */
@Composable internal fun NotesPane(vm: PocketViewModel, identity: Identity) {
    val center = vm.schedules
    var detail by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var confirmDelete by remember { mutableStateOf<JSONObject?>(null) }
    var showEnded by remember { mutableStateOf(false) }
    var patrolBusy by remember { mutableStateOf(false) }
    val readOnly = center.bundle?.text("readOnlyReason").orEmpty()
    val active = center.tasks.filter { it.text("status") == "active" }.sortedBy { it.text("scheduledAt") }
    val ended = center.tasks.filter { it.text("status") != "active" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp).animateContentSize()) {
        when {
            !vm.connected -> Hint("连上本机 DSH 后显示便条")
            !center.bundleRead -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Spinner(); Spacer(Modifier.width(8.dp)); Text("正在读取便条…", color = Pocket.muted) }
            center.bundle == null && center.bundleError.isBlank() -> Hint("本机 DSH 没有便条组件（dsh-schedule）")
            center.enabled == false && !center.builtIn -> InsetGroup {
                GroupRow("启用便条", icon = Glyph.Clock, chevron = false,
                    subtitle = if (readOnly.isNotBlank()) "此组件由 profile 配置管理，不能在这里开关" else "DSH 的便条组件没开：开了之后便条才会按时送到",
                    trailing = {
                        if (center.busy) Spinner(size = 14.dp)
                        else Switch(false, { vm.schedulesEnable(it) }, enabled = readOnly.isBlank(),
                            colors = SwitchDefaults.colors(checkedTrackColor = Pocket.accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = Pocket.hairline))
                    })
            }
            center.service != true && center.error.isNotBlank() -> InsetGroup { GroupRow("便条读取失败", icon = Glyph.Warning, chevron = false, subtitle = center.error, onClick = { vm.schedulesRefresh() }) }
        }
        if (center.service == true) {
            AnimatedVisibility(active.none(::patrolNote), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("让${identity.shown}自己逛逛", style = MaterialTheme.typography.titleSmall)
                            Text("巡视：每天 9 点到 21 点每 3 小时一次，挑两三个应用逛。会花模型的钱。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                        }
                        TextButton({ patrolBusy = true; vm.startPatrol { patrolBusy = false; it.onSuccess { vm.showError("巡视贴上了，到点它就去逛") }.onFailure { e -> vm.showError(e.message ?: "巡视没有贴上") } } },
                            enabled = !patrolBusy) { Text(if (patrolBusy) "正在贴…" else "开始巡视", color = Pocket.accent) }
                    }
                }
            }
            if (active.isEmpty()) Hint("还没有便条。点下面的“留便条”，写下要它以后做的事和时间。")
            active.forEach { entry -> key(entry.text("id")) { Sticky(vm, entry) { detail = entry.text("id") } } }
            if (ended.isNotEmpty()) {
                TextButton({ showEnded = !showEnded }) { Text(if (showEnded) "收起已结束的便条" else "已结束的便条 ${ended.size} 张", color = Pocket.muted) }
                AnimatedVisibility(showEnded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column { ended.forEach { entry -> key(entry.text("id")) { Sticky(vm, entry) { detail = entry.text("id") } } } }
                }
            }
            Footnote("便条由手机上的 DSH 按时送到：到点时它若正忙着别的，会先收尾再来做（最多等 30 分钟）。你写的便条它照做；它自己贴的便条，到时它自己判断还要不要做。")
        }
    }
    // A note that left the catalog (deleted here or elsewhere) closes its sheet.
    val shown = detail?.let { id -> center.tasks.firstOrNull { it.text("id") == id } }
    LaunchedEffect(detail, shown == null) { if (detail != null && shown == null) detail = null }
    shown?.let { entry ->
        NoteDetailSheet(vm, entry, identity, dismiss = { detail = null },
            edit = { editing = entry; detail = null }, runs = { history = entry; detail = null }, remove = { confirmDelete = entry; detail = null },
            open = { vm.openSession(entry.text("sessionId")); detail = null })
    }
    editing?.let { entry -> EditNoteSheet(vm, entry) { editing = null } }
    history?.let { entry -> HistorySheet(vm, entry) { history = null } }
    confirmDelete?.let { entry ->
        ConfirmDialog("撤下这张便条？", "「${noteWords(entry).take(40)}」撤下后不再送到，它的送达记录也一起删掉；会话和已经发出的消息都保留。", "撤下", destructive = true,
            dismiss = { confirmDelete = null }) { vm.scheduleDelete(entry); confirmDelete = null }
    }
}

@Composable private fun Hint(text: String) =
    Text(text, Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)

internal fun noteSessionName(vm: PocketViewModel, id: String): String = when {
    Companion.isOwnerSession(id) -> if (vm.ownerSession == id) "你们俩的对话" else "以前的对话"
    id == ScheduleFormat.PATROL_SESSION -> "巡视"
    else -> vm.sessions.firstOrNull { it.text("sessionId") == id }?.let { row -> sessionTitle(row).let { if (isAppSession(row)) it.substringBefore(" · ") else it } } ?: "会话 ${id.take(8)}"
}

@Composable private fun Sticky(vm: PocketViewModel, entry: JSONObject, open: () -> Unit) {
    val active = entry.text("status") == "active"
    Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(6.dp), color = if (active) Pocket.sticky else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (active) Pocket.onSticky else Pocket.muted, shadowElevation = if (active) 2.dp else 0.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when { patrolNote(entry) -> "巡视"; ownNote(entry) -> "它自己贴的"; else -> "你贴的" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(if (active) Companion.nextText(entry.text("scheduledAt")) else "不再送到", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
            Text(noteWords(entry), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("${Companion.frequency(entry)} · ${noteSessionName(vm, entry.text("sessionId"))}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun NoteDetailSheet(vm: PocketViewModel, entry: JSONObject, identity: Identity, dismiss: () -> Unit, edit: () -> Unit, runs: () -> Unit, remove: () -> Unit, open: () -> Unit) {
    val active = entry.text("status") == "active"
    PocketSheet(dismiss) {
        SheetHeader(if (ownNote(entry)) "${identity.shown}自己贴的便条" else if (patrolNote(entry)) "巡视" else "你贴的便条", Companion.frequency(entry))
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(6.dp), color = Pocket.sticky, contentColor = Pocket.onSticky) {
                Text(noteWords(entry), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
            }
            DetailLine("状态", if (active) "贴着，下次 ${Companion.nextText(entry.text("scheduledAt"))}" else "不再送到（可以查看和撤下）")
            if (active) DetailLine("下次送到", ScheduleFormat.localTime(entry.text("scheduledAt")))
            entry.text("timeZone").takeIf { it.isNotBlank() }?.let { DetailLine("时区", it) }
            if (entry.text("kind") == "cron") DetailLine("表达式", entry.text("expression"))
            entry.optJSONObject("lastDelivery")?.let { DetailLine("上次送到", ScheduleFormat.localTime(it.text("deliveredAt"))) }
            DetailLine("在哪", noteSessionName(vm, entry.text("sessionId")))
            if (ownNote(entry)) Text("这是它自己做事时给自己留的，到时它会自己判断还要不要做。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(open) { Text("看原话", color = Pocket.accent) }
            TextButton(edit, enabled = active) { Text("改", color = if (active) Pocket.accent else Pocket.faint) }
            TextButton(runs) { Text("送达记录", color = Pocket.accent) }
            Spacer(Modifier.weight(1f))
            TextButton(remove) { Text("撤下", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Kind pills and the fields of the chosen rule; shared by the edit sheet and the note sheet's 自定义. */
@Composable internal fun TimingEditor(value: TimingDraft, kinds: List<Pair<String, String>> = TIMING_KINDS, showZone: Boolean = true, change: (TimingDraft) -> Unit) {
    if (kinds.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { (kind, label) -> Pill(label, selected = value.kind == kind, trailing = null) { change(value.copy(kind = kind)) } }
        }
        Spacer(Modifier.height(8.dp))
    }
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
    if (showZone && value.kind != "every") OutlinedTextField(value.zone, { change(value.copy(zone = it.trim())) }, field, singleLine = true, label = { Text("时区（IANA）") }, shape = RoundedCornerShape(12.dp))
    value.problem()?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
}
internal val TIMING_KINDS = listOf("once" to "仅一次", "daily" to "每天", "weekly" to "每周", "every" to "间隔", "cron" to "Cron")

@Composable private fun EditNoteSheet(vm: PocketViewModel, entry: JSONObject, dismiss: () -> Unit) {
    var title by remember { mutableStateOf(entry.text("title")) }
    var prompt by remember { mutableStateOf(entry.text("prompt")) }
    val initial = remember { TimingDraft.of(entry) }
    var timing by remember { mutableStateOf(initial) }
    val timingChanged = timing != initial
    val valid = title.isNotBlank() && title.trim().length <= 120 && prompt.isNotBlank() && (!timingChanged || timing.problem() == null)
    PocketSheet(dismiss) {
        SheetHeader("改便条", "改完马上生效")
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 240.dp), label = { Text("到时要做") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称") }, shape = RoundedCornerShape(12.dp))
            Text("时间", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            TimingEditor(timing) { timing = it }
            if (!timingChanged) Text("时间没改：保存时保留原来的下次送到时间。", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss) { Text("取消", color = Pocket.muted) }
                TextButton({
                    val receipt = if (timingChanged) Companion.editReceipt(Companion.whenText(initial), Companion.whenText(timing)) else "已改"
                    vm.scheduleUpdate(entry, title.trim().takeIf { it != entry.text("title") }, prompt.trim().takeIf { it != entry.text("prompt") },
                        if (timingChanged) timing.change() else null, receipt, saved = dismiss)
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) { vm.showError(e.message ?: "送达记录读取失败") }
        finally { loading = false }
    }
    LaunchedEffect(entry.text("id")) { load(null) }
    PocketSheet(dismiss) {
        SheetHeader("送达记录", noteWords(entry).take(40))
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (records.isEmpty() && !loading) Text("还没有送到过。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
            records.forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${ScheduleFormat.localTime(record.text("scheduledAt"))} 到点 · ${ScheduleFormat.localTime(record.text("deliveredAt"))} 送到",
                        style = MaterialTheme.typography.labelMedium)
                    record.text("prompt").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                HorizontalDivider(color = Pocket.hairline)
            }
            if (loading) Spinner(Modifier.padding(12.dp))
            else if (next != null) TextButton({ load(next) }) { Text("更早的记录", color = Pocket.accent) }
            page?.let { p ->
                val retention = p.optJSONObject("retention")
                if (next == null && (p.optBoolean("earlierRecordsPruned") || p.optBoolean("earlierRecordsUnavailable")))
                    Text("更早的记录不在了${retention?.let { "（保留最近 ${it.optInt("days")} 天、最多 ${it.optInt("records")} 条）" }.orEmpty()}。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            }
            Text("送到只表示便条已经交给它；做得怎样看聊天记录或原话。", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        }
    }
}
