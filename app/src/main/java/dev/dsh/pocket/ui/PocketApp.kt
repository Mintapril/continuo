@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.dsh.pocket.ui

import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.pocket.CallActivity
import dev.dsh.pocket.PocketViewModel
import dev.dsh.pocket.cowork.CoworkConnection
import dev.dsh.pocket.cowork.CoworkControllerService
import dev.dsh.pocket.cowork.CoworkLinkState
import dev.dsh.pocket.cowork.ErrorClock
import dev.dsh.pocket.cowork.LiveTextureView
import dev.dsh.pocket.cowork.LiveVideo
import dev.dsh.pocket.cowork.StatusCard
import dev.dsh.pocket.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.util.Calendar

/** Every screen of the app. Settings pages go back to the settings hub; the developer pages back to 开发者, its 性格 and
 *  联系方式 pages back to 它. */
internal enum class Screen { TODAY, SETTINGS, IDENTITY, CHARACTER, CONTACTS, COWORK, NOTIFY, MEMORY, ENTRY, DSH, APPEARANCE, ABOUT, DEVELOPER, DEV_NEW, ONBOARDING }
private val IDENTITY_PAGES = setOf(Screen.CHARACTER, Screen.CONTACTS)
private val SETTINGS_PAGES = setOf(Screen.IDENTITY, Screen.COWORK, Screen.NOTIFY, Screen.MEMORY, Screen.ENTRY, Screen.DSH, Screen.APPEARANCE, Screen.ABOUT, Screen.DEVELOPER)

/** The home page's tabs, swiped or tapped: 聊天, 事件, 便条, 日记. */
private const val TODAY_TABS = 4
private const val NOTES_TAB = 2

/**
 * The app is opened now and then: the first screen shows what the AI is doing now on top, then its chat with the owner,
 * the events it ran on its own, its notes and its diary as tabs, and 留便条 / 叫它 at the bottom. Everything else lives
 * in the settings; Code tasks under 开发者.
 */
@Composable fun PocketApp(vm: PocketViewModel, open: String? = null, consumeOpen: () -> Unit = {}) {
    val context = LocalContext.current
    val identity by IdentityStore.flow(context).collectAsState()
    var screen by rememberSaveable { mutableStateOf(if (identity.onboarded) Screen.TODAY else Screen.ONBOARDING) }
    // Kept here, so the tab shown survives the settings pages (the pager state is saved like rememberSaveable).
    val tabs = rememberPagerState { TODAY_TABS }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(open) {
        when (open) {
            "call" -> { context.startActivity(CallActivity.call(context)); consumeOpen() }
            "note" -> { context.startActivity(CallActivity.note(context)); consumeOpen() }
            "chat" -> { if (vm.selected != null) vm.leaveChat(); screen = Screen.TODAY; tabs.requestScrollToPage(0); consumeOpen() }
            "notes" -> { if (vm.selected != null) vm.leaveChat(); screen = Screen.TODAY; tabs.requestScrollToPage(NOTES_TAB); consumeOpen() }
            null -> {}
            else -> consumeOpen()
        }
    }
    LaunchedEffect(vm.notice) { if (vm.notice.isNotBlank()) { snackbar.showSnackbar(vm.notice, withDismissAction = true); vm.clearNotice() } }
    BackHandler(vm.selected != null) { vm.leaveChat() }
    BackHandler(vm.selected == null && screen != Screen.TODAY && screen != Screen.ONBOARDING) {
        screen = when (screen) { Screen.DEV_NEW -> Screen.DEVELOPER; in IDENTITY_PAGES -> Screen.IDENTITY; in SETTINGS_PAGES -> Screen.SETTINGS; else -> Screen.TODAY }
    }
    CompositionLocalProvider(LocalHaptics provides vm.haptics) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AnimatedContent((vm.selected != null) to screen, transitionSpec = {
                // Deeper pages come in from the right, going back slides them out the same way; the rest cross-fades.
                val from = depth(initialState.first, initialState.second); val to = depth(targetState.first, targetState.second)
                when {
                    to > from -> (slideInHorizontally(tween(320, easing = EaseOutCubic)) { it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320, easing = EaseOutCubic)) { -it / 10 } + fadeOut(tween(180)))
                    to < from -> (slideInHorizontally(tween(320, easing = EaseOutCubic)) { -it / 10 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320, easing = EaseOutCubic)) { it / 4 } + fadeOut(tween(180)))
                    else -> fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                }
            }, label = "screen") { (chat, target) ->
                val toSettings = { screen = Screen.SETTINGS }
                if (chat) ChatScreen(vm) { vm.leaveChat() }
                else when (target) {
                    Screen.TODAY -> Today(vm, tabs, { screen = Screen.CHARACTER }) { screen = Screen.SETTINGS }
                    Screen.ONBOARDING -> Onboarding(vm) { screen = Screen.TODAY }
                    Screen.SETTINGS -> Settings(vm, back = { screen = Screen.TODAY }) { screen = it }
                    Screen.IDENTITY -> IdentitySettings(vm, toSettings) { screen = it }
                    Screen.CHARACTER -> CharacterSettings(vm) { screen = Screen.IDENTITY }
                    Screen.CONTACTS -> ContactSettings(vm) { screen = Screen.IDENTITY }
                    Screen.COWORK -> CoworkSettings(vm, toSettings)
                    Screen.NOTIFY -> NotifySettings(vm, toSettings)
                    Screen.APPEARANCE -> AppearanceSettings(vm, toSettings)
                    Screen.ABOUT -> AboutSettings(toSettings)
                    Screen.ENTRY -> EntrySettings(vm, toSettings)
                    Screen.DSH -> DshSettings(vm, toSettings)
                    Screen.MEMORY -> MemorySettings(vm, toSettings)
                    Screen.DEVELOPER -> DeveloperTasks(vm, toSettings) { screen = Screen.DEV_NEW }
                    Screen.DEV_NEW -> DeveloperNewTask(vm) { screen = Screen.DEVELOPER }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 96.dp)) { data -> PocketSnackbar(data) }
        }
        vm.pending.firstOrNull()?.let { key(it.text("eventId")) { PendingRequest(vm, it) } }
    }
}

/** How deep a page sits, for the direction of the page transition. */
private fun depth(chat: Boolean, screen: Screen): Int = when {
    chat -> 3
    screen == Screen.TODAY || screen == Screen.ONBOARDING -> 0
    screen == Screen.SETTINGS -> 1
    screen == Screen.DEV_NEW || screen in IDENTITY_PAGES -> 3
    else -> 2
}

@Composable internal fun PocketSnackbar(data: SnackbarData) {
    Surface(Modifier.padding(horizontal = 20.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, Modifier.weight(1f, fill = false).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            IconButton({ data.dismiss() }, Modifier.size(40.dp)) { GlyphIcon(Glyph.Close, "关闭提示", size = 16.dp) }
        }
    }
}

// ───────────────────────────── Today ─────────────────────────────

/** The local clock, ticking once a minute, for the idle faces and the date line. */
@Composable internal fun rememberMinute(): Calendar {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); now = Calendar.getInstance() } }
    return now
}

/** What it is doing now, from the broker state: the same words as its status card. */
@Composable internal fun rememberStatusCard(name: String): StatusCard.Card {
    val link by CoworkConnection.connection.collectAsState()
    val status by CoworkConnection.status.collectAsState()
    val now = rememberMinute()
    val context = LocalContext.current
    return StatusCard.of(name, link, status, false, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), ErrorClock.ageMs(context, status, now.timeInMillis))
}

@Composable private fun Today(vm: PocketViewModel, tabs: PagerState, openCharacter: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity by IdentityStore.flow(context).collectAsState()
    LaunchedEffect(vm.connected) { if (vm.connected) { vm.schedulesRefresh(); vm.diaryRefresh(); vm.characterRefresh() } }
    // 聊天 is followed from here, not from its tab: the pager drops tabs it is not showing, and coming back reloaded it.
    val owner by OwnerConversation.flow(context).collectAsState()
    val ownerExists = vm.sessions.any { it.optString("sessionId") == owner }
    LaunchedEffect(owner, vm.connected, ownerExists) { vm.followOwner() }
    val today = remember(rememberMinute().get(Calendar.DAY_OF_YEAR)) { Companion.dayHeading(System.currentTimeMillis()).substringAfter(" · ") }
    val week = remember(today) { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[LocalDate.now().dayOfWeek.value - 1] }
    val active = vm.schedules.tasks.count { it.text("status") == "active" }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(today, style = MaterialTheme.typography.headlineMedium, fontFamily = Fonts.serif, fontWeight = FontWeight.Medium)
                Text(week, style = MaterialTheme.typography.labelMedium, color = Pocket.muted)
            }
            CircleButton(Glyph.Settings, "设置", onClick = openSettings)
        }
        NowCard(identity, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        AnimatedVisibility(!vm.connected, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ConnectCard(vm, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
        }
        // A swipe anywhere on the page moves the pages with the finger, and the pill with them; a tap on a tab goes there.
        Tabs(tabs.currentPage, listOf("聊天", "事件", if (active > 0) "便条 $active" else "便条", "日记"), Modifier.padding(horizontal = 16.dp),
            position = { tabs.currentPage + tabs.currentPageOffsetFraction }) { scope.launch { tabs.animateScrollToPage(it) } }
        HorizontalPager(tabs, Modifier.weight(1f).fillMaxWidth()) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) { 0 -> ChatPane(vm, identity, visible = tabs.currentPage == 0 && !tabs.isScrollInProgress); 1 -> EventsPane(vm, identity); NOTES_TAB -> NotesPane(vm, identity, openCharacter); else -> DiaryPane(vm, identity) }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val haptic = rememberHaptic()
            Surface({ haptic(); context.startActivity(CallActivity.note(context)) }, Modifier.weight(1f).heightIn(min = 52.dp), shape = CircleShape, color = Pocket.sticky, contentColor = Pocket.onSticky) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Note, null, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("留便条", style = MaterialTheme.typography.titleSmall)
                }
            }
            Surface({ haptic(); context.startActivity(CallActivity.call(context)) }, Modifier.weight(1f).heightIn(min = 52.dp), shape = CircleShape, color = Pocket.ink, contentColor = Pocket.onInk) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Chat, null, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("叫${identity.shown}", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/**
 * Segmented tabs: the white pill slides to the chosen one. With [position] (a pager's page plus its offset, read as it
 * moves) the pill follows the pages under the finger instead.
 */
@Composable internal fun Tabs(selected: Int, labels: List<String>, modifier: Modifier = Modifier, position: (() -> Float)? = null, change: (Int) -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.padding(3.dp)) {
            val width = maxWidth / labels.size.coerceAtLeast(1)
            val at by animateDpAsState(width * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "tab")
            val last = (labels.size - 1).coerceAtLeast(0).toFloat()
            Box(Modifier.matchParentSize()) {
                // Read in the layout phase, so a moving pager shifts the pill without recomposing the tabs.
                Surface(Modifier.offset { IntOffset((position?.let { width * it().coerceIn(0f, last) } ?: at).roundToPx(), 0) }.width(width).fillMaxHeight(), shape = CircleShape,
                    color = if (Pocket.dark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White, shadowElevation = if (Pocket.dark) 0.dp else 1.dp) {}
            }
            Row {
                labels.forEachIndexed { index, label ->
                    val active = index == selected
                    val ink by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface else Pocket.muted, tween(200), label = "tab ink")
                    Box(Modifier.weight(1f).clip(CircleShape).clickable(role = Role.Tab) { change(index) }.semantics { this.selected = active }) {
                        Text(label, Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = ink)
                    }
                }
            }
        }
    }
}

/** 现在: its face, what it is doing, and a small live picture of its phone; a tap opens the picture (看画面). */
@Composable private fun NowCard(identity: Identity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val card = rememberStatusCard(identity.shown)
    val status by CoworkConnection.status.collectAsState()
    val link by CoworkConnection.connection.collectAsState()
    val linked = link == CoworkLinkState.CONNECTED && status.optInt("displayId") > 0
    val open = { CoworkControllerService.show(context, if (card.asking) CoworkControllerService.ACCEPT else CoworkControllerService.WATCH) }
    val tone by animateColorAsState(if (card.asking) Pocket.warning else MaterialTheme.colorScheme.onSurface, tween(250), label = "now tone")
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.clickable(enabled = linked, role = Role.Button, onClickLabel = "看${identity.shown}的手机", onClick = open).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(identity.avatar, card.mood, identity.shown, size = 60.dp)
            Spacer(Modifier.width(12.dp))
            // What it does now changes in place: the old words fade out as the new ones come in.
            AnimatedContent(card.title to card.text, Modifier.weight(1f), transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 5 }) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false)
            }, label = "now") { (title, text) ->
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = tone)
                    Spacer(Modifier.height(2.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = Pocket.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AnimatedVisibility(card.asking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        TextButton(open, contentPadding = PaddingValues(0.dp)) { Text("去接手", color = Pocket.accent, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
            AnimatedVisibility(linked, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Row { Spacer(Modifier.width(10.dp)); LivePreview(Modifier.width(54.dp)) }
            }
        }
    }
}

/** The small live picture of its phone (shared stream: the broker allows one viewer, reference counted). */
@Composable internal fun LivePreview(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { CoworkConnection.acquireLive(); onDispose { CoworkConnection.releaseLive() } }
    val poster by CoworkConnection.live.collectAsState()
    val info by LiveVideo.info.collectAsState()
    val ratio = (info?.width ?: 720).toFloat() / (info?.height ?: 1608).toFloat()
    Box(modifier.aspectRatio(ratio).clip(RoundedCornerShape(10.dp)).background(Color(0xFF141416))) {
        poster?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.matchParentSize()) }
        AndroidView(factory = { LiveTextureView(it) }, modifier = Modifier.matchParentSize())
    }
}

@Composable internal fun ConnectCard(vm: PocketViewModel, modifier: Modifier = Modifier) {
    var manual by remember { mutableStateOf(false) }
    InsetGroup(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (vm.connecting) Pocket.warning else Pocket.faint, pulse = vm.connecting); Spacer(Modifier.width(8.dp))
                Text(if (vm.connecting) "正在连接本机 DSH…" else "未连接到本机 DSH", style = MaterialTheme.typography.titleSmall)
            }
            Text("日记、便条和叫它都要连上手机里的 DSH；首次可通过 KernelSU 读取本机令牌，或手动输入。", style = MaterialTheme.typography.bodySmall, color = Pocket.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Root 连接", Modifier.weight(1f), enabled = !vm.connecting) { vm.connectRoot() }
                SecondaryButton("输入令牌", Modifier.weight(1f), enabled = !vm.connecting) { manual = true }
            }
        }
    }
    if (manual) InputDialog("输入连接令牌", "DSH Web 的访问令牌，只保存在本机加密存储中", "", { manual = false }, secret = true) { vm.connectManual(it); manual = false }
}

/** Session actions: rename, pin, archive. Archive never deletes records or files. */
@Composable internal fun SessionMenu(vm: PocketViewModel, row: JSONObject, dismiss: () -> Unit, inChat: Boolean = false, openJobs: (() -> Unit)? = null, openPermission: (() -> Unit)? = null) {
    val id = row.text("sessionId")
    var rename by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    val archived = id in vm.archived; val pinned = id in vm.pinned
    if (!rename && !archive) PocketSheet(dismiss) {
        SheetHeader(sessionTitle(row), listOf(sessionMode(row).title, workspaceLabel(row.text("cwd"))).joinToString(" · "))
        if (inChat) {
            openPermission?.let { SheetRow("工具权限", permissionLabel(vm.currentPermission()), Glyph.Shield) { it() } }
            SheetRow("工作区已锁定", row.text("cwd"), Glyph.Folder, enabled = false) {}
            openJobs?.let { SheetRow("后台任务", icon = Glyph.Terminal) { it() } }
        }
        SheetRow("重命名", icon = Glyph.Pencil) { rename = true }
        SheetRow(if (pinned) "取消置顶" else "置顶", icon = Glyph.Pin) { vm.pin(id, unpin = pinned); dismiss() }
        SheetRow(if (archived) "恢复" else "归档", icon = Glyph.Archive) { if (archived) { vm.archive(id, restore = true); dismiss() } else archive = true }
    }
    if (rename) InputDialog("重命名", "新的标题", sessionTitle(row), { rename = false; dismiss() }) { vm.rename(id, it); rename = false; dismiss() }
    if (archive) ConfirmDialog("归档这个会话？", "不会删除会话记录或工作区文件，可在开发者的“已归档”中恢复。运行中的请先停止。", "归档", dismiss = { archive = false; dismiss() }) {
        vm.archive(id); archive = false; dismiss()
    }
}

