@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.*
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun ChatScreen(vm: PocketViewModel, openDrawer: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    var followBottom by remember(vm.selected) { mutableStateOf(true) }
    var lateQuestion by remember(vm.selected) { mutableStateOf<JSONObject?>(null) }
    val entries = remember(vm.rows) { chatEntries(vm.rows) }
    val summary = vm.selectedSummary
    val mode = sessionMode(summary)
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collect { moving -> if (moving) followBottom = !list.canScrollForward } }
    LaunchedEffect(vm.rows, vm.running) { if (followBottom && entries.isNotEmpty()) list.scrollToItem(entries.size + 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Top bar: menu · title (tap for options) · background jobs
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Glyph.Menu, "打开菜单", onClick = openDrawer)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { sheet = "menu" }.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sessionTitle(summary), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(2.dp)); GlyphIcon(Glyph.ChevronDown, "会话选项", size = 14.dp, tint = Pocket.muted)
                }
                val live = vm.streamStatus == "实时已连接"
                Text(listOfNotNull(mode.title, workspaceLabel(summary?.text("cwd")), presetTag(summary), vm.streamStatus.takeIf { !live && it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Pocket.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val runningJobs = vm.jobs.count { it.text("status") == "running" }
            Box {
                CircleButton(Glyph.More, "更多选项：权限、工作区与后台任务") { sheet = "menu" }
                if (runningJobs > 0) StatusDot(Pocket.accent, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp), pulse = true)
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "history") {
                    when {
                        vm.hasMore -> TextButton({ vm.older() }, enabled = !vm.historyBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (vm.historyBusy) "正在加载…" else "加载更早的消息", style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
                        }
                        vm.rows.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ContinuoMark(size = 36.dp, mode = if (vm.streamStatus != "实时已连接") CursorMode.BLINK else CursorMode.STEADY)
                            Spacer(Modifier.height(14.dp))
                            Text(if (vm.streamStatus == "实时已连接") "还没有消息" else "正在同步会话…", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                        }
                    }
                }
                items(entries, key = { it.key }) { entry ->
                    when (entry.kind) {
                        "tools" -> ToolGroup(vm, entry.rows)
                        "activity" -> ActivityGroup(entry.rows)
                        else -> MessageRow(vm, entry.rows.single())
                    }
                }
                item(key = "generation") { if (vm.running) Working(vm) }
            }
            this@Column.AnimatedVisibility(!followBottom && list.canScrollForward, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), enter = fadeIn(), exit = fadeOut()) {
                CircleButton(Glyph.ArrowDown, "回到最新", size = 36.dp, container = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    followBottom = true; scope.launch { list.animateScrollToItem(entries.size + 1) }
                }
            }
        }
        Column(Modifier.imePadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusStrip(vm, { sheet = "progress" }) { lateQuestion = it }
            Composer(vm, placeholder = if (vm.running) "补充消息，将排队发送" else "回复 DSH…", running = vm.running, onSend = { followBottom = true; vm.send() }) {
                ModelPill(vm) { sheet = "model" }
            }
        }
    }
    lateQuestion?.let { question ->
        val session = vm.selected
        if (session != null) key(session, question.text("callId")) {
            QuestionSheet(question, null, deferred = true, dismiss = { lateQuestion = null }) { answer -> vm.answerContinued(session, question.text("callId"), answer) { lateQuestion = null } }
        }
    }
    val close = { sheet = null }
    when (sheet) {
        "model" -> ModelSheet(vm.catalog, vm.currentModel(), close) { p, m, e -> vm.selectModel(p, m, e); sheet = null }
        "permission" -> PermissionSheet(vm, close)
        "jobs" -> JobsSheet(vm, close)
        "progress" -> ProgressSheet(vm.selectedProjection, close)
        "menu" -> summary?.let { SessionMenu(vm, it, close, inChat = true, openJobs = { sheet = "jobs" }, openPermission = { sheet = "permission" }) } ?: run { sheet = null }
    }
}

/** Compact chips above the composer: progress, goal, deferred questions, queued messages. */
@Composable private fun StatusStrip(vm: PocketViewModel, openProgress: () -> Unit, answerLate: (JSONObject) -> Unit) {
    val todos = todoItems(vm.selectedProjection)
    val goal = goalInfo(vm.selectedProjection)
    val continued = continuedQuestions(vm.selectedProjection)
    val inbox = vm.selectedProjection.optJSONObject("inbox")
    val queued = (inbox?.optJSONArray("next-turn")?.length() ?: 0) + (inbox?.optJSONArray("next-step")?.length() ?: 0)
    if (todos.isEmpty() && goal == null && continued.isEmpty() && queued == 0) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (continued.isNotEmpty()) Surface(onClick = { answerLate(continued.first()) }, shape = CircleShape, color = Pocket.accent.copy(alpha = .14f), contentColor = Pocket.accentPressed) {
            Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Question, null, size = 15.dp); Spacer(Modifier.width(6.dp))
                Text("${continued.size} 个问题可补充回答", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (todos.isNotEmpty()) {
            val done = todos.count { it.status == "completed" }
            val current = todos.firstOrNull { it.status == "in_progress" }?.content
            Pill(listOfNotNull("进度 $done/${todos.size}", current).joinToString(" · "), leading = Glyph.List, trailing = Glyph.ChevronUp, onClick = openProgress)
        } else if (goal != null) Pill("目标 · ${goalPhaseLabel(goal.phase)}", leading = Glyph.Eye, trailing = Glyph.ChevronUp, onClick = openProgress)
        if (queued > 0) Pill("$queued 条排队中", leading = Glyph.Clock, trailing = null, enabled = false) {}
    }
}

@Composable private fun ProgressSheet(projection: JSONObject, dismiss: () -> Unit) {
    val todos = todoItems(projection); val goal = goalInfo(projection)
    PocketSheet(dismiss) {
        SheetHeader("进度", if (todos.isNotEmpty()) "${todos.count { it.status == "completed" }} / ${todos.size} 已完成" else null)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            goal?.let { g ->
                InsetGroup(Modifier.padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("目标 · ${goalPhaseLabel(g.phase)}", style = MaterialTheme.typography.labelMedium, color = Pocket.accent)
                        Text(g.objective, style = MaterialTheme.typography.bodyLarge, fontFamily = Fonts.sans)
                        if (g.blockedReason.isNotBlank()) Text(g.blockedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            todos.forEachIndexed { index, todo ->
                val done = todo.status == "completed"; val current = todo.status == "in_progress"
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    Surface(Modifier.size(22.dp), shape = CircleShape, color = if (done) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        border = if (done) null else BorderStroke(1.5.dp, if (current) Pocket.accent else Pocket.hairline)) {
                        Box(contentAlignment = Alignment.Center) {
                            when { done -> GlyphIcon(Glyph.Check, "已完成", size = 14.dp, tint = MaterialTheme.colorScheme.surface)
                                current -> Spinner(size = 11.dp, color = Pocket.accent)
                                else -> Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = Pocket.muted) }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(todo.content, Modifier.weight(1f).padding(top = 1.dp), style = MaterialTheme.typography.bodyLarge,
                        color = if (done) Pocket.muted else MaterialTheme.colorScheme.onSurface, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        textDecoration = if (done) TextDecoration.LineThrough else null)
                }
            }
            if (todos.isEmpty() && goal == null) Text("暂无进度信息", Modifier.padding(20.dp), color = Pocket.muted)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Working(vm: PocketViewModel) {
    val active = vm.rows.lastOrNull()
    val state = when {
        vm.pending.any { it.text("agentId") == vm.selected } -> "等待你的回答"
        active?.role == "tool" && active.streaming -> "${toolLabel(toolName(active)).verb}…"
        active?.streaming == true && active.text.isNotBlank() -> "正在回复…"
        active?.streaming == true && active.reasoning.isNotBlank() -> "正在思考…"
        else -> "正在处理…"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        ContinuoCursor(height = 18.dp); Spacer(Modifier.width(10.dp))
        Text(state, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
    }
}

@Composable private fun MessageRow(vm: PocketViewModel, row: TranscriptRow) {
    when (row.role) {
        "user" -> UserMessage(vm, row)
        "assistant" -> AssistantMessage(vm, row)
        else -> NoticeRow(row)
    }
}

@Composable private fun UserMessage(vm: PocketViewModel, row: TranscriptRow) {
    var actions by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.End) {
        if (row.title.isNotBlank()) Text(row.title, Modifier.padding(end = 6.dp, bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        row.images.forEach { AttachmentImage(vm, it, Modifier.fillMaxWidth(.62f).padding(bottom = 6.dp)) }
        if (row.text.isNotBlank()) Box(Modifier.fillMaxWidth(.86f), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(18.dp), color = Pocket.userBubble) {
                Text(row.text, Modifier.combinedClickable(onClick = {}, onLongClick = { haptic(); actions = true }).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (actions) MessageActions(row.text) { actions = false }
}

@Composable private fun AssistantMessage(vm: PocketViewModel, row: TranscriptRow) {
    var actions by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth()) {
        val label = when {
            row.title.startsWith("Assistant (interrupted)") -> "回复已中断"
            row.title.startsWith("Assistant attempt") -> "未提交的回复"
            else -> ""
        }
        if (label.isNotEmpty()) Text(label, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
        if (row.reasoning.isNotBlank()) Thinking(row)
        if (row.text.isNotBlank()) MarkdownText(row.text)
        row.images.forEach { AttachmentImage(vm, it, Modifier.fillMaxWidth().padding(top = 8.dp)) }
        if (row.error) ErrorDetail(row.detail.ifBlank { row.text })
        if (!row.streaming && row.text.isNotBlank()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ haptic(); clipboard.setText(AnnotatedString(row.text)) }, Modifier.size(36.dp)) { GlyphIcon(Glyph.Copy, "复制回复", size = 18.dp, tint = Pocket.muted) }
            IconButton({ actions = true }, Modifier.size(36.dp)) { GlyphIcon(Glyph.More, "更多操作", size = 18.dp, tint = Pocket.muted) }
        }
    }
    if (actions) MessageActions(row.text) { actions = false }
}

@Composable private fun Thinking(row: TranscriptRow) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val live = row.streaming && row.text.isBlank()
    Column(Modifier.padding(bottom = 2.dp)) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) { ContinuoCursor(height = 14.dp); Spacer(Modifier.width(7.dp)) }
            Text(if (live) "正在思考" else "思考过程", style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
            Spacer(Modifier.width(3.dp)); GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, if (open) "收起思考过程" else "展开思考过程", size = 14.dp, tint = Pocket.muted)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.height(IntrinsicSize.Min).padding(top = 6.dp, start = 4.dp)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Pocket.hairline, CircleShape))
                Spacer(Modifier.width(12.dp))
                SelectionContainer { Text(row.reasoning, style = MaterialTheme.typography.bodySmall, color = Pocket.muted) }
            }
        }
    }
}

@Composable private fun ErrorDetail(detail: String) {
    var open by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Warning, null, size = 16.dp, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                Text(detail.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "出现错误", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (open) SelectionContainer { Text(detail, Modifier.padding(top = 8.dp), fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

/** Failure notices stay visible; they are never collapsed into the activity log. */
@Composable private fun NoticeRow(row: TranscriptRow) {
    if (row.error) {
        Column {
            Text(row.title.ifBlank { "运行记录" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            ErrorDetail(transcriptDetail(row))
        }
    } else ActivityGroup(listOf(row))
}

@Composable private fun ActivityGroup(rows: List<TranscriptRow>) {
    var open by rememberSaveable(rows.first().key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Layers, null, size = 15.dp, tint = Pocket.faint); Spacer(Modifier.width(7.dp))
            Text(if (rows.size == 1) rows[0].title.ifBlank { "会话记录" } else "会话记录 · ${rows.size} 条", style = MaterialTheme.typography.labelMedium, color = Pocket.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(3.dp)); GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, size = 13.dp, tint = Pocket.faint)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(12.dp), color = Pocket.codeBackground) {
                SelectionContainer {
                    Text(rows.joinToString("\n\n") { listOf(it.title, transcriptDetail(it)).filter { s -> s.isNotBlank() }.joinToString("\n") },
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp), fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 17.sp, color = Pocket.muted)
                }
            }
        }
    }
}

private fun toolIcon(kind: String): ImageVector = when (kind) {
    "web" -> Glyph.Globe; "terminal" -> Glyph.Terminal; "phone" -> Glyph.Phone; "question" -> Glyph.Question; "plan" -> Glyph.List
    "agent" -> Glyph.Agent; "write" -> Glyph.Pencil; "read" -> Glyph.File; "search" -> Glyph.Search; "image" -> Glyph.Image; else -> Glyph.Tool
}

/** Consecutive tool calls share one collapsed record. Details stay inside that one card. */
@Composable private fun ToolGroup(vm: PocketViewModel, rows: List<TranscriptRow>) {
    var open by rememberSaveable(rows.first().key) { mutableStateOf(false) }
    val running = rows.count { it.streaming }; val failed = rows.count { it.error }
    val headline = if (rows.size == 1) toolLabel(toolName(rows[0])).verb else "${rows.size} 个步骤"
    val hint = if (rows.size == 1) toolTarget(rows[0]).ifBlank { toolName(rows[0]) }
        else rows.map { toolLabel(toolName(it)).verb }.distinct().joinToString(" · ")
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.Transparent, border = BorderStroke(1.dp, Pocket.hairline)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 30.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(if (rows.size == 1) toolIcon(toolLabel(toolName(rows[0])).kind) else Glyph.Tool, null, size = 16.dp, tint = Pocket.muted); Spacer(Modifier.width(10.dp))
                Text(headline, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = if (rows.size == 1 && toolTarget(rows[0]).isNotBlank()) Fonts.mono else null)
                ToolStatus(running > 0, failed)
                Spacer(Modifier.width(4.dp)); GlyphIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, if (open) "收起工具记录" else "展开工具记录", size = 14.dp, tint = Pocket.faint)
            }
            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    rows.forEach { row ->
                        HorizontalDivider(color = Pocket.hairline)
                        ToolDetail(vm, row)
                    }
                }
            }
        }
    }
}

@Composable private fun ToolStatus(running: Boolean, failed: Int) {
    when {
        running -> Spinner(size = 13.dp)
        failed > 0 -> Text(if (failed > 1) "$failed 失败" else "失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun ToolDetail(vm: PocketViewModel, row: TranscriptRow) {
    val name = toolName(row); val label = toolLabel(name); val target = toolTarget(row)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(toolIcon(label.kind), null, size = 14.dp, tint = if (row.error) MaterialTheme.colorScheme.error else Pocket.muted)
            Spacer(Modifier.width(8.dp))
            Text(label.verb, style = MaterialTheme.typography.labelSmall)
            if (target.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(target, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Fonts.mono)
            }
            Spacer(Modifier.width(8.dp)); ToolStatus(row.streaming, if (row.error) 1 else 0)
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = Pocket.faint, fontFamily = Fonts.mono)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Pocket.codeBackground) {
            SelectionContainer {
                Text(transcriptDetail(row).ifBlank { "等待工具输出…" }, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp),
                    fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 17.sp, color = if (Pocket.dark) Color(0xFFECE9E1) else MaterialTheme.colorScheme.onSurface)
            }
        }
        row.images.forEach { AttachmentImage(vm, it, Modifier.fillMaxWidth()) }
    }
}

@Composable private fun MessageActions(text: String, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var selecting by remember { mutableStateOf(false) }
    PocketSheet(dismiss) {
        if (selecting) {
            SheetHeader("选择文本", leading = { CircleButton(Glyph.ArrowLeft, "返回", size = 36.dp) { selecting = false } })
            SelectionContainer { Text(text, Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge) }
        } else {
            Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = Pocket.hairline, modifier = Modifier.padding(vertical = 4.dp))
            SheetRow("复制消息", icon = Glyph.Copy) { clipboard.setText(AnnotatedString(text)); dismiss() }
            SheetRow("选择文本", icon = Glyph.Pencil) { selecting = true }
        }
    }
}

@Composable private fun AttachmentImage(vm: PocketViewModel, attachment: JSONObject, modifier: Modifier = Modifier) {
    val id = attachment.text("attachmentId")
    var image by remember(id) { mutableStateOf<ByteArray?>(null) }; var error by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { try { image = vm.image(id) } catch (_: Exception) { error = true } }
    if (image != null) AsyncImage(image, attachment.text("name").ifBlank { "会话图片" }, modifier.heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
    else Surface(modifier.height(72.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (error) Text("图片加载失败", style = MaterialTheme.typography.bodySmall, color = Pocket.muted) else Spinner()
        }
    }
}

/** Markdown in native TextView spans (no WebView, no remote images). Assistant prose uses the serif face. */
@Composable internal fun MarkdownText(text: String, modifier: Modifier = Modifier, serif: Boolean = true, size: Float = 14f) {
    val context = LocalContext.current; val fonts = Fonts; val colors = Pocket
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val markwon = remember(context, colors, fonts) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.codeTypeface(fonts.monoTypeface).codeTextColor(ink).codeBackgroundColor(colors.codeBackground.toArgb())
                        .linkColor(colors.link.toArgb()).isLinkUnderlined(false).blockQuoteColor(colors.hairline.toArgb())
                        .headingBreakHeight(0).thematicBreakColor(colors.hairline.toArgb()).listItemColor(colors.muted.toArgb())
                }
            }).build()
    }
    val chunks = remember(text) { text.split("```") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chunks.forEachIndexed { index, chunk ->
            if (index % 2 == 1) CodeBlock(chunk.substringBefore('\n').trim().take(30), chunk.substringAfter('\n', chunk).trimEnd('\n'))
            else if (chunk.isNotBlank()) AndroidView(
                factory = { ctx -> TextView(ctx).apply { setTextIsSelectable(true); setPadding(0, 0, 0, 0); includeFontPadding = false; setLineSpacing(0f, 1.3f) } },
                update = { view ->
                    view.typeface = fonts.sansTypeface; view.textSize = size
                    view.setTextColor(ink); view.setLinkTextColor(colors.link.toArgb()); markwon.setMarkdown(view, chunk.trim('\n'))
                }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Pocket.codeBackground, border = BorderStroke(1.dp, Pocket.hairline)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Pocket.muted, fontFamily = Fonts.mono)
                IconButton({ haptic(); clipboard.setText(AnnotatedString(code)) }, Modifier.size(36.dp)) { GlyphIcon(Glyph.Copy, "复制代码", size = 16.dp, tint = Pocket.muted) }
            }
            SelectionContainer {
                Text(code, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    fontFamily = Fonts.mono, fontSize = 13.sp, lineHeight = 19.sp, color = if (Pocket.dark) Color(0xFFECE9E1) else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The single composer used by the start screens and by sessions. */
@Composable internal fun Composer(vm: PocketViewModel, placeholder: String, modifier: Modifier = Modifier, enabled: Boolean = true, running: Boolean = false,
                                  onSend: () -> Unit, footer: @Composable RowScope.() -> Unit) {
    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (pickerOwner != null && pickerOwner == vm.composerKey) uris.take(5).forEach(vm::addAttachment)
        else if (uris.isNotEmpty()) vm.showError("输入位置已切换，请重新选择附件。")
        pickerOwner = null
    }
    val locked = vm.submitting || vm.uncertain || vm.starting
    val canSend = enabled && vm.connected && !locked && (vm.draft.isNotBlank() || vm.attachments.isNotEmpty())
    val haptic = rememberHaptic()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
        color = if (Pocket.dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, Pocket.hairline), shadowElevation = if (Pocket.dark) 0.dp else 3.dp) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            if (vm.uncertain) Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Warning, null, size = 15.dp, tint = Pocket.warning); Spacer(Modifier.width(6.dp))
                Text("发送结果未确认，重试会沿用同一请求", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                TextButton({ vm.send(true) }, enabled = !vm.submitting) { Text("重试", color = Pocket.accent, style = MaterialTheme.typography.labelSmall) }
            }
            if (vm.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.attachments.forEach { a ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.heightIn(min = 40.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(if (a.mediaType.startsWith("image/")) Glyph.Image else Glyph.File, null, size = 16.dp, tint = Pocket.muted); Spacer(Modifier.width(6.dp))
                            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                            IconButton({ vm.removeAttachment(a.id) }, Modifier.size(32.dp), enabled = !locked) { GlyphIcon(Glyph.Close, "移除 ${a.name}", size = 14.dp, tint = Pocket.muted) }
                        }
                    }
                }
            }
            BasicTextField(vm.draft, vm::updateDraft, Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 10.dp).semantics { contentDescription = "输入框" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 8, enabled = enabled && !locked,
                cursorBrush = SolidColor(Pocket.accent),
                decorationBox = { inner -> Box { if (vm.draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Pocket.faint, maxLines = 2, overflow = TextOverflow.Ellipsis); inner() } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = { haptic(); pickerOwner = vm.composerKey; picker.launch(arrayOf("*/*")) }, enabled = enabled && !locked, shape = CircleShape,
                    color = Color.Transparent, border = BorderStroke(1.dp, Pocket.hairline), modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Plus, "添加文件或图片", size = 18.dp, tint = if (enabled) MaterialTheme.colorScheme.onSurface else Pocket.faint) }
                }
                Spacer(Modifier.width(6.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = footer)
                Spacer(Modifier.width(6.dp))
                val hasInput = vm.draft.isNotBlank() || vm.attachments.isNotEmpty()
                if (running && hasInput) {
                    SendCircle(MaterialTheme.colorScheme.surfaceContainerHigh, enabled = true, onClick = { haptic(); vm.cancelTurn() }) { GlyphIcon(Glyph.Stop, "停止生成", size = 16.dp, tint = MaterialTheme.colorScheme.onSurface) }
                    Spacer(Modifier.width(6.dp))
                }
                val stop = running && !hasInput
                SendCircle(when { stop -> MaterialTheme.colorScheme.onSurface; canSend -> Pocket.accent; else -> MaterialTheme.colorScheme.surfaceContainerHigh },
                    enabled = stop || canSend, onClick = { haptic(); if (stop) vm.cancelTurn() else onSend() }) {
                    when {
                        vm.submitting || vm.starting -> Spinner(size = 16.dp, color = Color.White)
                        stop -> GlyphIcon(Glyph.Stop, "停止生成", size = 16.dp, tint = MaterialTheme.colorScheme.surface)
                        else -> GlyphIcon(Glyph.ArrowUp, "发送", size = 18.dp, tint = if (canSend) Color.White else Pocket.faint)
                    }
                }
            }
        }
    }
}

@Composable private fun SendCircle(color: Color, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = color, modifier = Modifier.size(36.dp)) { Box(contentAlignment = Alignment.Center) { content() } }
}

/** Route pill: provider stays visible so equal model ids from different providers are distinguishable. */
@Composable internal fun ModelPill(vm: PocketViewModel, onClick: () -> Unit) {
    val choice = modelChoice(vm.catalog, vm.currentModel())
    val haptic = rememberHaptic()
    Surface(onClick = { haptic(); onClick() }, shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.dp, Pocket.hairline),
        modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = if (choice.selected) "模型：${choice.providerName} ${choice.modelName} ${choice.effortName}" else "选择模型" }) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!choice.selected) Text("选择模型", style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            else {
                Text(choice.providerName, style = MaterialTheme.typography.labelMedium, color = Pocket.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 84.dp))
                Text(" · ", style = MaterialTheme.typography.labelMedium, color = Pocket.faint)
                Text(choice.modelName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                if (choice.effortName.isNotBlank()) Text(" ${choice.effortName}", style = MaterialTheme.typography.labelMedium, color = Pocket.muted, maxLines = 1)
            }
            Spacer(Modifier.width(3.dp)); GlyphIcon(Glyph.ChevronDown, null, size = 14.dp, tint = Pocket.muted)
        }
    }
}

@Composable internal fun PermissionPill(vm: PocketViewModel, onClick: () -> Unit) {
    val value = vm.currentPermission()
    Pill(if (value.isBlank()) "默认权限" else permissionName(vm.permissionCatalog, value), leading = if (value.contains("full-access")) Glyph.Warning else Glyph.Shield, onClick = onClick)
}
