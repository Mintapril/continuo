@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.*
import org.json.JSONObject

/** Bottom sheet with the reference app's flat surface, 28dp top radius and slim handle. */
@Composable internal fun PocketSheet(dismiss: () -> Unit, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || dismissible })
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) dismiss() }, sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(Pocket.hairline, CircleShape)) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible),
    ) { Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), content = content) }
}

/** One selectable row inside a sheet: 56dp, optional leading glyph, trailing check. */
@Composable internal fun SheetRow(title: String, subtitle: String? = null, icon: ImageVector? = null, checked: Boolean = false, enabled: Boolean = true,
                                  tint: Color = MaterialTheme.colorScheme.onSurface, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { haptic(); onClick() }.semantics { selected = checked }
        .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { GlyphIcon(it, null, size = 20.dp, tint = if (enabled) tint else tint.copy(alpha = .38f)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) tint else tint.copy(alpha = .38f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
        if (checked) { Spacer(Modifier.width(10.dp)); GlyphIcon(Glyph.Check, "已选择", size = 20.dp, tint = Pocket.accent) }
    }
}

@Composable private fun SheetSearch(query: String, onChange: (String) -> Unit, placeholder: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 14.dp).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Search, null, size = 18.dp, tint = Pocket.muted); Spacer(Modifier.width(8.dp))
            BasicTextField(query, onChange, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(Pocket.accent), decorationBox = { inner -> Box { if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Pocket.faint); inner() } })
            if (query.isNotEmpty()) GlyphIcon(Glyph.Close, "清除", Modifier.noRippleClick { onChange("") }, size = 16.dp, tint = Pocket.muted)
        }
    }
}

/** Provider-grouped route picker. Provider stays visible because model ids can repeat across providers. */
@Composable internal fun ModelSheet(catalog: JSONObject, current: JSONObject?, dismiss: () -> Unit, select: (String, String, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(current?.text("provider").orEmpty()) }
    var modelId by rememberSaveable { mutableStateOf(current?.text("model").orEmpty()) }
    var effort by rememberSaveable { mutableStateOf(current?.text("reasoningEffort")?.takeIf { it.isNotBlank() }) }
    val groups = catalog.objects("groups")
    val picked = groups.firstOrNull { it.text("id") == provider }?.objects("models")?.firstOrNull { it.text("id") == modelId }
    val reasoning = picked?.optJSONObject("reasoning")
    val efforts = reasoning?.objects("efforts").orEmpty()
    val total = groups.sumOf { it.objects("models").size }
    val valid = picked != null && (effort == null || efforts.any { it.text("id") == effort })
    PocketSheet(dismiss) {
        SheetHeader("模型", leading = { CircleButton(Glyph.Close, "关闭", size = 36.dp, onClick = dismiss) },
            trailing = { TextButton({ select(provider, modelId, effort) }, enabled = valid) { Text("完成", style = MaterialTheme.typography.titleSmall, color = if (valid) Pocket.accent else Pocket.faint) } })
        if (total > 6) SheetSearch(query, { query = it }, "搜索模型或提供方")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            var count = 0
            groups.forEach { group ->
                val matches = group.objects("models").filter { (group.text("id") + " " + group.text("name") + " " + it.text("name") + " " + it.text("id")).contains(query.trim(), true) }
                if (matches.isEmpty()) return@forEach
                count += matches.size
                item(key = "p:${group.text("id")}") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.text("name").ifBlank { group.text("id") }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
                        if (group.text("name").isNotBlank() && group.text("name") != group.text("id")) Text(group.text("id"), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                    }
                }
                items(matches, key = { "m:${group.text("id")}:${it.text("id")}" }) { model ->
                    val checked = provider == group.text("id") && modelId == model.text("id")
                    val name = model.text("name").ifBlank { model.text("id") }
                    val sub = listOfNotNull(model.text("id").takeIf { it != name }, if (model.optJSONObject("reasoning") != null) "可调思考强度" else null).joinToString(" · ")
                    SheetRow(name, sub, checked = checked) {
                        if (!checked) { provider = group.text("id"); modelId = model.text("id"); effort = model.optJSONObject("reasoning")?.text("defaultEffort")?.takeIf { it.isNotBlank() } }
                    }
                }
            }
            if (count == 0) item { Text("没有匹配的模型", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted) }
            items(catalog.objects("failures")) { failure ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    GlyphIcon(Glyph.Warning, null, size = 16.dp, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp))
                    Text("${failure.text("id")}：${failure.text("message")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (efforts.isNotEmpty()) {
            HorizontalDivider(color = Pocket.hairline)
            Text("思考强度", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val default = reasoning?.text("defaultEffort").orEmpty()
                Pill("默认", selected = effort == null, trailing = null) { effort = null }
                efforts.forEach { option ->
                    val label = option.text("name").ifBlank { option.text("id") } + if (option.text("id") == default) " ·默认" else ""
                    Pill(label, selected = effort == option.text("id"), trailing = null) { effort = option.text("id") }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

internal fun permissionName(catalog: JSONObject, value: String): String = permissionLabel(value)

/** Centered choices, not a bottom sheet. The list scrolls inside a bounded dialog. */
@Composable internal fun ChoiceDialog(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(vertical = 8.dp), content = content)
        }
    }
}

@Composable internal fun PermissionSheet(vm: PocketViewModel, dismiss: () -> Unit) {
    var dangerous by remember { mutableStateOf<JSONObject?>(null) }
    val current = vm.currentPermission()
    ChoiceDialog(dismiss) {
        SheetHeader("工具权限", if (vm.selected == null) "用于即将开始的任务" else "仅更改当前会话")
        vm.permissionCatalog.objects("options").forEach { option ->
            val value = option.text("value")
            SheetRow(permissionLabel(value), permissionDescription(value), if (value.contains("full-access")) Glyph.Warning else Glyph.Shield, checked = value == current) {
                if (value.contains("full-access") && value != current) dangerous = option else { vm.permission(value); dismiss() }
            }
        }
        if (vm.permissionCatalog.objects("options").isEmpty()) Text("连接后可选择权限预设", Modifier.padding(20.dp), color = Pocket.muted)
    }
    dangerous?.let { option ->
        ConfirmDialog("允许完全访问？", "这会放宽模型工具的限制。chroot 不是安全边界，模型可能访问共享存储与 root 环境。仅在明确理解风险时继续。", "确认更改", destructive = true,
            dismiss = { dangerous = null }) { vm.permission(option.text("value")); dangerous = null; dismiss() }
    }
}

@Composable internal fun WorkspaceSheet(vm: PocketViewModel, dismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (!vm.workspaceEditable) {
        ChoiceDialog(dismiss) {
            SheetHeader("工作区已锁定", "开始会话后不能中途切换工作区；请新建任务选择其他目录。")
            Text(vm.selectedSummary?.text("cwd").orEmpty(), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    ChoiceDialog(dismiss) {
        SheetHeader("工作区", "首条消息发送后锁定工作目录")
        SheetRow("/workspace", "默认工作目录", Glyph.Folder, checked = vm.homeWorkspace == null || vm.workspaces.none { it.text("workspaceId") == vm.homeWorkspace }) { vm.changeWorkspace(null); dismiss() }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(vm.workspaces, key = { it.text("workspaceId") }) { w ->
                SheetRow(w.text("title").ifBlank { workspaceLabel(w.text("path")) }, w.text("path"), Glyph.Folder, checked = vm.homeWorkspace == w.text("workspaceId")) { vm.changeWorkspace(w.text("workspaceId")); dismiss() }
            }
        }
        HorizontalDivider(color = Pocket.hairline, modifier = Modifier.padding(vertical = 4.dp))
        SheetRow("添加工作区", "Linux 中的绝对路径", Glyph.Plus, enabled = vm.connected, tint = MaterialTheme.colorScheme.onSurface) { adding = true }
    }
    if (adding) InputDialog("添加工作区", "例如 /workspace/phone/Documents", "/workspace", { adding = false }) { vm.createWorkspace(it); adding = false }
}

@Composable internal fun JobsSheet(vm: PocketViewModel, dismiss: () -> Unit) {
    var viewing by remember { mutableStateOf<JSONObject?>(null) }
    var stop by remember { mutableStateOf<String?>(null) }
    PocketSheet({ vm.closeJobOutput(); dismiss() }) {
        val job = viewing
        if (job == null) {
            SheetHeader("后台任务", "${vm.jobs.size} 个任务")
            if (vm.jobs.isEmpty()) Text("此会话没有后台任务", Modifier.fillMaxWidth().padding(28.dp), color = Pocket.muted, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(vm.jobs, key = { it.text("id") }) { item ->
                    val running = item.text("status") == "running"
                    SheetRow(item.text("label").ifBlank { item.text("id") }, listOf(item.text("kind"), jobStatus(item.text("status")), item.text("detail")).filter { it.isNotBlank() }.joinToString(" · "), Glyph.Terminal,
                        trailing = { if (running) StatusDot(Pocket.accent, pulse = true) }) { viewing = item; vm.followJob(item.text("id")) }
                }
            }
        } else {
            SheetHeader(job.text("label").ifBlank { "输出" }, jobStatus(job.text("status")),
                leading = { CircleButton(Glyph.ArrowLeft, "返回", size = 36.dp) { vm.closeJobOutput(); viewing = null } },
                trailing = { if (vm.jobs.firstOrNull { it.text("id") == job.text("id") }?.text("status") == "running") TextButton({ stop = job.text("id") }) { Text("停止", color = MaterialTheme.colorScheme.error) } })
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 120.dp, max = 460.dp), shape = RoundedCornerShape(12.dp), color = Pocket.codeBackground) {
                SelectionContainer { Text(vm.jobOutput.ifBlank { "等待输出…" }, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                    fontFamily = Fonts.mono, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    stop?.let { id -> ConfirmDialog("停止此后台任务？", "任务进程会被终止，已产生的输出会保留。", "停止", destructive = true, dismiss = { stop = null }) { vm.killJob(id); stop = null } }
}
private fun jobStatus(value: String) = when (value) { "running" -> "运行中"; "completed", "exited" -> "已结束"; "failed" -> "失败"; "killed" -> "已停止"; else -> value }

/** Pending approval or question from any session. Never dismissible: it must be answered. */
@Composable internal fun PendingRequest(vm: PocketViewModel, event: JSONObject) {
    val request = event.optJSONObject("request") ?: JSONObject()
    if (event.text("event") == "approval/request") {
        val session = vm.sessions.firstOrNull { it.text("sessionId") == event.text("agentId") }
        PocketSheet({}, dismissible = false) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = Pocket.accent.copy(alpha = .14f), modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Shield, null, size = 22.dp, tint = Pocket.accent) } }
                Text("允许 DSH 使用 ${request.text("toolName").ifBlank { "此工具" }}？", style = MaterialTheme.typography.headlineSmall)
                val reason = request.optJSONObject("displayReason")?.text("zh-CN")?.ifBlank { null } ?: request.text("reason")
                if (reason.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = Pocket.codeBackground) {
                    SelectionContainer { Text(reason, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                Text("${sessionTitle(session)} · 仅本次有效，不会修改长期权限", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                Spacer(Modifier.height(2.dp))
                PrimaryButton("允许一次", Modifier.fillMaxWidth()) { vm.answer(event, "allowed-once") }
                SecondaryButton("拒绝", Modifier.fillMaxWidth()) { vm.answer(event, "rejected") }
            }
        }
    } else QuestionSheet(request, vm.questionRemaining[event.text("eventId")], onEditing = { vm.setQuestionEditing(event.text("eventId"), it) }) { vm.answer(event, it) }
}

@Composable internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Button({ haptic(); onClick() }, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
        icon?.let { GlyphIcon(it, null, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}
@Composable internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape, border = BorderStroke(1.dp, Pocket.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        icon?.let { GlyphIcon(it, null, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable internal fun ConfirmDialog(title: String, text: String, confirm: String, destructive: Boolean = false, dismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, style = MaterialTheme.typography.titleSmall, color = if (destructive) MaterialTheme.colorScheme.error else Pocket.accent) } },
        dismissButton = { TextButton(dismiss) { Text("取消", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface) } })
}

@Composable internal fun InputDialog(title: String, label: String, initial: String, dismiss: () -> Unit, secret: Boolean = false, allowBlank: Boolean = false, submit: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, Pocket.hairline)) {
                    BasicTextField(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 14.dp, vertical = 13.dp), singleLine = true,
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(Pocket.accent))
                }
            }
        },
        confirmButton = { TextButton({ submit(value) }, enabled = allowBlank || value.isNotBlank()) { Text("确定", style = MaterialTheme.typography.titleSmall, color = if (allowBlank || value.isNotBlank()) Pocket.accent else Pocket.faint) } },
        dismissButton = { TextButton(dismiss) { Text("取消", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface) } })
}

internal fun sessionTitle(row: JSONObject?): String = row?.optJSONObject("projections")?.optJSONObject("values")?.text("title")?.takeIf { it.isNotBlank() } ?: "新任务"
