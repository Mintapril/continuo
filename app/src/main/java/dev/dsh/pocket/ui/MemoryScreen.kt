package dev.dsh.pocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.data.MemoryCenter
import dev.dsh.pocket.data.stringArray
import dev.dsh.pocket.data.text
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory (dsh-mneme) settings.
 *
 * The plugin persists feature flags and the standard/light preset to its own kv store, and DSH merges
 * them at boot — so every flag write here is labelled "next start" instead of pretending to be live.
 * Memory rows themselves are live: list/search/edit/archive/delete all hit the plugin immediately.
 */
@Composable internal fun MemorySettings(vm: PocketViewModel, back: () -> Unit) {
    val mem = vm.memory
    LaunchedEffect(Unit) { vm.memoryRefresh() }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<JSONObject?>(null) }
    var confirmReclaim by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var budgetDialog by remember { mutableStateOf(false) }
    var documentDialog by remember { mutableStateOf(false) }

    SettingsPage("记忆", back) {
        if (mem.available == false) {
            SectionLabel("状态")
            InsetGroup {
                GroupRow("dsh-mneme 未安装", icon = Glyph.Brain, chevron = false,
                    subtitle = "本机 DSH 没有注册记忆插件路由。插件装好后重新进入本页即可。")
            }
            Footnote("检查方式：DSH 的 /api/dsh-mneme/info 返回 404。安装插件需要在手机上部署 dsh-mneme 并把它加入 profile。")
            return@SettingsPage
        }

        SectionLabel("概览")
        InsetGroup {
            GroupRow("记忆条数", icon = Glyph.Brain, chevron = false,
                subtitle = if (mem.archivedTotal > 0) "另有 ${mem.archivedTotal} 条已归档" else null,
                trailing = {
                    if (mem.busy) Spinner(size = 14.dp) else Text("${mem.total}", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                })
            GroupDivider()
            GroupRow("检索方式", icon = Glyph.Search, chevron = false, trailing = {
                Text(mem.semanticDetail.ifBlank { if (mem.available == null) "读取中" else "未知" },
                    style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
            GroupDivider()
            GroupRow("上次整理", icon = Glyph.Clock, chevron = false, trailing = {
                Text(stampText(mem.dream.opt("lastRun")), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            })
            GroupDivider()
            GroupRow("版本", icon = Glyph.Info, value = mem.version.ifBlank { "—" }, chevron = false)
        }
        Footnote("记忆保存在本机 DSH 的 profile 里，不经过任何外部服务。语义检索需要额外的向量模型；未配置时只用关键词检索。")

        SectionLabel("模式")
        InsetGroup {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("standard" to "标准", "light" to "轻量").forEach { (value, label) ->
                    Pill(label, Modifier.weight(1f), selected = mem.mode == value, trailing = null) { vm.memoryMode(value) }
                }
            }
        }
        Footnote("「轻量」关闭重型后台整理，适合低配设备或省电场景。切换后需要 DSH 下次启动才生效。")

        SectionLabel("自动化")
        InsetGroup {
            ToggleRow("自动注入记忆", "在当前会话上下文里带上相关记忆", mem.features.optBoolean("autoInject")) { vm.memoryFlag("autoInject", it) }
            GroupDivider()
            ToggleRow("自动总结成记忆", "回合结束后提取值得记住的内容", mem.features.optBoolean("autoSummarize")) { vm.memoryFlag("autoSummarize", it) }
            GroupDivider()
            ToggleRow("自动整理", "定期合并重复、清理过时记忆", mem.features.optBoolean("autoDream")) { vm.memoryFlag("autoDream", it) }
            GroupDivider()
            ToggleRow("热记忆", "最近用过的记忆优先召回", mem.features.optBoolean("hotMemoryEnabled")) { vm.memoryFlag("hotMemoryEnabled", it) }
            GroupDivider()
            ToggleRow("实体抽取", "用模型抽取人物、项目等实体（额外调用模型）", mem.features.optBoolean("entityExtractionEnabled")) { vm.memoryFlag("entityExtractionEnabled", it) }
            GroupDivider()
            ToggleRow("文档记忆", "长文档以指针形式入记忆、按需读取全文", mem.features.optBoolean("documentMemoryEnabled")) { vm.memoryFlag("documentMemoryEnabled", it) }
            GroupDivider()
            ToggleRow("质量过滤", "拒收过短或重复的候选记忆", mem.features.optBoolean("memoryQualityFilter.enabled", true)) {
                vm.memoryFlag("memoryQualityFilter.enabled", it)
            }
            GroupDivider()
            ToggleRow("关键词检索（BM25）", "不依赖向量模型的基础检索", mem.features.optBoolean("bm25SearchEnabled")) { vm.memoryFlag("bm25SearchEnabled", it) }
            GroupDivider()
            ToggleRow("结果重排", "用重排模型提高召回准确率（额外开销）", mem.features.optBoolean("rerankEnabled")) { vm.memoryFlag("rerankEnabled", it) }
        }
        Footnote("这些开关会立即写入插件设置，但运行中的 DSH 进程仍按启动时的配置工作；重启 DSH 后生效。")

        SectionLabel("注入预算")
        InsetGroup {
            GroupRow("单条注入上限", icon = Glyph.File, subtitle = "每条记忆进入上下文的字符上限",
                value = "${mem.features.optInt("injectContentMaxChars", 300)} 字", onClick = { budgetDialog = true })
            GroupDivider()
            GroupRow("文档注入条数", icon = Glyph.Layers, subtitle = "每次最多注入几篇文档指针",
                value = "${mem.features.optInt("documentInjectBudget", 2)} 条", onClick = { documentDialog = true })
        }

        SectionLabel("记忆库")
        OutlinedTextField(query, {
            query = it
            if (it.isBlank()) vm.memoryLoad()
        }, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true, shape = RoundedCornerShape(14.dp),
            placeholder = { Text("搜索记忆", style = MaterialTheme.typography.bodyMedium, color = Pocket.faint) },
            trailingIcon = {
                if (query.isNotBlank()) TextButton({ vm.memorySearch(query) }) { Text("搜索", style = MaterialTheme.typography.labelMedium, color = Pocket.accent) }
            })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MemoryCenter.TYPES.forEach { type ->
                Pill(MemoryCenter.label(type), selected = mem.filter == type, trailing = null) {
                    query = ""
                    vm.memoryFilter(type)
                }
            }
        }
        if (mem.entries.isEmpty()) {
            InsetGroup {
                GroupRow(if (query.isBlank()) "还没有记忆" else "没有匹配的记忆", icon = Glyph.Brain, chevron = false,
                    subtitle = if (query.isBlank()) "任务跑过几轮后，值得记住的内容会自动积累到这里" else "换一个关键词试试")
            }
        } else {
            mem.entries.forEach { entry ->
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.takeIf { !Pocket.dark } ?: MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, Pocket.hairline)) {
                    Column(Modifier.fillMaxWidth().clickable { detail = entry }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.text("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            ImportanceDots(entry.optInt("importance", 3))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOf(MemoryCenter.label(entry.text("type")), sourceLabel(entry.text("source"))).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                        Text(entry.text("content"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                            color = Pocket.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (mem.hasMore) {
                GroupRow("加载更多", icon = Glyph.ArrowDown, chevron = false, enabled = !mem.loadingMore,
                    trailing = { if (mem.loadingMore) Spinner(size = 14.dp) }, onClick = { vm.memoryMore() })
            }
        }
        InsetGroup {
            GroupRow("新建记忆", icon = Glyph.Plus, subtitle = "手动写一条，模型之后可以召回", onClick = { creating = true })
        }

        SectionLabel("维护")
        InsetGroup {
            GroupRow("注入预览", icon = Glyph.Eye, subtitle = "看这次实际会注入哪些记忆、占多少字", onClick = { vm.memoryPreviewInjection() })
            GroupDivider()
            GroupRow("存储回收", icon = Glyph.Trash, subtitle = "清理过期快照与已归档向量", onClick = { vm.memoryPreviewReclaim() })
        }
        Footnote("记忆是你自己的数据：删除与回收都不可撤销，执行前会给出数量。")
    }

    detail?.let { entry ->
        MemoryDetailSheet(entry,
            archive = { archived -> vm.memoryArchive(entry.text("id"), archived); detail = null },
            edit = { editing = it; detail = null },
            remove = { confirmDelete = it; detail = null },
            importance = { vm.memoryImportance(entry.text("id"), it) },
            dismiss = { detail = null })
    }
    editing?.let { entry ->
        MemoryEditSheet(entry, dismiss = { editing = null }) { title, content ->
            vm.memoryUpdate(entry.text("id"), dev.dsh.pocket.data.obj("title" to title, "content" to content))
            editing = null
        }
    }
    if (creating) {
        MemoryCreateSheet(dismiss = { creating = false }) { type, title, content, importance ->
            vm.memoryCreate(type, title, content, importance)
            creating = false
        }
    }
    confirmDelete?.let { entry ->
        ConfirmDialog("删除这条记忆", "「${entry.text("title")}」将被永久删除，不能恢复。", "删除", destructive = true,
            dismiss = { confirmDelete = null }) {
            vm.memoryDelete(entry.text("id")); confirmDelete = null
        }
    }
    if (budgetDialog) {
        InputDialog("单条注入上限", "每条记忆进入上下文的字符数，40–4000", "${mem.features.optInt("injectContentMaxChars", 300)}", { budgetDialog = false }) { text ->
            text.trim().toIntOrNull()?.takeIf { it in 40..4000 }?.let { vm.memoryFlag("injectContentMaxChars", it); budgetDialog = false }
                ?: vm.showError("请输入 40 到 4000 之间的整数")
        }
    }
    if (documentDialog) {
        InputDialog("文档注入条数", "每次最多注入几篇文档指针，0–12", "${mem.features.optInt("documentInjectBudget", 2)}", { documentDialog = false }) { text ->
            text.trim().toIntOrNull()?.takeIf { it in 0..12 }?.let { vm.memoryFlag("documentInjectBudget", it); documentDialog = false }
                ?: vm.showError("请输入 0 到 12 之间的整数")
        }
    }
    vm.memoryInjection?.let { snapshot ->
        PocketSheet({ vm.clearMemorySheets() }) {
            SheetHeader("注入预览", "上一帧系统提示词里的记忆部分")
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(prettyInjection(snapshot), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    vm.memoryReclaim?.let { report ->
        PocketSheet({ vm.clearMemorySheets() }) {
            SheetHeader("存储回收", if (report.optBoolean("dryRun")) "预估" else "已完成")
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                val planned = report.optJSONObject("planned")
                val cleared = report.optJSONObject("cleared")
                val size = report.optJSONObject("size")
                val rows = listOf(
                    "过期快照" to (planned?.optInt("dream_run_inputs") ?: 0),
                    "已归档向量" to (planned?.optInt("archived_embeddings") ?: 0),
                    "蒸馏原文" to (planned?.optInt("distill_records") ?: 0))
                val total = rows.sumOf { it.second }
                rows.forEach { (label, count) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("$count 行", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("当前数据库", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(bytes(size?.optLong("before_bytes") ?: 0), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("可整理空页", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(bytes(size?.optLong("freelist_bytes") ?: 0), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                }
                if (!report.optBoolean("dryRun")) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("实际清除", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${listOf("dream_run_inputs", "archived_embeddings", "distill_records").sumOf { cleared?.optInt(it) ?: 0 }} 行",
                            style = MaterialTheme.typography.bodyMedium, color = Pocket.success)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (report.optBoolean("dryRun")) {
                    Text("回收只清理过期快照和已归档向量，不会删除记忆本身。", style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                    Spacer(Modifier.height(8.dp))
                    TextButton({ confirmReclaim = true }, enabled = total > 0) {
                        Text(if (total > 0) "执行回收（$total 行）" else "没有可回收的内容",
                            style = MaterialTheme.typography.labelLarge, color = if (total > 0) Pocket.accent else Pocket.faint)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmReclaim) {
        ConfirmDialog("执行存储回收", "将永久清除过期快照、已归档向量和蒸馏原文，记忆本身不受影响。", "回收", destructive = true,
            dismiss = { confirmReclaim = false }) {
            vm.memoryReclaimNow(); confirmReclaim = false
        }
    }
}

@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    GroupRow(title, subtitle = subtitle, chevron = false, trailing = {
        androidx.compose.material3.Switch(checked, onChange, colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = Pocket.accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary, uncheckedBorderColor = Pocket.hairline))
    })

@Composable private fun ImportanceDots(value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            Surface(Modifier.padding(start = 2.dp).size(5.dp), shape = RoundedCornerShape(3.dp),
                color = if (index < value) Pocket.accent else Pocket.hairline) {}
        }
    }
}

@Composable private fun MemoryDetailSheet(entry: JSONObject, archive: (Boolean) -> Unit, edit: (JSONObject) -> Unit,
                                          remove: (JSONObject) -> Unit, importance: (Int) -> Unit, dismiss: () -> Unit) {
    val archived = entry.optBoolean("archived")
    var rating by remember(entry.text("id")) { mutableStateOf(entry.optInt("importance", 3)) }
    PocketSheet(dismiss) {
        SheetHeader(entry.text("title"), "${MemoryCenter.label(entry.text("type"))} · ${sourceLabel(entry.text("source"))}")
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(entry.text("content"), style = MaterialTheme.typography.bodyMedium)
            entry.stringArray("tags").takeIf { it.isNotEmpty() }?.let { tags ->
                Text(tags.joinToString("  ") { "#$it" }, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.accent)
            }
            Text("创建 ${stampText(entry.opt("created_at"))} · 更新 ${stampText(entry.opt("updated_at"))}",
                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("重要性", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                repeat(5) { index ->
                    Surface({ rating = index + 1; importance(index + 1) }, Modifier.padding(start = 4.dp).size(24.dp),
                        shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(if (index < rating) 12.dp else 8.dp), shape = RoundedCornerShape(6.dp),
                                color = if (index < rating) Pocket.accent else Pocket.hairline) {}
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ edit(entry) }) { Text("编辑", color = Pocket.accent) }
            TextButton({ archive(!archived) }) { Text(if (archived) "恢复" else "归档", color = Pocket.accent) }
            Spacer(Modifier.weight(1f))
            TextButton({ remove(entry) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable private fun MemoryEditSheet(entry: JSONObject, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var title by remember { mutableStateOf(entry.text("title")) }
    var content by remember { mutableStateOf(entry.text("content")) }
    PocketSheet(dismiss) {
        SheetHeader("编辑记忆")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("标题") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 300.dp), label = { Text("内容") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss) { Text("取消", color = Pocket.muted) }
                TextButton({ if (title.isNotBlank() && content.isNotBlank()) save(title.trim(), content.trim()) },
                    enabled = title.isNotBlank() && content.isNotBlank()) { Text("保存", color = Pocket.accent) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun MemoryCreateSheet(dismiss: () -> Unit, save: (String, String, String, Int) -> Unit) {
    var type by remember { mutableStateOf("project") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(3) }
    PocketSheet(dismiss) {
        SheetHeader("新建记忆")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("preference", "project", "decision", "pitfall", "constraint", "history").forEach { value ->
                    Pill(MemoryCenter.label(value), selected = type == value, trailing = null) { type = value }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("标题") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), label = { Text("内容") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("重要性", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                repeat(5) { index ->
                    Surface({ importance = index + 1 }, Modifier.padding(start = 4.dp).size(24.dp), shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(if (index < importance) 12.dp else 8.dp), shape = RoundedCornerShape(6.dp),
                                color = if (index < importance) Pocket.accent else Pocket.hairline) {}
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(dismiss) { Text("取消", color = Pocket.muted) }
                TextButton({ if (title.isNotBlank() && content.isNotBlank()) save(type, title.trim(), content.trim(), importance) },
                    enabled = title.isNotBlank() && content.isNotBlank()) { Text("保存", color = Pocket.accent) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Provenance strings are plugin-internal (`panel`, `session`, `dream`); name them in the user's terms. */
private fun sourceLabel(value: String) = when (value) {
    "panel" -> "手写"
    "session" -> "会话"
    "dream" -> "自动整理"
    "import" -> "导入"
    "" -> "未知来源"
    else -> value
}

/** Timestamps arrive as ISO strings; show local wall-clock without pretending to full precision. */
private fun stampText(value: Any?): String {
    val raw = when (value) {
        is String -> value
        is JSONObject -> value.text("at").ifBlank { value.text("started_at") }
        else -> ""
    }
    if (raw.isBlank()) return "从未"
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(raw.take(19))
    }.getOrNull() ?: return raw.take(10)
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(parsed.time))
}

private fun bytes(value: Long): String = when {
    value >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", value / 1048576.0)
    value >= 1 shl 10 -> String.format(Locale.US, "%.0f KB", value / 1024.0)
    else -> "$value B"
}

/** Injection snapshots are structured; flatten the parts a user cares about instead of dumping JSON. */
private fun prettyInjection(snapshot: JSONObject): String {
    if (snapshot.length() == 0) return "还没有渲染过系统提示词：开一个新会话后再看，或自动注入当前是关闭的。"
    val lines = mutableListOf<String>()
    fun walk(value: Any?, depth: Int = 0, label: String = "") {
        when (value) {
            is JSONObject -> {
                if (label.isNotEmpty()) lines += "▍$label"
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (child is JSONObject || child is org.json.JSONArray) walk(child, depth + 1, key)
                    else lines += "$key：${child ?: "—"}"
                }
            }
            is org.json.JSONArray -> {
                lines += "▍$label（${value.length()} 项）"
                for (index in 0 until minOf(value.length(), 20)) {
                    val child = value.opt(index)
                    if (child is JSONObject) {
                        lines += "· ${child.text("title").ifBlank { child.text("id") }}"
                        val text = child.text("content").ifBlank { child.text("excerpt") }
                        if (text.isNotBlank()) lines += "  ${text.take(160).replace('\n', ' ')}"
                    } else lines += "· $child"
                }
                if (value.length() > 20) lines += "… 另有 ${value.length() - 20} 项"
            }
        }
    }
    walk(snapshot)
    return lines.joinToString("\n").ifBlank { snapshot.toString(2) }
}