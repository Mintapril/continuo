@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.dsh.pocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.data.*
import org.json.JSONObject

/**
 * Pure presentation: paging never submits; only the final explicit button sends answers.
 * Styled like the reference app's ask-user card: serif question, outlined option rows,
 * free-text "something else" row, dark pill primary action.
 */
@Composable internal fun QuestionSheet(request: JSONObject, remainingMs: Long?, deferred: Boolean = false, dismiss: (() -> Unit)? = null, onEditing: (Boolean) -> Unit = {}, submit: (JSONObject) -> Unit) {
    val questions = request.objects("questions")
    val selected = remember { mutableStateMapOf<String, Set<String>>() }
    val custom = remember { mutableStateMapOf<String, String>() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val answered: (JSONObject) -> Boolean = { !selected[it.text("id")].isNullOrEmpty() || !custom[it.text("id")].isNullOrBlank() }
    val completed = questions.count(answered)
    val q = questions.getOrNull(page)
    val haptic = rememberHaptic()
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    PocketSheet({ dismiss?.invoke() }, dismissible = dismiss != null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ContinuoMark(size = 20.dp); Spacer(Modifier.width(8.dp))
                Text(if (deferred) "任务已继续 · 仍可补充回答" else "DSH 需要你的决定", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
                if (questions.size > 1) Text("${page + 1} / ${questions.size}", style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
                if (remainingMs != null) {
                    Spacer(Modifier.width(10.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.Clock, null, size = 13.dp, tint = Pocket.muted); Spacer(Modifier.width(4.dp))
                            Text("${(remainingMs.coerceAtLeast(0) + 999) / 1000}s", style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
                        }
                    }
                }
                dismiss?.let { Spacer(Modifier.width(6.dp)); CircleButton(Glyph.Close, "稍后回答", size = 32.dp, onClick = it) }
            }
            if (questions.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                questions.forEachIndexed { index, question ->
                    val color = when { index == page -> MaterialTheme.colorScheme.onSurface; answered(question) -> Pocket.accent; else -> Pocket.hairline }
                    Surface(Modifier.weight(1f).height(3.dp), shape = CircleShape, color = color) {}
                }
            }
            if (q != null) key(q.text("id")) {
                val id = q.text("id"); val multiple = q.optBoolean("multiSelect")
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (q.text("header").isNotBlank()) Text(q.text("header").uppercase(), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.accent)
                    Text(q.text("question"), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
                    if (q.text("detail").isNotBlank()) Text(q.text("detail"), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                    Spacer(Modifier.height(2.dp))
                    q.objects("options").forEachIndexed { index, option ->
                        val label = option.text("label"); val checked = label in selected[id].orEmpty()
                        Surface(onClick = { haptic(); selected[id] = if (multiple) selected[id].orEmpty().let { if (checked) it - label else it + label } else setOf(label); if (!multiple) custom[id] = "" },
                            modifier = Modifier.fillMaxWidth().semantics { this.selected = checked; role = if (multiple) Role.Checkbox else Role.RadioButton },
                            shape = RoundedCornerShape(14.dp), border = BorderStroke(if (checked) 1.5.dp else 1.dp, if (checked) MaterialTheme.colorScheme.onSurface else Pocket.hairline),
                            color = if (checked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(24.dp), shape = RoundedCornerShape(7.dp), color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (checked) GlyphIcon(Glyph.Check, null, size = 15.dp, tint = MaterialTheme.colorScheme.surface)
                                        else Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = Pocket.muted)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
                                    if (option.text("description").isNotBlank()) Text(option.text("description"), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
                                }
                            }
                        }
                    }
                    val text = custom[id].orEmpty()
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (text.isNotBlank()) MaterialTheme.colorScheme.onSurface else Pocket.hairline)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.Top) {
                            GlyphIcon(Glyph.Pencil, null, Modifier.padding(top = 2.dp), size = 18.dp, tint = Pocket.muted); Spacer(Modifier.width(12.dp))
                            BasicTextField(text, { custom[id] = it; if (!multiple && it.isNotBlank()) selected[id] = emptySet() },
                                Modifier.weight(1f).heightIn(min = 22.dp).onFocusChanged { onEditing(it.isFocused) }, maxLines = 6,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(Pocket.accent),
                                decorationBox = { inner -> Box { if (text.isEmpty()) Text(if (q.objects("options").isEmpty()) "输入你的回答" else "其他想法…", style = MaterialTheme.typography.bodyLarge, color = Pocket.faint); inner() } })
                        }
                    }
                    if (multiple) Text("可多选", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page > 0) CircleButton(Glyph.ArrowLeft, "上一题", size = 48.dp) { page-- }
                if (page < questions.lastIndex) PrimaryButton("下一题", Modifier.weight(1f), enabled = q?.let(answered) == true) { page++ }
                else PrimaryButton(if (deferred) "提交补充回答" else "提交", Modifier.weight(1f), enabled = questions.isNotEmpty() && completed == questions.size, icon = Glyph.ArrowUp) {
                    submit(questionAnswers(questions, selected, custom))
                }
            }
        }
    }
}
