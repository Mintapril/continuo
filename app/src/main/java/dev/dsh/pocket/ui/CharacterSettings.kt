package dev.dsh.pocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.data.IdentityStore
import dev.dsh.pocket.data.Temperament
import dev.dsh.pocket.data.Temperament.Contact
import dev.dsh.pocket.notify.NotifyCenter
import java.time.ZoneId

// 它的性格、精力和心境、找你 (docs/personality-design.md): kept and computed by the Cowork plugin, set here.

/** The 它 page's part: 性格, how it is doing, and how it may reach you. */
@Composable internal fun CharacterRows(vm: PocketViewModel, name: String, open: (Screen) -> Unit) {
    val profile = vm.character
    val outreach = vm.outreach
    var levelDialog by remember { mutableStateOf(false) }
    var quietDialog by remember { mutableStateOf(false) }
    var dndDialog by remember { mutableStateOf(false) }
    var preferencesDialog by remember { mutableStateOf(false) }
    SectionLabel("${name}的性格")
    InsetGroup {
        if (profile == null) {
            GroupRow(if (vm.characterError.isBlank()) "正在读取…" else "读不到", icon = Glyph.Warning, chevron = false,
                subtitle = vm.characterError.ifBlank { null }?.let { "$it（手机上的 Cowork 插件可能还是旧版）" }, onClick = { vm.characterRefresh() })
        } else {
            GroupRow("性格", icon = Glyph.Agent, subtitle = profile.derived?.let { Temperament.easeText(it) } ?: "定一个，它就有自己的脾气和节奏，会自己安排时间",
                value = Temperament.summary(profile), onClick = { open(Screen.CHARACTER) })
            vm.psyche?.takeIf { it.active && profile.traits != null }?.let { p ->
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meter("精力", p.energy, Temperament.energyWords(p.energyBand))
                    Meter("心境", p.mood, Temperament.moodWords(p.moodBand))
                }
            }
        }
    }
    if (profile != null && profile.traits == null) Footnote("没定性格时，它不会自己起来逛，也没有精力和心境；巡视照旧。")
    SectionLabel("${name}找你")
    InsetGroup {
        if (outreach == null) {
            GroupRow("正在读取…", icon = Glyph.Clock, chevron = false, onClick = { vm.characterRefresh() })
        } else {
            val now = System.currentTimeMillis()
            GroupRow("主动找我", icon = Glyph.Chat, value = Temperament.levelText(outreach.level), onClick = { levelDialog = true })
            GroupDivider()
            GroupRow("你的联系方式", icon = Glyph.Phone, subtitle = if (outreach.contacts.isEmpty()) "只在 Continuo 里找你；加上 QQ、微信，它也能在那里找你"
                else "Continuo、" + outreach.contacts.joinToString("、") { Temperament.appLabel(it.packageName) }, onClick = { open(Screen.CONTACTS) })
            GroupDivider()
            GroupRow("夜里别找我", icon = Glyph.Clock, value = "${outreach.quietFrom}–${outreach.quietTo}", onClick = { quietDialog = true })
            GroupDivider()
            GroupRow("别找我", icon = Glyph.Hand, value = Temperament.dndText(outreach.dndUntil, now, ZoneId.systemDefault()), onClick = { dndDialog = true })
            GroupDivider()
            GroupRow("自己的时间", icon = Glyph.Eye, subtitle = outreach.preferences.ifBlank { "想让它多去哪、少去哪、看什么，写在这里" }, onClick = { preferencesDialog = true })
        }
    }
    Footnote("它想跟你分享点什么、说说今天、或者很久没聊了，会自己来找你；你没看到时，它最多换一个地方再找一次。夜里和“别找我”期间都不找。")
    if (levelDialog && outreach != null) ChoiceDialog({ levelDialog = false }) {
        SheetHeader("主动找我", "它自己开口找你的次数，所有地方加起来")
        Temperament.LEVELS.forEach { (key, label) ->
            val n = Temperament.LEVEL_TOPICS[key] ?: 0
            SheetRow(label, if (n == 0) "它不主动找你" else "每天最多 $n 件事", checked = key == outreach.level) {
                vm.setOutreach(Temperament.levelRequest(key)); levelDialog = false
            }
        }
    }
    if (quietDialog && outreach != null) InputDialog("夜里别找我", "写成 23:00-08:00 这样，这段时间它不找你", "${outreach.quietFrom}-${outreach.quietTo}", { quietDialog = false }) { text ->
        val span = Temperament.quietOf(text)
        if (span == null) vm.showError("时间写成 23:00-08:00 这样") else { vm.setOutreach(Temperament.quietRequest(span.first, span.second)); quietDialog = false }
    }
    if (dndDialog && outreach != null) DndDialog(vm, outreach.dndUntil) { dndDialog = false }
    if (preferencesDialog && outreach != null) InputDialog("自己的时间", "它自己逛的时候参考这些话：想多去或少去的应用、想看的东西（最多 ${Temperament.PREFERENCES_MAX} 字）",
        outreach.preferences, { preferencesDialog = false }, allowBlank = true, singleLine = false) { text ->
        runCatching { Temperament.preferencesRequest(text) }.onSuccess { vm.setOutreach(it); preferencesDialog = false }.onFailure { vm.showError(it.message ?: "写不下") }
    }
}

/** 别找我: one hour, until the morning, or let it reach you again. */
@Composable private fun DndDialog(vm: PocketViewModel, until: Long, dismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    ChoiceDialog(dismiss) {
        SheetHeader("别找我", Temperament.dndText(until, now, ZoneId.systemDefault()))
        SheetRow("1 小时", icon = Glyph.Clock) { vm.setDnd(Temperament.inAnHour(now)); dismiss() }
        SheetRow("到明早 8 点", icon = Glyph.Clock) { vm.setDnd(Temperament.nextMorning(now, ZoneId.systemDefault())); dismiss() }
        if (until > now) SheetRow("现在可以找我", icon = Glyph.Check) { vm.setDnd(0); dismiss() }
    }
}

/** A labelled bar 0..100. */
@Composable private fun Meter(label: String, value: Double, words: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f).height(8.dp).clip(CircleShape)) {
            Surface(Modifier.fillMaxSize(), color = Pocket.hairline) {}
            Surface(Modifier.fillMaxHeight().fillMaxWidth((value / 100).toFloat().coerceIn(0f, 1f)), shape = CircleShape, color = Pocket.accent) {}
        }
        Spacer(Modifier.width(10.dp))
        Text("${value.toInt()} · $words", Modifier.widthIn(min = 76.dp), style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
    }
}

/** 性格: a type's preset, then each of the eight values on its own. */
@Composable internal fun CharacterSettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    LaunchedEffect(vm.connected) { if (vm.connected) vm.characterRefresh() }
    val profile = vm.character
    var draft by remember(profile?.traits) { mutableStateOf(profile?.traits.orEmpty()) }
    var clearing by remember { mutableStateOf(false) }
    SettingsPage("性格", back) {
        if (profile == null) {
            InsetGroup { GroupRow(if (vm.characterError.isBlank()) "正在读取…" else "读不到", icon = Glyph.Warning, chevron = false,
                subtitle = vm.characterError.ifBlank { null }, onClick = { vm.characterRefresh() }) }
            return@SettingsPage
        }
        profile.derived?.let { d ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${d.type} · 外倾 ${d.extraverted}%", style = MaterialTheme.typography.headlineSmall, fontFamily = Fonts.serif)
                Text("主导 ${d.stack.getOrNull(0).orEmpty()}，辅助 ${d.stack.getOrNull(1).orEmpty()}，劣势 ${d.stack.getOrNull(3).orEmpty()}。${Temperament.easeText(d)}",
                    style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
            }
        }
        SectionLabel("先选一个类型")
        Temperament.GROUPS.forEach { (group, types) ->
            Text(group, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = Pocket.faint)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { type ->
                    Pill(type, Modifier.weight(1f), trailing = null, selected = profile.derived?.type == type, enabled = vm.connected) { vm.setCharacterType(type) }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp)) {
            Pill("不指定", trailing = null, selected = profile.traits == null, enabled = vm.connected && profile.traits != null) { clearing = true }
        }
        Footnote("选一个类型就套上它的预设，再往下按${identity.shown}的样子微调八个值。它不知道自己是什么类型，除非有人认真问它。")
        if (profile.traits != null) {
            SectionLabel("八个功能")
            InsetGroup {
                Temperament.FUNCTIONS.forEachIndexed { index, f ->
                    if (index > 0) GroupDivider(inset = 16.dp)
                    val value = draft[f] ?: 50
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$f ${Temperament.FUNCTION_NAMES[f]}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("$value", style = MaterialTheme.typography.bodyMedium, color = Pocket.muted)
                        }
                        Text(Temperament.FUNCTION_HINTS[f].orEmpty(), style = MaterialTheme.typography.bodySmall, color = Pocket.faint)
                        Slider(value.toFloat(), { draft = draft + (f to it.toInt().coerceIn(0, 100)) }, valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = Pocket.accent, activeTrackColor = Pocket.accent, inactiveTrackColor = Pocket.hairline))
                    }
                }
            }
            if (draft != profile.traits) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("还原", Modifier.weight(1f)) { draft = profile.traits }
                PrimaryButton("保存", Modifier.weight(1f), enabled = vm.connected) { vm.setCharacterTraits(draft) }
            }
            Footnote("值越高，它越常这样做，做起来也越轻松；外倾的靠和人来往回精神，内倾的靠独处。")
        }
    }
    if (clearing) ConfirmDialog("不指定性格？", "${identity.shown}不再有精力和心境，也不再自己起来逛；巡视照旧可以用。", "不指定", destructive = true, dismiss = { clearing = false }) {
        vm.clearCharacter(); clearing = false
    }
}

/** 你的联系方式: your own accounts in its apps, so it can reach you there too. */
@Composable internal fun ContactSettings(vm: PocketViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    remember { NotifyCenter.init(context) }
    val excluded by NotifyCenter.excluded.collectAsState()
    val apps by CoworkConnection.apps.collectAsState()
    LaunchedEffect(vm.connected) { if (vm.connected && vm.outreach == null) vm.characterRefresh() }
    val outreach = vm.outreach
    val contacts = outreach?.contacts.orEmpty()
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var editingShown by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Contact?>(null) }
    val label = { pkg: String -> apps.firstOrNull { it.packageName == pkg }?.label ?: Temperament.appLabel(pkg) }
    fun save(list: List<Contact>, receipt: String = "已保存") =
        runCatching { Temperament.contactsRequest(list) }.onSuccess { vm.setOutreach(it, receipt) }.onFailure { vm.showError(it.message ?: "存不下") }
    SettingsPage("你的联系方式", back) {
        Footnote("${identity.shown}在 Continuo 里总能找到你。加上你在 QQ、微信这些应用里的号，它也能用它手机上的号私聊你；只在私聊里，不会在群里提你。")
        InsetGroup {
            GroupRow("Continuo", icon = Glyph.Chat, subtitle = "弹一条带它头像的通知", chevron = false)
            contacts.forEach { c ->
                GroupDivider()
                GroupRow(label(c.packageName), icon = Glyph.Phone, subtitle = "它那边给你的备注：${c.remark}\n它在你那边显示为：${c.shownAs}" +
                    if (c.packageName in excluded) "\n这个应用在“通知叫醒”的排除列表里：你回它，它收不到" else "",
                    onClick = { editing = c; editingShown = false }, trailing = {
                        CircleButton(Glyph.Trash, "去掉", container = androidx.compose.ui.graphics.Color.Transparent, tint = Pocket.faint) { removing = c }
                    })
            }
            if (contacts.size < Temperament.CONTACTS_MAX) {
                GroupDivider()
                GroupRow("加一个", icon = Glyph.Plus, tint = Pocket.accent, enabled = outreach != null, onClick = { picking = true })
            }
        }
        Footnote("备注要和它手机上那个应用里你的名字一字不差：它靠这个认出是你。显示的名字是它在你手机上发消息时的名字，插件靠它认出那是它自己发的。")
    }
    if (picking) {
        val rows = apps.filter { a -> contacts.none { it.packageName == a.packageName } }.map { PickerApp(it.packageName, it.label) }
        AppPickerSheet("在哪个应用里找你", if (rows.isEmpty()) "连上它的手机后才能从它装的应用里选" else "选一个它手机上装着、你也在用的应用", rows, emptySet(),
            { picking = false }, max = 1) { picked ->
            picking = false
            picked.firstOrNull()?.let { pkg -> editing = Contact(pkg, "", ""); editingShown = false }
        }
    }
    editing?.let { c ->
        if (!editingShown) InputDialog("它那边给你的备注", "${label(c.packageName)}里你在它的好友列表中叫什么", c.remark, { editing = null }) { remark ->
            if (remark.isBlank()) vm.showError("填上它那边给你的备注") else { editing = c.copy(remark = remark.trim()); editingShown = true }
        } else InputDialog("它在你那边显示的名字", "你的${label(c.packageName)}里，它发来的消息显示的名字", c.shownAs, { editing = null }) { shown ->
            val problem = Temperament.contactProblem(c.remark, shown)
            if (problem != null) vm.showError(problem) else {
                val next = c.copy(shownAs = shown.trim())
                save(contacts.filterNot { it.packageName == c.packageName } + next)
                editing = null
            }
        }
    }
    removing?.let { c ->
        ConfirmDialog("去掉${label(c.packageName)}？", "${identity.shown}不再在${label(c.packageName)}里找你。", "去掉", destructive = true, dismiss = { removing = null }) {
            save(contacts.filterNot { it.packageName == c.packageName }, "去掉了"); removing = null
        }
    }
}
