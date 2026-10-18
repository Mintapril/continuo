package dev.dsh.pocket

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.pocket.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.TimeZone

/** All remote mutations are user initiated. Reconnect never resubmits a prompt. */
class PocketViewModel(application: Application) : AndroidViewModel(application) {
    private val api = DshApi()
    private val vault = CredentialVault(application)
    private val runtime = RuntimeController()
    private val prefs = application.getSharedPreferences("preferences", 0)
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var notice by mutableStateOf(""); private set
    var runtimeStatus by mutableStateOf("尚未检查。连接聊天无需 Root；运行环境管理需要 KernelSU 授权。"); private set
    var runtimeBusy by mutableStateOf(false); private set
    var sessions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var workspaces by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var archived by mutableStateOf<Set<String>>(emptySet()); private set
    var pinned by mutableStateOf<Set<String>>(emptySet()); private set
    var catalog by mutableStateOf(obj()); private set
    var permissionCatalog by mutableStateOf(obj()); private set
    var selected by mutableStateOf<String?>(null); private set
    var rows by mutableStateOf<List<TranscriptRow>>(emptyList()); private set
    var hasMore by mutableStateOf(false); private set
    var historyBusy by mutableStateOf(false); private set
    var streamStatus by mutableStateOf(""); private set
    var jobs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var pending by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var submitting by mutableStateOf(false); private set
    var uncertain by mutableStateOf(false); private set
    var draft by mutableStateOf(""); private set
    var attachments by mutableStateOf<List<PendingAttachment>>(emptyList()); private set
    /** Cowork or Code. There is deliberately no plain chat mode. */
    var mode by mutableStateOf(runCatching { TaskMode.valueOf(prefs.getString("mode", TaskMode.CODE.name)!!) }.getOrDefault(TaskMode.CODE)); private set
    /** DSH agent preset ids; null when the roster could not be read. */
    var presets by mutableStateOf<Set<String>?>(null); private set
    var homeWorkspace by mutableStateOf(prefs.getString("workspace", null)); private set
    var homeModel by mutableStateOf<JSONObject?>(null); private set
    var homePermission by mutableStateOf<String?>(null); private set
    var starting by mutableStateOf(false); private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true)); private set
    private fun homeKey(value: TaskMode) = "home:${value.preset}"
    /** The composer belongs to the open session, or to the start screen of the current mode. */
    val composerKey: String get() = selected ?: homeKey(mode)
    fun modeAvailable(value: TaskMode): Boolean = presets?.contains(value.preset) ?: (value == TaskMode.CODE)
    private val attachmentDrafts = mutableMapOf<String, List<PendingAttachment>>()
    private val sendingSessions = mutableSetOf<String>()
    private var selectionGeneration = 0L
    private var connectionGeneration = 0L
    private val refreshMutex = Mutex()
    private val connectionMutex = Mutex()
    private fun pendingFile(id: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(getApplication<Application>().filesDir, "pending-prompt-$name.json"))
    }
    private val outbox = PendingPromptStore(
        read = { id -> pendingFile(id).let { file -> if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().bufferedReader().use { it.readText() } else null } },
        write = { id, value ->
            val file = pendingFile(id)
            if (value == null) { file.delete(); check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "无法清除已确认的请求记录" } }
            else {
                val stream = file.startWrite()
                try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (e: Exception) { file.failWrite(stream); throw e }
            }
        },
    )
    private fun restoreComposer(id: String) {
        draft = prefs.getString("draft:$id", "") ?: ""
        attachments = attachmentDrafts[id].orEmpty()
        submitting = id in sendingSessions
        if (id.startsWith("home:")) { uncertain = false; return }
        uncertain = try {
            val request = outbox.get(id)
            if (request != null && draft.isBlank()) {
                draft = request.getJSONArray("content").objects().filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") }
                if (draft.isNotEmpty()) prefs.edit().putString("draft:$id", draft).apply()
            }
            request != null
        } catch (e: Exception) { notice = e.message ?: "待确认请求无法读取"; true }
    }
    private fun ownsSelection(id: String, generation: Long) = selected == id && selectionGeneration == generation
    private var clientId = ""
    private val questionWaits = mutableMapOf<String, Job>()
    var questionRemaining by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    private val editingQuestions = mutableSetOf<String>()
    fun setQuestionEditing(id: String, focused: Boolean) { if (focused) editingQuestions.add(id) else editingQuestions.remove(id) }
    var answeringQuestions by mutableStateOf<Set<String>>(emptySet()); private set
    var jobOutput by mutableStateOf(""); private set
    private var outputStream: Job? = null
    private var streams = mutableListOf<Job>()
    private var conversation: Job? = null
    private var jobStream: Job? = null
    private var transcript = Transcript()
    private var snapshotCut = -1L
    private val projections = mutableMapOf<String, ProjectionState>()
    var selectedProjection by mutableStateOf(obj()); private set
    val selectedSummary get() = sessions.firstOrNull { it.text("sessionId") == selected }
    val running get() = selectedSummary?.optBoolean("running") == true

    init {
        restoreComposer(homeKey(mode))
        startSupervisor()
    }
    // DSH issues a fresh login token on every start, so the saved one goes stale. The supervisor keeps the app
    // connected on its own: saved token first, then the current token read through Root, retrying with backoff.
    @Volatile private var autoStopped = false
    @Volatile private var everConnected = false
    @Volatile private var kickNow = true
    /** Called when the app becomes visible: skip the grace period and reconnect at once if needed. */
    fun resumeConnection() { kickNow = true }
    private suspend fun autoConnectOnce(): Boolean {
        val tried = mutableSetOf<String>()
        try { vault.load()?.let { tried += it; connect(it); return true } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        try {
            val token = runtime.execute(RuntimeController.Action.AUTH).trim()
            if (token.isNotEmpty() && token !in tried) { connect(token); return true }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        return false
    }
    private fun startSupervisor() {
        viewModelScope.launch {
            var downSince = 0L; var failures = 0; var told = false
            while (isActive) {
                if (connected || autoStopped || runtimeBusy || connecting) {
                    if (connected) { downSince = 0L; failures = 0; told = false }
                    delay(if (connecting) 500 else 2_000); continue
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (downSince == 0L) downSince = now
                // A brief stream drop heals by itself; only act after a grace period (not on first start / app resume).
                val grace = if (everConnected && !kickNow && failures == 0) 6_000L else 0L
                if (now - downSince < grace) { delay(1_000); continue }
                kickNow = false
                if (autoConnectOnce()) { failures = 0; told = false; downSince = 0L; continue }
                failures++
                if (failures >= 4 && !told) { told = true; notice = "暂时无法自动连接 DSH，将继续重试（可在 设置 → 运行环境 检查服务）。" }
                delay((2_000L * failures).coerceAtMost(15_000L))
            }
        }
    }
    fun clearNotice() { notice = "" }
    fun changeTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun changeHaptics(value: Boolean) { haptics = value; prefs.edit().putBoolean("haptics", value).apply() }

    // ── dsh-mneme 记忆插件 ────────────────────────────────────────────────
    // The plugin owns its own storage inside the DSH profile; the app is a viewer/editor over its HTTP
    // routes. Nothing here changes the local DSH state machine, so every call is independent.
    val memory = MemoryCenter(api)
    var memoryInjection by mutableStateOf<JSONObject?>(null); private set
    var memoryReclaim by mutableStateOf<JSONObject?>(null); private set
    fun memoryRefresh() = task { memory.refresh() }
    fun memoryLoad() = task { memory.load() }
    fun memoryMore() = task { memory.more() }
    fun memorySearch(text: String) = task { memory.search(text) }
    fun memoryFilter(value: String) = task { memory.selectFilter(value); memory.load() }
    fun memoryFlag(key: String, value: Any) = task { memory.patchFlag(key, value); notice = "已保存；开关在 DSH 下次启动时生效" }
    fun memoryMode(value: String) = task { memory.setMode(value); notice = "已记录为「${if (value == "light") "轻量" else "标准"}」；DSH 下次启动时生效" }
    fun memoryUpdate(id: String, patch: JSONObject) = task { memory.update(id, patch); memory.load() }
    fun memoryDelete(id: String) = task { memory.remove(id); memory.load() }
    fun memoryArchive(id: String, archived: Boolean) = task { memory.setArchived(id, archived); memory.load() }
    fun memoryImportance(id: String, importance: Int) = task { memory.setImportance(id, importance) }
    fun memoryCreate(type: String, title: String, content: String, importance: Int) =
        task { memory.create(type, title, content, importance); memory.refresh() }
    fun memoryPreviewInjection() = task { memoryInjection = memory.injectionPreview() }
    fun memoryPreviewReclaim() = task { memoryReclaim = memory.reclaim(dryRun = true) }
    fun memoryReclaimNow() = task { memoryReclaim = memory.reclaim(dryRun = false); memory.refresh() }
    fun clearMemorySheets() { memoryInjection = null; memoryReclaim = null }
    fun changeMode(value: TaskMode) {
        mode = value; prefs.edit().putString("mode", value.name).apply()
        if (selected == null) restoreComposer(homeKey(value))
    }
    val workspaceEditable get() = canChooseWorkspace(selected, starting, submitting, uncertain)
    fun changeWorkspace(id: String?) {
        if (!workspaceEditable) { notice = "工作区已锁定，请新建任务选择其他目录。"; return }
        homeWorkspace = id; prefs.edit().apply { if (id == null) remove("workspace") else putString("workspace", id) }.apply()
    }
    fun updateDraft(value: String) {
        if (submitting || uncertain || starting) return
        draft = value; prefs.edit().putString("draft:$composerKey", value).apply()
    }
    fun removeAttachment(id: String) {
        if (submitting || uncertain || starting) return
        attachments = attachments.filterNot { it.id == id }; attachmentDrafts[composerKey] = attachments
    }
    fun showError(message: String) { notice = message }
    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message ?: "操作失败" }
    }
    private fun closeStreams() {
        connectionGeneration++; streamStatus = "连接已断开"
        streams.forEach { it.cancel() }; streams.clear(); conversation?.cancel(); jobStream?.cancel()
        pending = emptyList(); clientId = ""; questionWaits.values.forEach { it.cancel() }; questionWaits.clear(); questionRemaining = emptyMap(); outputStream?.cancel()
    }
    fun connectSaved() = task { autoStopped = false; val token = vault.load() ?: error("尚未保存本机令牌，请使用 Root 连接或手动输入。") ; connect(token) }
    fun connectManual(token: String) = task { autoStopped = false; connect(token.trim()) }
    fun connectRoot() = task {
        autoStopped = false; connecting = true
        try { connect(runtime.execute(RuntimeController.Action.AUTH).trim()) } finally { connecting = false }
    }
    private suspend fun connect(token: String) = connectionMutex.withLock {
        connecting = true; closeStreams(); connected = false
        val generation = connectionGeneration
        projections.clear(); selectedProjection = obj()
        try {
            api.login(token); vault.save(token); everConnected = true
            val ready = CompletableDeferred<Unit>()
            streams += viewModelScope.launch {
                persistentStream("\$events", obj()) { frame ->
                    if (generation != connectionGeneration) return@persistentStream
                    when (frame.text("type")) {
                        "ready" -> {
                            val eventClient = frame.text("clientId")
                            clientId = eventClient; pending = emptyList(); connected = false
                            questionWaits.values.forEach { it.cancel() }; questionWaits.clear(); questionRemaining = emptyMap()
                            // The events bridge does not replay missed status/list events. Establish
                            // a fresh baseline before announcing this generation as connected.
                            refreshSessions()
                            val models = api.rpc("session/modelCatalog") as? JSONObject ?: obj()
                            val permissions = api.rpc("permissionPresets/catalog") as? JSONObject ?: obj()
                            val roster = try {
                                availablePresetIds(api.rpc("agentPresets/list", obj()) as? JSONObject)
                            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (generation == connectionGeneration && clientId == eventClient) {
                                catalog = models; permissionCatalog = permissions; presets = roster; connected = true; ready.complete(Unit)
                            }
                        }
                        "waterfall" -> {
                            if (frame.text("event") in listOf("approval/request", "user-questions/request"))
                                { pending = pending.filterNot { it.text("eventId") == frame.text("eventId") } + frame; claimQuestion(frame) }
                            else api.rpc("\$events/result", obj("clientId" to clientId, "eventId" to frame.text("eventId"), "outcome" to obj("kind" to "next")))
                        }
                        "cancel" -> { val id = frame.text("eventId"); pending = pending.filterNot { it.text("eventId") == id }; questionWaits.remove(id)?.cancel(); questionRemaining = questionRemaining - id }
                        "emit" -> {
                            val event = frame.text("event")
                            val args = frame.optJSONArray("args") ?: JSONArray()
                            if (event == "api-session/error") notice = args.optString(1, "会话发生错误")
                            if (event.startsWith("api-session/")) refreshSessions()
                        }
                    }
                }
            }
            withTimeout(12_000) { ready.await() }
            streams += viewModelScope.launch { persistentStream("workspace/follow", obj()) { if (generation == connectionGeneration) workspaceFrame(it) } }
            streams += viewModelScope.launch { persistentStream("session/control", obj()) { if (generation == connectionGeneration) projectionFrame(it) } }
            selected?.let { openSession(it) }
        } catch (e: Exception) { closeStreams(); connected = false; throw e }
        finally { connecting = false }
    }
    private fun claimQuestion(event: JSONObject) {
        val wait = event.optJSONObject("request")?.optJSONObject("wait") ?: return
        if (!wait.optBoolean("timed")) return
        val eventId = event.text("eventId"); val generation = clientId
        questionWaits.remove(eventId)?.cancel()
        questionWaits[eventId] = viewModelScope.launch {
            try {
                coroutineScope {
                    var timer: Job? = null
                    api.stream("userQuestions/attachWait", obj("agentId" to event.text("agentId"), "callId" to wait.text("callId"))).collect { frame ->
                        timer?.cancel()
                        timer = launch {
                            var remaining = frame.optLong("remainingMs", 0)
                            while (remaining > 0) { questionRemaining = questionRemaining + (eventId to remaining); delay(1000); if (eventId !in editingQuestions) remaining -= 1000 }
                            if (clientId == generation && pending.any { it.text("eventId") == eventId }) {
                                api.rpc("\$events/result", obj("clientId" to generation, "eventId" to eventId, "outcome" to obj("kind" to "rejected", "error" to obj("name" to "UserQuestionError", "message" to "Question timed out", "code" to "ASK_TIMED_OUT"))))
                                pending = pending.filterNot { it.text("eventId") == eventId }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Host cancellation is authoritative. */ }
            finally { questionRemaining = questionRemaining - eventId }
        }
    }
    fun followJob(id: String) {
        val session = selected ?: return; val generation = selectionGeneration
        outputStream?.cancel(); jobOutput = ""
        outputStream = task { api.stream("job/follow", obj("request" to obj("sessionId" to session, "jobId" to id))).collect { frame ->
            if (ownsSelection(session, generation) && frame.text("type") == "output") jobOutput = (jobOutput + frame.objects("chunks").joinToString("") { it.text("text") }).takeLast(100_000)
        } }
    }
    fun closeJobOutput() { outputStream?.cancel(); jobOutput = "" }
    private suspend fun persistentStream(endpoint: String, args: JSONObject, receive: suspend (JSONObject) -> Unit) {
        val connection = connectionGeneration
        val selection = selectionGeneration
        var waitMs = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                api.stream(endpoint, args).collect { receive(it); waitMs = 1_000 }
                throw IllegalStateException("实时连接结束")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (connection != connectionGeneration) return
                if (endpoint == "\$events") { connected = false; pending = emptyList(); clientId = ""; questionWaits.values.forEach { it.cancel() }; questionWaits.clear(); questionRemaining = emptyMap() }
                if (endpoint == "session/follow" && selection == selectionGeneration) streamStatus = "重新同步中 · ${e.message?.take(80)}"
                delay(waitMs); waitMs = (waitMs * 2).coerceAtMost(15_000)
            }
        }
    }
    suspend fun refreshSessions() {
        val generation = connectionGeneration
        refreshMutex.withLock {
            if (generation != connectionGeneration) return@withLock
            val result = api.rpc("session/list", obj("_request" to obj())) as? JSONObject ?: obj()
            if (generation != connectionGeneration) return@withLock
            sessions = result.objects("items").sortedByDescending { it.optLong("updatedAt") }
            sessions.forEach { row ->
                row.optJSONObject("projections")?.let { hints -> projections.getOrPut(row.text("sessionId")) { ProjectionState() }.hint(hints) }
            }
            publishProjections()
        }
    }
    fun refresh() = task { refreshSessions(); catalog = api.rpc("session/modelCatalog") as? JSONObject ?: obj() }
    private fun workspaceFrame(frame: JSONObject) {
        fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()
        when (frame.text("type")) {
            "baseline" -> frame.getJSONObject("value").let { workspaces = it.objects("items"); archived = strings(it.optJSONArray("archivedSessionIds")); pinned = strings(it.optJSONArray("pinnedSessionIds")) }
            "upsert" -> frame.getJSONObject("workspace").let { w -> workspaces = workspaces.filterNot { it.text("workspaceId") == w.text("workspaceId") } + w }
            "remove" -> workspaces = workspaces.filterNot { it.text("workspaceId") == frame.text("workspaceId") }
            "archived" -> archived = strings(frame.optJSONArray("archivedSessionIds"))
            "pinned" -> pinned = strings(frame.optJSONArray("pinnedSessionIds"))
        }
    }
    private fun projectionFrame(frame: JSONObject) {
        if (frame.text("type") == "baseline") {
            val all = frame.getJSONObject("value").getJSONObject("projections")
            all.keys().forEach { id -> projections.getOrPut(id) { ProjectionState() }.baseline(all.getJSONObject(id)) }
        } else if (frame.text("type") == "projection") {
            projections.getOrPut(frame.text("sessionId")) { ProjectionState() }
                .update(frame.text("key"), frame.opt("value"), frame.getLong("seq"))
        }
        publishProjections()
    }
    private fun publishProjections() {
        selectedProjection = projections[selected]?.value() ?: obj()
        sessions = sessions.map { row -> JSONObject(row.toString()).apply { projections[text("sessionId")]?.let { put("projections", obj("values" to it.value())) } } }
    }
    fun openSession(id: String) {
        val generation = ++selectionGeneration
        val connection = connectionGeneration
        selected = id; restoreComposer(id)
        selectedProjection = projections[id]?.value() ?: obj(); transcript = Transcript(); rows = emptyList(); hasMore = false
        val reducer = transcript
        snapshotCut = -1L; historyBusy = false; streamStatus = "正在同步…"
        conversation?.cancel(); jobStream?.cancel(); closeJobOutput(); rowsPainter?.cancel()
        conversation = viewModelScope.launch {
            persistentStream("session/follow", obj("request" to obj("address" to address(id), "assistantStream" to true, "maxMessages" to 50))) {
                if (!ownsSelection(id, generation) || connection != connectionGeneration) return@persistentStream
                withContext(Dispatchers.Default) { reducer.accept(it) }
                if (it.text("type") == "snapshot") {
                    snapshotCut = it.optLong("cursor", -1)
                    it.optJSONObject("projections")?.let { p -> projections.getOrPut(id) { ProjectionState() }.baseline(p); publishProjections() }
                }
                hasMore = reducer.hasMore; if (streamStatus != "实时已连接") streamStatus = "实时已连接"
                // Structural frames publish immediately. Token frames only mark the reducer
                // dirty; a single painter publishes the latest rows about 12 times a second.
                if (it.text("type") == "assistant-stream") scheduleRows(id, generation, reducer)
                else if (ownsSelection(id, generation)) rows = withContext(Dispatchers.Default) { reducer.rows }
            }
        }
        jobs = emptyList()
        jobStream = viewModelScope.launch { persistentStream("job/list", obj("request" to obj("sessionId" to id))) {
            if (ownsSelection(id, generation) && connection == connectionGeneration && it.text("type") == "rows") jobs = it.objects("jobs")
        } }
    }
    private var rowsPainter: Job? = null
    private fun scheduleRows(id: String, generation: Long, reducer: Transcript) {
        if (rowsPainter?.isActive == true) return
        rowsPainter = viewModelScope.launch {
            delay(80)
            val next = withContext(Dispatchers.Default) { reducer.rows }
            if (ownsSelection(id, generation) && transcript === reducer) rows = next
        }
    }
    fun leaveChat() {
        selectionGeneration++; selected = null; conversation?.cancel(); jobStream?.cancel(); closeJobOutput(); rowsPainter?.cancel()
        rows = emptyList(); jobs = emptyList(); historyBusy = false; selectedProjection = obj()
        restoreComposer(homeKey(mode))
    }
    private fun address(id: String) = obj("kind" to "session", "sessionId" to id)
    fun older() = task {
        val id = selected ?: return@task; if (historyBusy || !hasMore) return@task
        val generation = selectionGeneration; val reducer = transcript; val cut = snapshotCut
        historyBusy = true
        try {
            val page = api.call("session/page", obj("address" to address(id), "throughSeq" to cut, "beforeSeq" to reducer.firstSeq, "maxMessages" to 50))
            if (!ownsSelection(id, generation) || transcript !== reducer || snapshotCut != cut) return@task
            reducer.prepend(page); rows = reducer.rows; hasMore = reducer.hasMore
        } finally { if (ownsSelection(id, generation)) historyBusy = false }
    }
    /**
     * Start a task from the current mode's start screen: create a session bound to the mode's
     * preset, move the composed draft into it, apply the chosen route/permission, then send.
     * A failure before creation keeps the draft on the start screen; after creation it stays
     * in the new session's composer, so nothing typed is lost and nothing is sent twice.
     */
    fun startTask() = task {
        if (selected != null || starting || submitting) return@task
        val taskMode = mode; val key = homeKey(taskMode)
        check(connected) { "请先连接本机 DSH" }
        check(modeAvailable(taskMode)) { "${taskMode.title} 模式尚未在 DSH 中配置（需要预设 ${taskMode.preset}）" }
        val text = draft; val files = attachments.toList()
        require(text.isNotBlank() || files.isNotEmpty()) { "请先输入任务内容" }
        starting = true
        try {
            val workspace = homeWorkspace?.takeIf { id -> workspaces.any { it.text("workspaceId") == id } }
            val request = obj("agentPreset" to taskMode.preset).apply { if (workspace != null) put("workspaceId", workspace) else put("cwd", "/workspace") }
            val id = api.call("session/create", request).getString("sessionId")
            withContext(NonCancellable + Dispatchers.IO) {
                check(prefs.edit().putString("draft:$id", text).remove("draft:$key").commit()) { "会话已创建，但草稿未能移入会话" }
            }
            attachmentDrafts[id] = files; attachmentDrafts.remove(key)
            if (mode == taskMode) { draft = ""; attachments = emptyList() }
            refreshSessions(); openSession(id)
            homeModel?.let { api.call("session/selectModel", obj("sessionId" to id, "provider" to it.text("provider"), "model" to it.text("model"), "reasoningEffort" to it.text("reasoningEffort").ifBlank { null })) }
            homePermission?.let { api.rpc("commands/execute", obj("agentId" to id, "line" to "/permission $it", "submittedAttachments" to JSONArray())) }
            if (selected == id) sendNow(false)
        } finally { starting = false }
    }
    fun createWorkspace(path: String) = task { api.call("workspace/create", obj("path" to path.trim())); notice = "工作区已添加" }
    fun rename(id: String, title: String) = task { api.call("session/rename", obj("sessionId" to id, "title" to title.trim())); refreshSessions() }
    fun archive(id: String, restore: Boolean = false) = task { api.call(if (restore) "workspace/unarchiveSession" else "workspace/archiveSession", obj("sessionId" to id)); refreshSessions(); if (selected == id) leaveChat() }
    fun pin(id: String, unpin: Boolean = false) = task { api.call(if (unpin) "workspace/unpinSession" else "workspace/pinSession", obj("sessionId" to id)) }
    fun selectModel(provider: String, model: String, effort: String?) = task {
        val id = selected
        if (id == null) { homeModel = obj("provider" to provider, "model" to model, "reasoningEffort" to effort); return@task }
        api.call("session/selectModel", obj("sessionId" to id, "provider" to provider, "model" to model, "reasoningEffort" to effort))
    }
    /** Route shown by the composer: the open session's next turn, or the start screen choice. */
    fun currentModel(): JSONObject? = if (selected != null) selectedProjection.optJSONObject("modelSelection")?.optJSONObject("next") ?: catalog.optJSONObject("default")
        else homeModel ?: catalog.optJSONObject("default")
    fun currentPermission(): String = if (selected != null) selectedProjection.optJSONObject("permissions")?.text("currentValue").orEmpty() else homePermission.orEmpty()
    fun permission(value: String) = task {
        require(permissionCatalog.objects("options").any { it.text("value") == value })
        val id = selected
        if (id == null) { homePermission = value; return@task }
        api.rpc("commands/execute", obj("agentId" to id, "line" to "/permission $value", "submittedAttachments" to JSONArray()))
    }
    fun addAttachment(uri: Uri) = task {
        val id = composerKey; val generation = selectionGeneration
        if (submitting || uncertain || starting) return@task
        val resolver = getApplication<Application>().contentResolver
        val attachment = withContext(Dispatchers.IO) {
            var name = "attachment"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
            val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(10 * 1024 * 1024 + 1) } ?: error("无法读取文件")
            require(bytes.size <= 10 * 1024 * 1024) { "首版单个附件上限为 10 MiB，请选择更小的文件。" }
            val type = resolver.getType(uri) ?: "application/octet-stream"
            PendingAttachment(UUID.randomUUID().toString(), name, type, bytes)
        }
        if (composerKey != id || selectionGeneration != generation || submitting || uncertain || starting) return@task
        require(attachments.size < 5) { "一次最多选择 5 个附件" }
        val imageTypes = listOf("image/png", "image/jpeg", "image/webp", "image/gif")
        if (attachment.mediaType in imageTypes) {
            selectedProjection.optJSONObject("imageLimits")?.let { limits ->
                require(attachment.bytes.size <= limits.optLong("maxImageBytes", Long.MAX_VALUE)) { "图片超过 DSH 的单张大小限制" }
                val images = attachments.filter { it.mediaType in imageTypes } + attachment
                require(images.size <= limits.optInt("maxImagesPerMessage", 5)) { "图片数量超过 DSH 限制" }
                require(images.sumOf { it.bytes.size.toLong() } <= limits.optLong("maxMessageImageBytes", Long.MAX_VALUE)) { "图片总大小超过 DSH 限制" }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片内容" }
                require(bounds.outWidth.toLong() * bounds.outHeight <= limits.optLong("maxImagePixels", Long.MAX_VALUE) && maxOf(bounds.outWidth, bounds.outHeight) <= limits.optInt("maxImageDimension", Int.MAX_VALUE)) { "图片尺寸超过 DSH 限制，请缩小后重试" }
            }
        }
        attachments = attachments + attachment; attachmentDrafts[id] = attachments
    }
    fun send(retry: Boolean = false) = task { sendNow(retry) }
    private suspend fun sendNow(retry: Boolean) {
        val id = selected ?: return
        if (!sendingSessions.add(id)) return
        val draftAtSend = draft; val attachmentsAtSend = attachments.toList()
        if (selected == id) submitting = true
        var retained: JSONObject? = null
        var acknowledged = false
        try {
            val request = if (retry) outbox.get(id) ?: error("没有待重试的提交") else {
                require(outbox.get(id) == null) { "上次发送结果未确认，请先使用原请求重试。" }
                require(draftAtSend.isNotBlank() || attachmentsAtSend.isNotEmpty())
                val content = JSONArray()
                if (draftAtSend.isNotBlank()) content.put(obj("type" to "text", "text" to draftAtSend))
                for (a in attachmentsAtSend) {
                    val data = Base64.encodeToString(a.bytes, Base64.NO_WRAP)
                    if (a.mediaType in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) content.put(obj("type" to "image", "mediaType" to a.mediaType, "data" to data, "name" to a.name))
                    else { val receipt = api.rpc("fileUploads/upload", obj("agentId" to id, "request" to obj("data" to data, "name" to a.name))) as JSONObject; content.put(obj("type" to "file", "receiptId" to receipt.getString("receiptId"))) }
                }
                obj("requestId" to UUID.randomUUID().toString(), "sessionId" to id, "mode" to "queue", "content" to content, "clientTimeZone" to TimeZone.getDefault().id).also {
                    // A disk failure prevents sending. A crash after this commit remains safe to retry.
                    withContext(Dispatchers.IO) { outbox.retain(id, it) }
                }
            }
            retained = request
            if (selected == id) uncertain = true
            api.call("session/prompt", request)
            acknowledged = true
            // Clear the captured session, never whichever composer is now visible. Keep the
            // outbox until its draft removal is durable, including cancellation after success.
            withContext(NonCancellable + Dispatchers.IO) {
                check(prefs.edit().remove("draft:$id").commit()) { "发送已成功，但本地草稿未能保存" }
                outbox.clear(id, request.getString("requestId"))
            }
            attachmentDrafts.remove(id)
        } catch (e: RpcRejectedException) {
            retained?.let { request -> withContext(NonCancellable + Dispatchers.IO) { outbox.clear(id, request.getString("requestId")) } }
            // An explicit rejection is editable; upload/transport failures never guess acceptance.
            throw e
        } finally {
            sendingSessions.remove(id)
            if (selected == id) restoreComposer(id)
        }
        if (acknowledged) refreshSessions()
    }
    fun cancelTurn() = task { selected?.let { api.call("session/cancel", obj("sessionId" to it)) } }
    fun killJob(id: String) = task { api.call("job/kill", obj("sessionId" to selected, "jobId" to id)) }
    fun answer(event: JSONObject, value: Any) = task {
        require(clientId.isNotBlank()) { "实时连接已失效，请等待问题重新同步。" }
        api.rpc("\$events/result", obj("clientId" to clientId, "eventId" to event.text("eventId"), "outcome" to obj("kind" to "result", "value" to value)))
        val id = event.text("eventId"); pending = pending.filterNot { it.text("eventId") == id }; questionWaits.remove(id)?.cancel(); questionRemaining = questionRemaining - id
    }
    fun answerContinued(sessionId: String, callId: String, value: JSONObject, completed: () -> Unit) = task {
        val key = "$sessionId:$callId"
        if (key in answeringQuestions) return@task
        answeringQuestions = answeringQuestions + key
        try {
            val accepted = api.rpc("userQuestions/answer", obj("agentId" to sessionId, "callId" to callId, "answer" to value))
            check(accepted == true) { "此问题已不再等待回答，请等待会话同步。" }
            completed()
        } finally { answeringQuestions = answeringQuestions - key }
    }
    fun runtime(action: RuntimeController.Action) = task {
        if (runtimeBusy) return@task; runtimeBusy = true
        try {
            if (action == RuntimeController.Action.STOP) autoStopped = true
            if (action in listOf(RuntimeController.Action.START, RuntimeController.Action.RESTART, RuntimeController.Action.RECREATE_ENVIRONMENT)) { autoStopped = false; kickNow = true }
            runtimeStatus = runtime.execute(action)
            if (action == RuntimeController.Action.STOP) { closeStreams(); connected = false; api.disconnect() }
            if (action in listOf(RuntimeController.Action.START, RuntimeController.Action.RESTART, RuntimeController.Action.RECREATE_ENVIRONMENT)) {
                var failure: Exception? = null
                for (attempt in 0..9) {
                    try { connect(runtime.execute(RuntimeController.Action.AUTH).trim()); failure = null; break }
                    catch (e: Exception) { failure = e; delay(1_000) }
                }
                failure?.let { throw it }
            }
        } finally { runtimeBusy = false }
    }
    suspend fun image(id: String): ByteArray? {
        val session = selected ?: return null
        val result = api.call("session/attachment", obj("sessionId" to session, "attachmentId" to id))
        return Base64.decode(result.text("data"), Base64.DEFAULT)
    }
    override fun onCleared() { closeStreams(); super.onCleared() }
}

data class PendingAttachment(val id: String, val name: String, val mediaType: String, val bytes: ByteArray)
