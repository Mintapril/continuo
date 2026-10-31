package dev.dsh.pocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.Companion
import dev.dsh.pocket.data.Identity
import kotlinx.coroutines.delay

/**
 * 事件: what it did on its own (patrols, notifications that woke it, due notes), in the owner's conversation where
 * everything lives now, or earlier in the standing app sessions; newest first by day, from the plugin's activity (never
 * what a notification said). A tap opens that session with its thinking and tool calls, as 设置 → 开发者 does.
 */
@Composable internal fun EventsPane(vm: PocketViewModel, identity: Identity) {
    val status by CoworkConnection.status.collectAsState()
    val task = status.optString("taskId")
    // A turn's line is written as DSH ends it, a moment after the screen is free: read again a little later.
    LaunchedEffect(vm.connected, task) { if (vm.connected) { vm.eventsRefresh(); if (task.isBlank()) { delay(2_500); vm.eventsRefresh() } } }
    val label = rememberAppLabel()
    val entries = vm.events
    // The empty state stays out of the list (see ChatPane: an animated avatar faded out as a list item stays behind).
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(identity.avatar, StatusCard.Mood.IDLE, identity.shown, size = 96.dp)
            Spacer(Modifier.height(14.dp))
            Text(when {
                !vm.connected -> "连上本机 DSH 后显示事件"
                vm.eventsError == null -> "正在翻事件…"
                vm.eventsError!!.isNotBlank() -> if ("404" in vm.eventsError!!) "手机上的 Cowork 插件还是旧版，更新后这里会有${identity.shown}自己做的事" else "读不到事件：${vm.eventsError}"
                else -> "还没有事件。\n${identity.shown}自己逛、被通知叫醒、到点看便条时做的事会记在这里。"
            }, style = MaterialTheme.typography.bodyMedium, color = Pocket.muted, textAlign = TextAlign.Center)
        }
        return
    }
    val days = remember(entries) { entries.groupBy { Companion.dayHeading(it.at) }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { (heading, list) ->
            item(key = "day:$heading") {
                Text(heading, Modifier.animateItem().padding(start = 4.dp, top = 10.dp), style = MaterialTheme.typography.titleSmall,
                    fontFamily = Fonts.serif, fontWeight = FontWeight.Medium, color = Pocket.muted)
            }
            items(list, key = { it.key }) { event -> EventCard(event, label, Modifier.animateItem()) { vm.openSession(event.sessionId) } }
        }
        if (vm.eventsMore) item(key = "older") {
            TextButton({ vm.eventsOlder() }, Modifier.fillMaxWidth()) { Text("更早的事件", color = Pocket.accent) }
        }
    }
}

@Composable private fun EventCard(event: Companion.Event, label: (String) -> String, modifier: Modifier, open: () -> Unit) {
    Surface(open, modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Companion.clock(event.at), style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
                Spacer(Modifier.width(8.dp))
                Text(Companion.eventTitle(event, label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!event.finished) Text("没做完", style = MaterialTheme.typography.labelSmall, color = Pocket.warning)
            }
            if (event.text.isNotEmpty()) Text(event.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            // The session's own app is in the title already.
            val opened = event.apps.filter { it != event.app }.map(label).distinct()
            if (opened.isNotEmpty()) Text("打开了 " + opened.joinToString("、"), style = MaterialTheme.typography.labelMedium, color = Pocket.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            event.notified.forEach { Text("告诉你：$it", style = MaterialTheme.typography.bodySmall, color = Pocket.accent, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
