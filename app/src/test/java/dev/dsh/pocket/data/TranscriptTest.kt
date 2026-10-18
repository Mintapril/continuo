package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    @Test fun permissionConfigurationIsNotAnUnsupportedError() {
        val transcript = Transcript()
        transcript.accept(snapshot(2, listOf(
            event(0, "permission/preset", obj("value" to "workspace-write")),
            event(1, "sandbox/mode", obj("mode" to "workspace-write")),
            event(2, "approval/policy", obj("policy" to "ask")),
        )))
        assertEquals(3, transcript.rows.size)
        assertTrue(transcript.rows.none { it.error })
        assertEquals("会话权限已配置", transcript.rows[0].title)
        assertTrue(transcript.rows.all { it.detail.isNotBlank() })
    }

    @Test fun snapshotUsesDirectUserAndNestedAssistantMessagesAndRetainsImageMetadata() {
        val attachment = obj("attachmentId" to "image-1", "width" to 80, "height" to 60, "mediaType" to "image/png")
        val user = event(0, "user/message", message("user", text("Question"), obj("type" to "image", "attachment" to attachment)))
        val assistant = assistant(1, "Answer", reasoning = "Thinking")
        val transcript = Transcript()
        transcript.accept(snapshot(1, listOf(user, assistant)))
        assertEquals(1L, transcript.cursor)
        assertEquals(0L, transcript.firstSeq)
        assertFalse(transcript.hasMore)
        assertEquals(listOf("user", "assistant"), transcript.rows.map { it.role })
        assertEquals("Question", transcript.rows[0].text)
        assertEquals("image-1", transcript.rows[0].images.single().getString("attachmentId"))
        assertEquals("Answer", transcript.rows[1].text)
        assertEquals("Thinking", transcript.rows[1].reasoning)
        attachment.put("attachmentId", "mutated")
        assertEquals("image-1", transcript.rows[0].images.single().getString("attachmentId"))
    }

    @Test fun emptySnapshotResetsAllDurableAndLiveState() {
        val transcript = Transcript()
        transcript.accept(snapshot(0, listOf(user(0, "old"))))
        transcript.accept(start(1, after = 0))
        transcript.accept(chunk(2, 0, delta("text-delta", 0, "partial")))
        transcript.accept(snapshot(-1, emptyList(), revision = 44))
        assertTrue(transcript.rows.isEmpty())
        assertNull(transcript.firstSeq)
        assertEquals(-1L, transcript.cursor)
        transcript.accept(start(45, after = -1))
        assertTrue(transcript.rows.single().streaming)
    }

    @Test fun liveChunksKeepBlocksInFirstSeenOrderAndBlockEndIsAuthoritative() {
        val transcript = Transcript()
        transcript.accept(snapshot(-1, emptyList()))
        transcript.accept(start(1))
        transcript.accept(chunk(2, 0, obj("type" to "block-start", "index" to 7, "blockType" to "reasoning")))
        transcript.accept(chunk(3, 1, delta("reasoning-delta", 7, "Think")))
        transcript.accept(chunk(4, 2, delta("text-delta", 2, "A")))
        transcript.accept(chunk(5, 3, delta("text-delta", 2, "B")))
        transcript.accept(chunk(6, 4, obj("type" to "block-end", "index" to 2, "block" to text("Final AB"))))
        transcript.accept(chunk(7, 5, delta("text-delta", 2, "ignored after close")))
        transcript.accept(chunk(8, 6, delta("text-delta", 0, "last")))
        transcript.accept(chunk(9, 7, obj("type" to "usage", "usage" to obj("outputTokens" to 12))))
        transcript.accept(chunk(10, 8, obj("type" to "finish", "reason" to obj("kind" to "stop"))))
        val row = transcript.rows.single()
        assertEquals("Final AB\n\nlast", row.text)
        assertEquals("Think", row.reasoning)
        assertTrue(row.detail.contains("outputTokens"))
        assertTrue(row.detail.contains("stop"))
        assertTrue(row.streaming) // finish chunk is not the assistant-stream end marker.
        assertEquals(-1L, transcript.cursor)
    }

    @Test fun compactReconnectRestoresIndependentBlockIndexesAndDenseFrameCount() {
        val active = obj("attemptId" to "attempt-1", "startedAfterSeq" to 0, "turn" to 1, "step" to 1,
            "nextIndex" to 8, "stream" to array(
                raw(obj("type" to "block-start", "index" to 11, "blockType" to "reasoning")),
                obj("type" to "reasoning-chunks", "index" to 11, "texts" to array("a", "b")),
                obj("type" to "text-chunks", "index" to 4, "texts" to array("hello", " ", "world")),
                obj("type" to "tool-call-chunks", "index" to 19, "id" to "call-1", "name" to "read",
                    "args" to array("{\"path\":", "\"x\"}")),
            ))
        val transcript = Transcript()
        transcript.accept(snapshot(0, listOf(user(0, "Q")), revision = 21, active = active))
        assertEquals("ab", transcript.rows.last().reasoning)
        assertEquals("hello world", transcript.rows.last().text)
        assertTrue(transcript.rows.last().detail.contains("read (call-1)"))
        assertTrue(transcript.rows.last().detail.contains("{\"path\":\"x\"}"))
        transcript.accept(chunk(22, 8, delta("text-delta", 4, "!")))
        assertEquals("hello world!", transcript.rows.last().text)
    }

    @Test fun liveToolCallsAssembleRawArgumentsAndPreserveImageBlockEnd() {
        val transcript = Transcript()
        transcript.accept(snapshot(-1, emptyList()))
        transcript.accept(start(1))
        transcript.accept(chunk(2, 0, obj("type" to "tool-call-delta", "index" to 2, "id" to "c", "name" to "search", "argumentsDelta" to "{\"q\":")))
        transcript.accept(chunk(3, 1, obj("type" to "tool-call-delta", "index" to 2, "id" to "c", "argumentsDelta" to "\"Kotlin\"}")))
        transcript.accept(chunk(4, 2, obj("type" to "block-end", "index" to 4,
            "block" to obj("type" to "image", "attachment" to obj("attachmentId" to "img")))))
        assertTrue(transcript.rows.single().detail.contains("{\"q\":\"Kotlin\"}"))
        assertEquals("img", transcript.rows.single().images.single().getString("attachmentId"))
    }

    @Test fun durableBeforeEndHidesLiveDuplicateAndSettlesExactSeq() {
        val transcript = streamingTranscript()
        transcript.accept(assistant(1, "durable"))
        assertEquals(listOf("Q", "durable"), transcript.rows.map { it.text })
        assertFalse(transcript.rows.any { it.streaming })
        transcript.accept(end(3, 1, seq = 1))
        assertEquals(listOf("Q", "durable"), transcript.rows.map { it.text })
        transcript.accept(assistant(1, "durable"))
        assertEquals(2, transcript.rows.size)
    }

    @Test fun endBeforeDurableRetainsPendingContentUntilTheNamedSeqArrives() {
        val transcript = streamingTranscript()
        transcript.accept(end(3, 1, seq = 2))
        assertEquals("partial", transcript.rows.last().text)
        assertFalse(transcript.rows.last().streaming)
        assertTrue(transcript.rows.last().detail.contains("seq 2"))
        transcript.accept(event(1, "step/end", obj("turn" to 1, "step" to 1)))
        assertEquals("partial", transcript.rows.last().text)
        transcript.accept(assistant(2, "durable"))
        assertEquals(listOf("Q", "durable"), transcript.rows.map { it.text })
        assertFalse(transcript.rows.any { it.key.startsWith("attempt:") })
    }

    @Test fun sameStepSettlementMustUseExactSeqNotAnyNearbyAssistantEvent() {
        val transcript = streamingTranscript()
        transcript.accept(assistant(1, "durable"))
        illegalState { transcript.accept(end(3, 1, seq = 2)) }
        assertEquals("durable", transcript.rows.last().text)
        transcript.accept(end(3, 1, seq = 1)) // Rejected frame did not advance revision.
    }

    @Test fun pendingSettlementRejectsWrongEventTypeAndWrongStepWithoutAdvancingCursor() {
        val transcript = streamingTranscript()
        transcript.accept(end(3, 1, seq = 1))
        illegalState { transcript.accept(user(1, "not assistant")) }
        assertEquals(0L, transcript.cursor)
        illegalState { transcript.accept(assistant(1, "wrong step", step = 2)) }
        assertEquals(0L, transcript.cursor)
        transcript.accept(assistant(1, "correct"))
        assertEquals("correct", transcript.rows.last().text)
    }

    @Test fun abandonmentRemovesOnlyLiveAttempt() {
        val transcript = streamingTranscript()
        transcript.accept(end(3, 1, abandoned = true))
        assertEquals(listOf("Q"), transcript.rows.map { it.text })
        transcript.accept(start(4, after = 0, id = "attempt-2"))
        assertTrue(transcript.rows.last().streaming)
    }

    @Test fun durableAttemptRestoresCompactFailureAndDeduplicatesLiveAttempt() {
        val transcript = streamingTranscript()
        transcript.accept(event(1, "assistant/attempt", obj("turn" to 1, "step" to 1, "stream" to array(
            obj("type" to "text-chunks", "index" to 0, "texts" to array("failed prefix")),
            raw(obj("type" to "finish", "reason" to obj("kind" to "error", "failure" to obj("message" to "offline", "code" to "NETWORK")))),
        ))))
        transcript.accept(end(3, 1, seq = 1, eventType = "assistant/attempt"))
        assertEquals(2, transcript.rows.size)
        assertEquals("failed prefix", transcript.rows.last().text)
        assertTrue(transcript.rows.last().error)
        assertTrue(transcript.rows.last().detail.contains("offline"))
    }

    @Test fun prependHistoryPreservesLiveCursorAttemptAndCurrentRows() {
        val transcript = Transcript()
        transcript.accept(snapshot(3, listOf(user(2, "new question"), assistant(3, "new answer")), more = true))
        transcript.accept(start(1, after = 3, step = 2))
        transcript.accept(chunk(2, 0, delta("text-delta", 0, "live")))
        transcript.prepend(page(listOf(user(0, "old question"), assistant(1, "old answer"))))
        assertEquals(3L, transcript.cursor)
        assertEquals(0L, transcript.firstSeq)
        assertFalse(transcript.hasMore)
        assertEquals(listOf("old question", "old answer", "new question", "new answer", "live"), transcript.rows.map { it.text })
        transcript.accept(chunk(3, 1, delta("text-delta", 0, " continues")))
        assertEquals("live continues", transcript.rows.last().text)
    }

    @Test fun overlappingPageIsIdempotentAndCannotRewriteDurableEvents() {
        val transcript = Transcript()
        transcript.accept(snapshot(1, listOf(user(0, "Q"), assistant(1, "A"))))
        transcript.prepend(page(listOf(user(0, "Q"))))
        assertEquals(2, transcript.rows.size)
        illegalState { transcript.prepend(page(listOf(user(0, "changed")))) }
        assertEquals("Q", transcript.rows.first().text)
    }

    @Test fun surfaceReplacementUsesSurfaceOrderRatherThanNumericRange() {
        val firstReplacement = user(3, "rewritten first", replacement(0, 0))
        val secondReplacement = user(4, "summary", replacement(3, 1))
        val transcript = Transcript()
        transcript.accept(snapshot(4, listOf(user(0, "first"), assistant(1, "second"), user(2, "third"), firstReplacement, secondReplacement)))
        assertEquals(listOf("summary", "third"), transcript.rows.map { it.text })
        assertEquals(0L, transcript.firstSeq) // Shadowed raw events were not removed.
    }

    @Test fun laterHistoryReplaysReplacementWithoutResurrectingShadowedRows() {
        val transcript = Transcript()
        transcript.accept(snapshot(3, listOf(user(2, "keep"), user(3, "summary", replacement(0, 1))), more = true))
        assertTrue(transcript.rows.first().detail.contains("unloaded history"))
        transcript.prepend(page(listOf(user(0, "shadowed user"), assistant(1, "shadowed assistant"))))
        assertEquals(listOf("summary", "keep"), transcript.rows.map { it.text })
        assertFalse(transcript.rows.first().detail.contains("unloaded history"))
    }

    @Test fun toolCallAndResultBecomeOneUsefulExpandableCard() {
        val transcript = Transcript()
        transcript.accept(snapshot(1, listOf(toolCall(0), toolResult(1, failed = true))))
        val row = transcript.rows.single()
        assertEquals("tool", row.role)
        assertTrue(row.title.contains("read"))
        assertTrue(row.title.contains("失败"))
        assertEquals("result text", row.text)
        assertTrue(row.detail.contains("Arguments:"))
        assertTrue(row.detail.contains("path"))
        assertTrue(row.detail.contains("c1"))
        assertTrue(row.detail.contains("Denied"))
        assertTrue(row.error)
    }

    @Test fun nonSurfaceToolResultAndOrphanResultStillRender() {
        val result = toolResult(1).apply { getJSONObject("event").remove("surfaceOp") }
        val transcript = Transcript()
        transcript.accept(snapshot(1, listOf(toolCall(0), result)))
        assertEquals("result text", transcript.rows.single().text)
        transcript.accept(snapshot(1, listOf(toolResult(1)), more = true))
        assertEquals("tool", transcript.rows.single().role)
        assertTrue(transcript.rows.single().detail.contains("c1"))
    }

    @Test fun loadingToolCallBeforeExistingResultMergesInsteadOfDuplicating() {
        val transcript = Transcript()
        transcript.accept(snapshot(1, listOf(toolResult(1)), more = true))
        transcript.prepend(page(listOf(toolCall(0))))
        assertEquals(1, transcript.rows.size)
        assertTrue(transcript.rows.single().title.contains("read"))
    }

    @Test fun unknownRequiredSurfaceAndContentAreVisibleAndIgnorableMetadataIsOpaque() {
        val unknown = event(0, "plugin/future", obj("payload" to "must not disappear"), "append")
        val ignorable = event(1, "plugin/audit", obj("summary" to "audit"), obj("op" to "delete-everything")).apply {
            getJSONObject("event").put("ignorable", true)
        }
        val transcript = Transcript()
        transcript.accept(snapshot(2, listOf(unknown, ignorable,
            event(2, "user/message", message("user", obj("type" to "future-block", "payload" to "opaque"))))))
        assertEquals(3, transcript.rows.size)
        assertFalse(transcript.rows[0].error)
        assertEquals("扩展记录", transcript.rows[0].title)
        assertTrue(transcript.rows[0].detail.contains("must not disappear"))
        assertFalse(transcript.rows[1].error)
        assertEquals("audit", transcript.rows[1].text)
        assertEquals("", transcript.rows[2].text)
        assertTrue(transcript.rows[2].detail.contains("opaque"))
        transcript.accept(event(3, "plugin/new-surface-op", obj(), obj("op" to "future")))
        assertEquals("扩展记录", transcript.rows.last().title)
        assertTrue(transcript.rows.none { it.error })
    }

    @Test fun opaqueExtensionCannotReplaceExistingConversation() {
        val transcript = Transcript()
        transcript.accept(snapshot(2, listOf(user(0, "Keep question"), assistant(1, "Keep answer"),
            event(2, "plugin/extension", obj("payload" to "preserved"), obj("op" to "replace", "startSeq" to 0, "endSeq" to 1)))))
        assertEquals(listOf("Keep question", "Keep answer", ""), transcript.rows.map { it.text })
        assertTrue(transcript.rows.last().detail.contains("preserved"))
        assertEquals(2L, transcript.cursor)
    }

    @Test fun knownFailuresRemainVisibleAndRetryIsNotTerminalFailure() {
        val transcript = Transcript()
        val records = listOf(
            event(0, "command/done", obj("kind" to "error", "text" to "command failed")),
            event(1, "compaction/end", obj("error" to "compaction failed")),
            event(2, "tool/ptc-dispatch", obj("isError" to true)),
            event(3, "tool-workflow/agent-end", obj("outcome" to "failed")),
            event(4, "tool-workflow/run-end", obj("stopReason" to "error")),
            event(5, "hook/result", obj("exitCode" to 3, "stderrSummary" to "hook failed")),
            event(6, "llm/retry", obj("failure" to obj("message" to "temporary failure"))),
            event(7, "tool-workflow/run-end", obj("stopReason" to "cancelled")),
            event(8, "plugin/custom", obj("isError" to true, "message" to "extension failed")),
        )
        transcript.accept(snapshot(8, records))
        assertTrue(transcript.rows.take(6).all { it.error })
        assertEquals("command failed", transcript.rows[0].text)
        assertEquals("compaction failed", transcript.rows[1].text)
        assertFalse(transcript.rows[6].error)
        assertEquals("temporary failure", transcript.rows[6].text)
        assertFalse(transcript.rows[7].error)
        assertTrue(transcript.rows[7].title.contains("已取消"))
        assertTrue(transcript.rows[8].error)
    }

    @Test fun runtimeInjectedUserMessagesAreFoldedNoticesNotUserWords() {
        val transcript = Transcript()
        transcript.accept(snapshot(3, listOf(
            event(0, "user/message", message("user", text("hello")).put("source", obj("kind" to "user"))),
            event(1, "user/message", message("user", text("efficiency nudge")).put("source", obj("kind" to "plugin:acp-nudge"))),
            event(2, "user/message", message("user", text("summary")).put("source", obj("kind" to "compact-checkpoint", "compactionId" to "c"))),
            event(3, "user/message", message("user", text("ctx")).put("source", obj("kind" to "runtime-context", "form" to "snapshot"))),
        )))
        assertEquals(listOf("user", "notice", "notice", "notice"), transcript.rows.map { it.role })
        assertEquals("插件消息 · acp-nudge", transcript.rows[1].title)
        assertEquals("上下文压缩摘要", transcript.rows[2].title)
        assertTrue(transcript.rows[1].detail.contains("Source:"))
        assertEquals(listOf("message", "activity"), chatEntries(transcript.rows).map { it.kind })
    }

    @Test fun noticesAndTurnErrorsAreAvailableWithoutLifecycleNoise() {
        val notice = message("user", text("full context")).put("source", obj("kind" to "plugin", "form" to "notice", "summary" to "Workspace changed"))
        val transcript = Transcript()
        transcript.accept(snapshot(3, listOf(
            event(0, "turn/start", obj("turn" to 1)),
            event(1, "user/message", notice),
            event(2, "step/end", obj("turn" to 1, "step" to 1)),
            event(3, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "error", "error" to obj("message" to "Failure")))),
        )))
        assertEquals(2, transcript.rows.size)
        assertEquals("Workspace changed", transcript.rows.first().title)
        assertEquals("notice", transcript.rows.first().role)
        assertEquals("full context", transcript.rows.first().text)
        assertTrue(transcript.rows.first().detail.contains("Source:"))
        assertEquals("Failure", transcript.rows.last().text)
        assertTrue(transcript.rows.last().error)
    }

    @Test fun missingAndGappedDurableSeqsForceReconnect() {
        val transcript = Transcript()
        transcript.accept(snapshot(0, listOf(user(0, "Q"))))
        illegalState { transcript.accept(user(2, "gap")) }
        illegalState { transcript.accept(user(1, "missing").apply { getJSONObject("event").remove("seq") }) }
        illegalState { transcript.accept(user(1, "fractional").apply { getJSONObject("event").put("seq", 1.5) }) }
        assertEquals(0L, transcript.cursor)
        transcript.accept(user(1, "correct"))
        assertEquals(1L, transcript.cursor)
    }

    @Test fun historyAndSnapshotGapsOrMissingPrefixForceReconnect() {
        val transcript = Transcript()
        illegalState { transcript.accept(snapshot(2, listOf(user(0, "a"), user(2, "b")))) }
        illegalState { transcript.accept(snapshot(1, listOf(user(0, "a")))) }
        illegalState { transcript.accept(snapshot(2, listOf(user(2, "a")))) }
        transcript.accept(snapshot(2, listOf(user(2, "a")), more = true))
        illegalState { transcript.prepend(page(listOf(user(0, "missing 1")))) }
        assertEquals(2L, transcript.firstSeq)
    }

    @Test fun missingRevisionDenseIndexAndWrongAttemptForceReconnectAtomically() {
        val transcript = streamingTranscript()
        illegalState { transcript.accept(chunk(4, 1, delta("text-delta", 0, "revision gap"))) }
        illegalState { transcript.accept(chunk(3, 2, delta("text-delta", 0, "index gap"))) }
        illegalState { transcript.accept(chunk(3, 1, delta("text-delta", 0, "wrong id"), id = "other")) }
        illegalState { transcript.accept(chunk(3, 1, delta("text-delta", 0, "missing revision")).apply { getJSONObject("frame").remove("revision") }) }
        illegalState { transcript.accept(chunk(3, 1, delta("text-delta", 0, "missing index")).apply { getJSONObject("frame").remove("index") }) }
        illegalState { transcript.accept(chunk(3, 1, obj("type" to "text-delta", "text" to "missing block index"))) }
        assertEquals("partial", transcript.rows.last().text)
        transcript.accept(chunk(3, 1, delta("text-delta", 0, " ok")))
        assertEquals("partial ok", transcript.rows.last().text)
        illegalState { transcript.accept(end(4, 1, abandoned = true)) }
        transcript.accept(end(4, 2, abandoned = true))
    }

    @Test fun replacementLeavesNonSurfaceToolAuditVisible() {
        val transcript = Transcript()
        transcript.accept(snapshot(4, listOf(user(0, "old question"), toolCall(1), toolResult(2),
            assistant(3, "old answer"), user(4, "summary", replacement(0, 3)))))
        assertEquals(listOf("user", "tool"), transcript.rows.map { it.role })
        assertEquals("summary", transcript.rows.first().text)
        assertTrue(transcript.rows.last().detail.contains("Arguments:"))
        assertFalse(transcript.rows.any { it.text == "old answer" || it.text == "result text" })
    }

    @Test fun systemAndDeveloperSurfaceMessagesAndModelAuditRemainReadable() {
        val transcript = Transcript()
        transcript.accept(snapshot(2, listOf(
            event(0, "system/message", obj("message" to message("system", text("instructions")))),
            event(1, "developer/message", obj("message" to message("developer", obj("type" to "tool-removal", "toolName" to "old-tool")))),
            event(2, "model/selection", obj("model" to "example")),
        )))
        assertEquals(listOf("system", "developer", "notice"), transcript.rows.map { it.role })
        assertEquals("instructions", transcript.rows[0].text)
        assertTrue(transcript.rows[1].detail.contains("old-tool"))
        assertEquals("模型选择已更新", transcript.rows[2].title)
        assertFalse(transcript.rows[2].error)
    }

    @Test fun compactCountMismatchAndMissingCompactIndexForceReconnect() {
        val transcript = Transcript()
        val active = obj("attemptId" to "attempt-1", "startedAfterSeq" to -1, "turn" to 1, "step" to 1,
            "nextIndex" to 2, "stream" to array(obj("type" to "text-chunks", "index" to 0, "texts" to array("one"))))
        illegalState { transcript.accept(snapshot(-1, emptyList(), active = active)) }
        active.put("nextIndex", 1)
        active.getJSONArray("stream").getJSONObject(0).remove("index")
        illegalState { transcript.accept(snapshot(-1, emptyList(), active = active)) }
        assertTrue(transcript.rows.isEmpty())
    }

    private fun streamingTranscript() = Transcript().apply {
        accept(snapshot(0, listOf(user(0, "Q"))))
        accept(start(1, after = 0))
        accept(chunk(2, 0, delta("text-delta", 0, "partial")))
    }

    @Test fun streamingIntoALongTranscriptStaysCheapAndReusesDurableRows() {
        val records = (0 until 1500L).map { if (it % 2 == 0L) user(it, "question $it") else assistant(it, "answer $it") }
        val transcript = Transcript()
        transcript.accept(snapshot(1499, records))
        val before = transcript.rows
        transcript.accept(start(1, after = 1499))
        val started = System.nanoTime()
        for (i in 0 until 3000L) {
            transcript.accept(chunk(2 + i, i, delta("text-delta", 0, "token ")))
            if (i % 40 == 0L) transcript.rows // the painter reads about every 80 ms
        }
        val rows = transcript.rows
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(1501, rows.size)
        assertTrue(rows.last().streaming)
        assertTrue(rows.last().text.startsWith("token token"))
        for (i in 0 until 1500) assertSame(before[i], rows[i]) // durable rows are reused, not rebuilt per token
        assertTrue("3000 chunks over 1500 rows took ${elapsedMs}ms", elapsedMs < 4000)
    }

    private fun obj(vararg entries: Pair<String, Any?>) = JSONObject().apply { entries.forEach { (key, value) -> put(key, value) } }
    private fun array(vararg entries: Any) = JSONArray(entries.toList())
    private fun text(value: String) = obj("type" to "text", "text" to value)
    private fun message(role: String, vararg blocks: JSONObject) = obj("role" to role, "content" to array(*blocks), "source" to obj("kind" to role))
    private fun event(seq: Long, type: String, data: JSONObject, operation: Any? = null): JSONObject {
        val value = obj("seq" to seq, "time" to (1000 + seq), "type" to type, "data" to data)
        if (operation != null) value.put("surfaceOp", operation)
        else if (type in setOf("user/message", "assistant/message", "system/message", "developer/message", "tool/result")) value.put("surfaceOp", "append")
        return obj("type" to "event", "event" to value)
    }
    private fun user(seq: Long, value: String, operation: Any = "append") = event(seq, "user/message", message("user", text(value)), operation)
    private fun assistant(seq: Long, value: String, reasoning: String = "", step: Long = 1) = event(seq, "assistant/message",
        obj("turn" to 1, "step" to step, "message" to message("assistant", text(value), obj("type" to "reasoning", "text" to reasoning)), "stream" to array()))
    private fun snapshot(cursor: Long, records: List<JSONObject>, more: Boolean = false, revision: Long = 0, active: JSONObject? = null): JSONObject {
        val baseline = obj("revision" to revision)
        if (active != null) baseline.put("activeAttempt", active)
        return obj("type" to "snapshot", "cursor" to cursor, "records" to JSONArray(records), "hasMore" to more, "assistantStream" to baseline)
    }
    private fun page(records: List<JSONObject>, more: Boolean = false) = obj("records" to JSONArray(records), "hasMore" to more)
    private fun start(revision: Long, after: Long = -1, id: String = "attempt-1", step: Long = 1) = obj("type" to "assistant-stream", "frame" to
        obj("type" to "start", "attemptId" to id, "revision" to revision, "startedAfterSeq" to after, "turn" to 1, "step" to step))
    private fun chunk(revision: Long, index: Long, chunk: JSONObject, id: String = "attempt-1") = obj("type" to "assistant-stream", "frame" to
        obj("type" to "chunk", "attemptId" to id, "revision" to revision, "index" to index, "time" to 1000, "chunk" to chunk))
    private fun end(revision: Long, index: Long, seq: Long = 0, abandoned: Boolean = false, eventType: String = "assistant/message") = obj("type" to "assistant-stream", "frame" to
        obj("type" to "end", "attemptId" to "attempt-1", "revision" to revision, "index" to index, "outcome" to
            if (abandoned) obj("kind" to "abandoned") else obj("kind" to "committed", "seq" to seq, "eventType" to eventType)))
    private fun delta(type: String, index: Long, value: String) = obj("type" to type, "index" to index, "text" to value)
    private fun raw(chunk: JSONObject) = obj("type" to "chunk", "time" to 1000, "chunk" to chunk)
    private fun replacement(start: Long, end: Long) = obj("op" to "replace", "startSeq" to start, "endSeq" to end)
    private fun toolCall(seq: Long) = event(seq, "tool/call", obj("turn" to 1, "step" to 1, "callId" to "c1", "name" to "read", "arguments" to "{\"path\":\"test.txt\"}"))
    private fun toolResult(seq: Long, failed: Boolean = false): JSONObject {
        val body = message("tool", text("result text")).put("source", obj("kind" to "tool", "callId" to "c1")).put("isError", failed)
        val data = obj("turn" to 1, "step" to 1, "message" to body)
        if (failed) data.put("error", obj("name" to "Denied", "code" to "ACCESS", "reason" to "Not allowed"))
        return event(seq, "tool/result", data)
    }
    private fun illegalState(block: () -> Unit) {
        try { block(); fail("Expected IllegalStateException") } catch (_: IllegalStateException) { /* Expected reconnect signal. */ }
    }
}
