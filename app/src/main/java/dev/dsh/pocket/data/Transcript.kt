package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/** Presentation values only; [detail] is suitable for an expandable audit/tool card. */
data class TranscriptRow(
    val key: String,
    val role: String,
    val title: String = "",
    val text: String = "",
    val reasoning: String = "",
    val detail: String = "",
    val streaming: Boolean = false,
    val error: Boolean = false,
    val images: List<JSONObject> = emptyList(),
)

/**
 * Single-owner, transport/UI-independent reducer for DSH 0.2.0-rc.2 session/follow.
 * Feed an opening snapshot, then ordered frames; prepend session/page values separately.
 * A protocol discontinuity throws IllegalStateException: discard the connection and obtain
 * a new snapshot, rather than guessing across a missing event, revision, or chunk.
 * Inputs are detached. Raw durable events remain indexed by seq, including shadowed events.
 */
class Transcript {
    private var events = TreeMap<Long, JSONObject>()
    private var revision = 0L
    private var active: Attempt? = null
    private var pending = linkedMapOf<Long, Settlement>()
    private var view: List<TranscriptRow> = emptyList()
    /** Rows derived from durable events only (no live attempt). Token chunks never invalidate it. */
    private var durableView: List<TranscriptRow> = emptyList()
    /** Durable events are immutable per seq (conflicts are rejected), so their presentation rows are memoized. */
    private val rowCache = HashMap<String, TranscriptRow?>()

    private var viewDirty = false
    /** Built lazily: token frames only mark the view dirty, the painter pays for the live row once per paint. */
    val rows: List<TranscriptRow> @Synchronized get() {
        if (viewDirty) { view = withAttempt(durableView, events, active); viewDirty = false }
        return view
    }
    var hasMore: Boolean = false
        private set
    var cursor: Long = -1L
        private set
    val firstSeq: Long? get() = events.firstEntry()?.key

    @Synchronized fun accept(frame: JSONObject) {
        val input = frame.detached()
        when (input.requiredText("type")) {
            "snapshot" -> snapshot(input)
            "event" -> event(input.requiredObject("event"))
            "assistant-stream" -> stream(input.requiredObject("frame"))
            else -> error("Unsupported follow frame: ${input.string("type")}; reconnect required")
        }
    }

    @Synchronized fun prepend(page: JSONObject) {
        val input = page.detached()
        val older = readRecords(input.requiredArray("records"))
        val more = input.requiredBoolean("hasMore")
        val merged = TreeMap(events)
        older.forEach { (seq, value) ->
            check(seq <= cursor) { "History extends beyond follow cursor; reconnect required" }
            merged[seq]?.let { check(sameJson(it, value)) { "Conflicting durable seq $seq" } }
            merged[seq] = value
        }
        validateWindow(merged, cursor, more)
        val durable = renderDurable(merged, pending)
        events = merged
        hasMore = more
        durableView = durable
        viewDirty = true
    }

    private fun snapshot(frame: JSONObject) {
        val cut = frame.integer("cursor", -1)
        val replacement = readRecords(frame.requiredArray("records"))
        val more = frame.requiredBoolean("hasMore")
        validateWindow(replacement, cut, more)
        val baseline = frame.optJSONObject("assistantStream")
        val newRevision = baseline?.integer("revision") ?: 0L
        val attempt = baseline?.optJSONObject("activeAttempt")?.let { value ->
            val restored = newAttempt(value)
            check(restored.startedAfterSeq <= cut) { "Attempt starts beyond snapshot cursor" }
            val count = restored.assembly.compact(value.requiredArray("stream"))
            restored.nextIndex = value.integer("nextIndex")
            check(count == restored.nextIndex) { "Compact stream chunk count does not match nextIndex" }
            restored
        }
        rowCache.clear()
        val durable = renderDurable(replacement, emptyMap())
        events = replacement
        cursor = cut
        hasMore = more
        revision = newRevision
        active = attempt
        pending = linkedMapOf()
        durableView = durable
        viewDirty = true
    }

    private fun event(value: JSONObject) {
        val seq = value.integer("seq")
        value.requiredText("type")
        if (seq <= cursor) {
            check(events[seq]?.let { sameJson(it, value) } == true) { "Conflicting or unretained durable seq $seq" }
            return // An identical replay cannot duplicate a row or disturb the live assembler.
        }
        check(seq == cursor + 1) { "Durable seq gap: expected ${cursor + 1}, received $seq" }
        pending[seq]?.let { validateSettlement(value, it) }
        // In place: a failure below forces a reconnect, whose snapshot replaces every field, so no copy is needed.
        events[seq] = value
        val remaining = if (pending.containsKey(seq)) LinkedHashMap(pending).apply { remove(seq) } else pending
        val durable = renderDurable(events, remaining)
        cursor = seq
        pending = remaining
        durableView = durable
        viewDirty = true
    }

    private fun stream(frame: JSONObject) {
        val nextRevision = frame.integer("revision")
        check(nextRevision == revision + 1) { "Assistant revision gap: expected ${revision + 1}, received $nextRevision" }
        val id = frame.requiredText("attemptId")
        var next = active
        val waiting = LinkedHashMap(pending)
        when (frame.requiredText("type")) {
            "start" -> {
                check(active == null) { "Assistant start before previous attempt ended" }
                next = newAttempt(frame)
                check(next.startedAfterSeq == cursor) { "Assistant start cursor mismatch; reconnect required" }
            }
            "chunk", "end" -> {
                val attempt = active ?: error("Assistant frame without active attempt; reconnect required")
                check(attempt.id == id) { "Assistant attempt identity mismatch" }
                val index = frame.integer("index")
                check(index == attempt.nextIndex) { "Assistant chunk gap: expected ${attempt.nextIndex}, received $index" }
                if (frame.string("type") == "chunk") {
                    // In place (a failure forces a reconnect and a fresh snapshot): no per-token deep copy.
                    attempt.assembly.push(frame.requiredObject("chunk"))
                    attempt.nextIndex++
                } else {
                    val outcome = frame.requiredObject("outcome")
                    when (outcome.requiredText("kind")) {
                        "abandoned" -> Unit
                        "committed" -> {
                            val seq = outcome.integer("seq")
                            val type = outcome.requiredText("eventType")
                            check(type == "assistant/message" || type == "assistant/attempt") { "Invalid assistant settlement eventType" }
                            check(seq > attempt.startedAfterSeq) { "Assistant settlement precedes attempt" }
                            val settlement = Settlement(attempt, type)
                            val candidate = candidate(events, attempt)
                            check(candidate == null || candidate.integer("seq") == seq) { "Assistant settled a different durable seq" }
                            val durable = events[seq]
                            if (durable != null) validateSettlement(durable, settlement)
                            else {
                                check(seq > cursor) { "Assistant settlement missing from retained history" }
                                check(!waiting.containsKey(seq)) { "Two assistant attempts settled at one seq" }
                                waiting[seq] = settlement
                            }
                        }
                        else -> error("Unknown assistant outcome; reconnect required")
                    }
                    next = null
                }
            }
            else -> error("Unknown assistant frame; reconnect required")
        }
        val settlementsChanged = waiting.size != pending.size || waiting.keys != pending.keys
        val durable = if (settlementsChanged) renderDurable(events, waiting) else durableView
        active = next
        pending = waiting
        revision = nextRevision
        durableView = durable
        viewDirty = true
    }

    private fun newAttempt(value: JSONObject) = Attempt(
        value.requiredText("attemptId"), value.integer("startedAfterSeq", -1),
        value.integer("turn"), value.integer("step"),
    )

    private fun validateSettlement(event: JSONObject, settlement: Settlement) {
        check(event.string("type") == settlement.type && matches(event, settlement.attempt)) {
            "Assistant settlement does not match exact durable event"
        }
    }

    private fun matches(event: JSONObject, attempt: Attempt): Boolean {
        if (event.integer("seq") <= attempt.startedAfterSeq) return false
        if (event.string("type") !in ASSISTANT_EVENTS) return false
        val data = event.requiredObject("data")
        return data.integer("turn") == attempt.turn && data.integer("step") == attempt.step
    }

    /** Runtime-injected user-role messages (plugin nudges, compaction checkpoints, runtime context) that must not look typed by the user. */
    private fun injectedKind(source: JSONObject?): Boolean {
        val kind = source?.string("kind").orEmpty()
        return kind.startsWith("plugin:") || kind == "compact-checkpoint" || kind == "runtime-context"
    }

    private fun candidate(source: TreeMap<Long, JSONObject>, attempt: Attempt): JSONObject? {
        val matches = source.tailMap(attempt.startedAfterSeq, false).values.filter { matches(it, attempt) }
        check(matches.size <= 1) { "Multiple durable settlements for one active attempt" }
        return matches.firstOrNull()
    }

    private fun readRecords(records: JSONArray): TreeMap<Long, JSONObject> {
        val result = TreeMap<Long, JSONObject>()
        var previous: Long? = null
        records.objectValues().forEach { record ->
            check(record.requiredText("type") == "event") { "Unsupported history record" }
            val event = record.requiredObject("event")
            val seq = event.integer("seq")
            event.requiredText("type")
            check(previous == null || seq == previous!! + 1) { "History durable seq gap or disorder" }
            result[seq] = event
            previous = seq
        }
        return result
    }

    private fun validateWindow(source: TreeMap<Long, JSONObject>, cut: Long, more: Boolean) {
        if (source.isEmpty()) {
            check(cut == -1L && !more) { "Snapshot/history omitted durable events" }
            return
        }
        check(source.lastKey() == cut) { "Snapshot/history does not reach durable cursor" }
        var expected = source.firstKey()
        source.keys.forEach { seq ->
            check(seq == expected) { "History durable seq gap: expected $expected, received $seq" }
            expected++
        }
        check(more || source.firstKey() == 0L) { "History omitted its durable prefix" }
    }

    /** Replaying raw events also re-applies replacements when their older endpoints arrive. */
    private fun renderDurable(source: TreeMap<Long, JSONObject>, waiting: Map<Long, Settlement>): List<TranscriptRow> {
        val nodes = mutableListOf<Node>()
        source.values.forEach { event ->
            val type = event.string("type")
            val knownSurface = type in SURFACE_EVENTS
            // Unknown ignorable metadata is opaque, not a surface-editing instruction.
            val isSurface = knownSurface // Extension metadata cannot erase conversation messages.
            val node = Node(event, isSurface)
            val operation = event.opt("surfaceOp")
            if (!isSurface || operation == "append" || (type == "tool/result" && operation == null)) {
                nodes += node
            } else if (operation is JSONObject && operation.string("op") == "replace") {
                val start = operation.integer("startSeq")
                val end = operation.integer("endSeq")
                check(start < event.integer("seq") && end < event.integer("seq")) { "Surface replacement references the future" }
                val from = nodes.indexOfFirst { it.surface && it.event.integer("seq") == start }
                val through = nodes.indexOfFirst { it.surface && it.event.integer("seq") == end }
                if (from >= 0 && through >= 0) {
                    check(from <= through) { "Surface replacement endpoints are reversed" }
                    for (index in through downTo from) if (nodes[index].surface) nodes.removeAt(index)
                    nodes.add(from, node)
                } else {
                    val first = source.firstKey()
                    check((from >= 0 || start < first) && (through >= 0 || end < first)) {
                        "Surface replacement endpoint missing from retained history"
                    }
                    // A backwards window need not contain the replaced prefix. Clip only
                    // a known span; do not guess an unknown prefix's pre-existing ordering.
                    if (from < 0 && through >= 0) {
                        for (index in through downTo 0) if (nodes[index].surface) nodes.removeAt(index)
                        nodes.add(0, node.copy(partialReplacement = true))
                    } else {
                        nodes.add(if (from >= 0) from else 0, node.copy(partialReplacement = true))
                    }
                }
            } else {
                error("Missing or unsupported surfaceOp for $type; reconnect required")
            }
        }
        val calls = nodes.filter { it.event.string("type") == "tool/call" }
            .associateBy { it.event.requiredObject("data").requiredText("callId") }
        val results = nodes.filter { it.event.string("type") == "tool/result" }
            .associateBy { toolId(it.event.requiredObject("data").requiredObject("message")) }
        val output = mutableListOf<TranscriptRow>()
        nodes.forEach { node ->
            val event = node.event
            val type = event.string("type")
            val data = event.optJSONObject("data")
            val row = when (type) {
                "tool/call" -> {
                    val result = results[data!!.requiredText("callId")]?.event
                    cached("tool:${event.integer("seq")}:${result?.integer("seq") ?: -1}") { toolRow(event, result) }
                }
                "tool/result" -> if (calls.containsKey(toolId(data!!.requiredObject("message")))) null else cached("tool:-:${event.integer("seq")}") { toolRow(null, event) }
                else -> cached("event:${event.integer("seq")}") { durableRow(event) }
            }
            if (row != null) output += if (node.partialReplacement) row.copy(detail = joinDetail(
                row.detail, "Replacement intersects unloaded history; load earlier history for exact placement.",
            )) else row
        }
        waiting.forEach { (seq, settlement) ->
            output += attemptRow(settlement.attempt, false).copy(detail = joinDetail(
                settlement.attempt.assembly.detail(), "Awaiting durable ${settlement.type} at seq $seq",
            ))
        }
        return output
    }

    private inline fun cached(key: String, build: () -> TranscriptRow?): TranscriptRow? {
        if (rowCache.containsKey(key)) return rowCache[key]
        return build().also { rowCache[key] = it }
    }

    /** The live attempt is the only row that changes per token; everything above it is reused as-is. */
    private fun withAttempt(durable: List<TranscriptRow>, source: TreeMap<Long, JSONObject>, attempt: Attempt?): List<TranscriptRow> {
        // The durable envelope can precede its end frame. Keep validating the attempt,
        // but show only its durable row; end still has to name that exact seq.
        if (attempt == null || candidate(source, attempt) != null) return durable
        return durable + attemptRow(attempt, true)
    }

    private fun durableRow(event: JSONObject): TranscriptRow? {
        val type = event.string("type")
        val key = "event:${event.integer("seq")}" 
        val data = event.optJSONObject("data")
        return when (type) {
            "user/message", "assistant/message", "system/message", "developer/message" -> {
                val body = event.requiredObject("data")
                val message = if (type == "user/message") body else body.requiredObject("message")
                val role = type.substringBefore('/')
                val parts = content(message.requiredArray("content"))
                val source = message.optJSONObject("source")
                val title = when {
                    body.optBoolean("interrupted") -> "Assistant (interrupted)"
                    role == "system" -> "System instructions"
                    role == "developer" -> "Session update"
                    role == "user" && source?.string("form") == "notice" -> source.string("summary")
                    role == "user" && injectedKind(source) -> when (val kind = source?.string("kind").orEmpty()) {
                        "compact-checkpoint" -> "上下文压缩摘要"
                        "runtime-context" -> "运行时上下文"
                        else -> "插件消息 · ${kind.removePrefix("plugin:")}"
                    }
                    role == "user" && source != null && source.string("kind") !in setOf("", "user") -> source.string("kind")
                    else -> ""
                }
                // Injected context is not a message typed by the human. Keep it expandable.
                // Plugin nudges and compaction checkpoints are written by the runtime, not by the person using the app.
                val contextRecord = role == "user" && (source?.string("form") in setOf("instructions", "catalog", "snapshot", "notice", "recall") || injectedKind(source))
                TranscriptRow(key, if (contextRecord) "notice" else role, if (contextRecord) title.ifBlank { "会话上下文" } else title, parts.text, parts.reasoning,
                    joinDetail(parts.detail, if (contextRecord) "Source: ${json(source)}" else "", if (body.optBoolean("interrupted")) "Interrupted response" else "",
                        body.optJSONObject("usage")?.let { "Usage: ${json(it)}" }.orEmpty()),
                    images = parts.images)
            }
            "assistant/attempt" -> {
                val assembly = Assembly().apply { compact(event.requiredObject("data").requiredArray("stream")) }
                val parts = content(assembly.content())
                TranscriptRow(key, "assistant", "Assistant attempt (not committed)", parts.text, parts.reasoning,
                    joinDetail(parts.detail, assembly.detail()), error = assembly.failed, images = parts.images)
            }
            "turn/end" -> {
                val reason = data?.optJSONObject("reason")
                if (reason?.string("kind") == "completed") null else TranscriptRow(
                    key, "notice", "Turn ${reason?.string("kind") ?: "ended"}",
                    text = reason?.optJSONObject("error")?.string("message") ?: "",
                    detail = json(event.opt("data")), error = reason?.string("kind") == "error",
                )
            }
            in QUIET_EVENTS -> null
            else -> {
                // Extension names are valid, not failures. Preserve opaque data for inspection
                // without showing a fabricated 'unknown/unsupported' warning in the conversation.
                val retrying = type == "llm/retry" || type == "llm/retry-started"
                val failure = !retrying && (data?.opt("error")?.let { it != JSONObject.NULL && it != false && it != "" } == true ||
                    data?.optBoolean("isError") == true || data?.string("kind") == "error" || data?.string("status") == "failed" ||
                    (type == "tool-workflow/agent-end" && data?.string("outcome") == "failed") ||
                    (type == "tool-workflow/run-end" && data?.string("stopReason") == "error") ||
                    (type == "hook/result" && data?.optInt("exitCode", 0) != 0))
                val cancelled = data?.string("outcome") == "cancelled" || data?.string("stopReason") == "cancelled"
                val label = EVENT_LABELS[type] ?: "扩展记录"
                val description = data?.optJSONObject("error")?.string("message").orEmpty()
                    .ifBlank { (data?.opt("error") as? String).orEmpty() }
                    .ifBlank { data?.string("summary").orEmpty() }.ifBlank { data?.string("message").orEmpty() }
                    .ifBlank { data?.string("text").orEmpty() }.ifBlank { data?.string("stderrSummary").orEmpty() }
                    .ifBlank { if (retrying) data?.optJSONObject("failure")?.string("message").orEmpty() else "" }
                TranscriptRow(key, "notice", if (failure) "$label · 失败" else if (cancelled) "$label · 已取消" else label,
                    text = description, detail = json(event), error = failure)
            }
        }
    }

    private fun toolRow(call: JSONObject?, result: JSONObject?): TranscriptRow {
        val callData = call?.requiredObject("data")
        val resultData = result?.requiredObject("data")
        val message = resultData?.requiredObject("message")
        val id = callData?.requiredText("callId") ?: toolId(message!!)
        val name = callData?.requiredText("name") ?: id
        val parts = if (message != null) content(message.requiredArray("content")) else Parts()
        val failure = message?.optBoolean("isError") == true || resultData?.has("error") == true
        return TranscriptRow(
            key = "tool:${(call ?: result!!).integer("seq")}", role = "tool",
            title = "$name — ${if (result == null) "执行中" else if (failure) "失败" else "完成"}",
            text = parts.text, reasoning = parts.reasoning,
            detail = joinDetail("Call ID: $id", callData?.let { "Arguments:\n${it.string("arguments")}" }.orEmpty(),
                parts.detail, resultData?.opt("error")?.let { "Error:\n${json(it)}" }.orEmpty(),
                resultData?.opt("meta")?.let { "Metadata:\n${json(it)}" }.orEmpty()),
            streaming = result == null, error = failure, images = parts.images,
        )
    }

    private fun attemptRow(attempt: Attempt, streaming: Boolean): TranscriptRow {
        val parts = content(attempt.assembly.content())
        return TranscriptRow("attempt:${attempt.id}", "assistant", if (streaming) "Assistant" else "Assistant (settling)",
            parts.text, parts.reasoning, joinDetail(parts.detail, attempt.assembly.detail()),
            streaming, attempt.assembly.failed, parts.images)
    }

    private data class Node(val event: JSONObject, val surface: Boolean, val partialReplacement: Boolean = false)
    private data class Settlement(val attempt: Attempt, val type: String)
    private data class Attempt(
        val id: String, val startedAfterSeq: Long, val turn: Long, val step: Long,
        var nextIndex: Long = 0, val assembly: Assembly = Assembly(),
    ) {
        fun duplicate() = copy(assembly = assembly.duplicate())
    }

    private class Assembly {
        private val blocks = linkedMapOf<Long, JSONObject>()
        private val closed = mutableSetOf<Long>()
        private val opaque = mutableListOf<JSONObject>()
        private var usage: JSONObject? = null
        private var finish: JSONObject? = null
        val failed: Boolean get() = finish?.string("kind") == "error"

        fun duplicate() = Assembly().also { copy ->
            blocks.forEach { (index, block) -> copy.blocks[index] = block.detached() }
            copy.closed.addAll(closed)
            copy.opaque.addAll(opaque)
            copy.usage = usage
            copy.finish = finish
        }

        fun push(chunk: JSONObject) {
            val type = chunk.requiredText("type")
            when (type) {
                "block-start", "block-end", "text-delta", "reasoning-delta", "tool-call-delta" -> {
                    val index = chunk.integer("index")
                    if (index in closed) return // Same tolerance as the canonical BlockAssembler.
                    val blockType = when (type) {
                        "block-start" -> chunk.requiredText("blockType")
                        "block-end" -> chunk.requiredObject("block").requiredText("type")
                        else -> type.removeSuffix("-delta")
                    }
                    val block = blocks.getOrPut(index) { JSONObject().put("type", blockType) }
                    check(block.string("type") == blockType) { "Assistant block type changed at index $index" }
                    when (type) {
                        "block-end" -> { blocks[index] = chunk.requiredObject("block").detached(); closed += index }
                        "text-delta", "reasoning-delta" -> block.put("text", block.string("text") + chunk.textValue("text"))
                        "tool-call-delta" -> {
                            val id = chunk.requiredText("id")
                            check(!block.has("id") || block.string("id") == id) { "Tool call id changed within a block" }
                            block.put("id", id)
                            if (chunk.has("name")) block.put("name", chunk.textValue("name"))
                            block.put("arguments", block.string("arguments") + chunk.textValue("argumentsDelta"))
                        }
                    }
                }
                "usage" -> usage = chunk.requiredObject("usage")
                "finish" -> finish = chunk.requiredObject("reason")
                else -> opaque += chunk.detached()
            }
        }

        /** Packed run indexes identify blocks, NOT dense live frame positions. */
        fun compact(stream: JSONArray): Long {
            var count = 0L
            stream.objectValues().forEach { record ->
                when (val type = record.requiredText("type")) {
                    "chunk" -> { push(record.requiredObject("chunk")); count++ }
                    "text-chunks", "reasoning-chunks", "tool-call-chunks" -> {
                        val index = record.integer("index")
                        val values = record.requiredArray(if (type == "tool-call-chunks") "args" else "texts")
                        check(values.length() > 0) { "Empty compact chunk run" }
                        for (i in 0 until values.length()) {
                            val fragment = values.opt(i) as? String ?: error("Invalid compact delta text")
                            val chunk = JSONObject().put("index", index)
                            if (type == "tool-call-chunks") {
                                chunk.put("type", "tool-call-delta").put("id", record.requiredText("id"))
                                    .put("argumentsDelta", fragment)
                                if (i == 0 && record.has("name")) chunk.put("name", record.textValue("name"))
                            } else chunk.put("type", if (type == "text-chunks") "text-delta" else "reasoning-delta").put("text", fragment)
                            push(chunk)
                            count++
                        }
                    }
                    else -> error("Unsupported compact stream record: $type; reconnect required")
                }
            }
            return count
        }

        fun content() = JSONArray(blocks.values.toList())
        fun detail() = joinDetail(
            usage?.let { "Usage: ${json(it)}" }.orEmpty(),
            finish?.let { "Finish: ${json(it)}" }.orEmpty(),
            opaque.joinToString("\n") { "扩展流记录: ${json(it)}" },
        )
    }

    private data class Parts(
        val text: String = "", val reasoning: String = "", val detail: String = "",
        val images: List<JSONObject> = emptyList(),
    )

    companion object {
        private val ASSISTANT_EVENTS = setOf("assistant/message", "assistant/attempt")
        private val SURFACE_EVENTS = setOf("user/message", "assistant/message", "system/message", "developer/message", "tool/result")
        private val QUIET_EVENTS = setOf("turn/start", "step/start", "step/end", "request/header", "request/context", "session/end-seed", "session/title-llm-request", "web/deepseek-search-llm-request", "session-log-deepseek/delivery-accepted", "tool/ptc-dispatch-start")
        private val EVENT_LABELS = mapOf(
            "model/selection" to "模型选择已更新", "permission/preset" to "会话权限已配置",
            "sandbox/mode" to "沙箱模式已配置", "approval/policy" to "审批策略已配置",
            "agent-preset/selected" to "角色预设已更新", "agent/inbox/spliced" to "消息队列已更新",
            "approval/asked" to "请求工具授权", "approval/decided" to "授权已处理",
            "command/run" to "命令已开始", "command/done" to "命令执行结束",
            "compaction/start" to "正在整理上下文", "compaction/end" to "上下文整理结束",
            "compaction/prune" to "上下文已精简", "compaction/summary" to "上下文摘要",
            "deliverables/presented" to "交付文件", "feedback/message-delete" to "消息反馈已移除",
            "feedback/message-put" to "消息反馈已更新", "feedback/record" to "反馈记录",
            "goal/change" to "任务目标已更新", "hook/invoked" to "扩展动作已开始", "hook/result" to "扩展动作结果",
            "image/offload" to "图片上下文已卸载", "llm/retry" to "模型请求重试", "llm/retry-started" to "正在重试模型请求",
            "plan/mode" to "计划模式已更新", "schedule/change" to "计划安排已更新", "session/title" to "会话标题已更新",
            "subagent/catalog" to "子代理目录已更新", "subagent/descriptor" to "子代理信息已更新", "subagent/model-selection-policy" to "子代理模型策略已更新",
            "team/member" to "团队成员已更新", "team/message/delivered" to "团队消息已送达", "team/message/queued" to "团队消息已排队", "team/task" to "团队任务已更新",
            "todo/write" to "任务清单已更新", "tool-workflow/agent-end" to "工作流代理结束", "tool-workflow/agent-start" to "工作流代理开始",
            "tool-workflow/run-end" to "工作流结束", "tool-workflow/run-start" to "工作流开始", "tool/ptc-dispatch" to "工具调度结果", "workspace/changes" to "工作区变更"
        )

        private fun content(blocks: JSONArray): Parts {
            val text = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            val details = mutableListOf<String>()
            val images = mutableListOf<JSONObject>()
            blocks.objectValues().forEach { block ->
                when (val type = block.requiredText("type")) {
                    "text" -> text += block.string("text")
                    "reasoning" -> reasoning += block.string("text")
                    "image" -> {
                        val attachment = block.optJSONObject("attachment")
                        if (attachment != null) images += attachment.detached()
                        else details += "图片记录（无可下载附件）: ${json(block)}"
                        if (block.optBoolean("offloaded")) details += "Image offloaded from model context (attachment retained)."
                    }
                    "tool-call" -> details += "Tool: ${block.string("name")} (${block.string("id")})\n${block.string("arguments")}"
                    "file" -> details += "File attachment: ${json(block.opt("attachment"))}"
                    "tool-addition", "tool-removal" -> details += "$type: ${block.string("toolName")}" 
                    else -> { block.string("text").takeIf { it.isNotBlank() }?.let { text += it }; details += "扩展内容: ${json(block)}" }
                }
            }
            return Parts(text.joinToString("\n\n"), reasoning.joinToString("\n\n"), details.joinToString("\n\n"), images)
        }

        private fun toolId(message: JSONObject): String = message.optJSONObject("source")?.string("callId")
            ?.takeIf { it.isNotEmpty() } ?: message.requiredText("toolCallId")
        private fun joinDetail(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
        private fun JSONObject.detached() = JSONObject(toString())
        private fun JSONObject.string(key: String) = opt(key) as? String ?: ""
        private fun JSONObject.textValue(key: String) = opt(key) as? String ?: error("Missing or invalid $key")
        private fun JSONObject.requiredText(key: String) = textValue(key).also { check(it.isNotEmpty()) { "Empty $key" } }
        private fun JSONObject.requiredObject(key: String) = optJSONObject(key) ?: error("Missing or invalid $key object")
        private fun JSONObject.requiredArray(key: String) = optJSONArray(key) ?: error("Missing or invalid $key array")
        private fun JSONObject.requiredBoolean(key: String) = opt(key) as? Boolean ?: error("Missing or invalid $key boolean")
        private fun JSONObject.integer(key: String, minimum: Long = 0): Long {
            val number = opt(key) as? Number ?: error("Missing or invalid $key integer; reconnect required")
            val value = number.toDouble()
            check(value.isFinite() && value >= minimum.toDouble() && value <= 9007199254740991.0 && value == number.toLong().toDouble()) {
                "Invalid $key integer; reconnect required"
            }
            return number.toLong()
        }
        private fun JSONArray.objectValues() = (0 until length()).map { optJSONObject(it) ?: error("Invalid JSON object record") }
        private fun json(value: Any?): String = when (value) {
            null, JSONObject.NULL -> ""
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value.toString()
        }
        private fun sameJson(left: Any?, right: Any?): Boolean = when {
            left is JSONObject && right is JSONObject -> left.length() == right.length() && left.keys().asSequence().all {
                right.has(it) && sameJson(left.opt(it), right.opt(it))
            }
            left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all {
                sameJson(left.opt(it), right.opt(it))
            }
            left is Number && right is Number -> left.toDouble() == right.toDouble()
            else -> left == right
        }
    }
}
