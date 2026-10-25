@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.dsh.pocket.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.Companion
import dev.dsh.pocket.data.Identity

/**
 * The diary: one entry a day, written each night (23:00) by the plugin from what it did that day, newest first. A long
 * press writes a remark on that day (批一句), sent to the owner's conversation with it.
 */
@Composable internal fun DiaryPane(vm: PocketViewModel, identity: Identity) {
    var remark by remember { mutableStateOf<Companion.DiaryEntry?>(null) }
    LaunchedEffect(vm.connected) { if (vm.connected) vm.diaryRefresh() }
    val entries = vm.diary
    // The empty state stays out of the list (see ChatPane: an animated avatar faded out as a list item stays behind).
    if (entries.isEmpty()) Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 96.dp)
        Spacer(Modifier.height(14.dp))
        Text(when {
            !vm.connected -> "连上本机 DSH 后显示日记"
            vm.diaryError == null -> "正在翻日记…"
            vm.diaryError!!.isNotBlank() -> if ("404" in vm.diaryError!!) "手机上的 Cowork 插件还是旧版，更新后这里会有它的日记" else "读不到日记：${vm.diaryError}"
            else -> "${identity.shown}每天晚上 11 点写一篇日记，\n写这一天做了什么。今晚是第一篇。"
        }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
        items(entries, key = { it.key }) { entry ->
            DiaryDay(entry, Modifier.animateItem()) { remark = entry }
        }
        if (vm.diaryMore) item {
            TextButton({ vm.diaryOlder() }, Modifier.fillMaxWidth()) { Text("更早的日记", color = Pocket.accent) }
        }
    }
    remark?.let { entry ->
        InputDialog("批一句", "写给${identity.shown}，它会在你们的对话里回你", "", { remark = null }) { text -> vm.annotate(entry, text); remark = null }
    }
}

@Composable private fun DiaryDay(entry: Companion.DiaryEntry, modifier: Modifier, remark: () -> Unit) {
    val haptic = rememberHaptic()
    val heading = remember(entry.date) { entry.day?.let { Companion.dayHeading(it) } ?: entry.date }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { haptic(); remark() }, onLongClickLabel = "批一句").padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall, fontFamily = Fonts.serif, fontWeight = FontWeight.Medium, color = Pocket.muted)
            entry.text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
