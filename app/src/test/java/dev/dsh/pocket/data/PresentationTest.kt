package dev.dsh.pocket.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    @Test fun permissionsUseChineseRegardlessOfServerEnglishLabels() {
        assertEquals("只读", permissionLabel("read-only"))
        assertEquals("工作区读写", permissionLabel("workspace-write"))
        assertEquals("完全访问", permissionLabel("danger-full-access"))
        listOf("read-only", "workspace-write", "danger-full-access").forEach {
            assertFalse(permissionDescription(it).contains(it))
        }
    }
    @Test fun workspaceOnlyChangesBeforeSessionCreationAndSubmission() {
        assertTrue(canChooseWorkspace(null, false, false, false))
        assertFalse(canChooseWorkspace("existing-session", false, false, false))
        assertFalse(canChooseWorkspace(null, true, false, false))
        assertFalse(canChooseWorkspace(null, false, true, false))
        assertFalse(canChooseWorkspace(null, false, false, true))
    }
    @Test fun providerIdDisambiguatesIdenticalModelIds() {
        val first = modelIdentity(obj("provider" to "provider-a", "model" to "same-model"))
        val second = modelIdentity(obj("provider" to "provider-b", "model" to "same-model"))
        assertNotEquals(first.label, second.label)
        assertEquals("provider-a · same-model", first.label)
    }
    @Test fun groupingIsCompactButNeverReordersToolsAcrossMessages() {
        val rows = listOf(TranscriptRow("t1", "tool"), TranscriptRow("t2", "tool"),
            TranscriptRow("a", "assistant", text = "reply"), TranscriptRow("t3", "tool"),
            TranscriptRow("n", "notice"), TranscriptRow("bad", "notice", error = true))
        val grouped = chatEntries(rows)
        assertEquals(listOf("tools", "message", "tools", "activity", "message"), grouped.map { it.kind })
        assertEquals(listOf("t1", "t2"), grouped[0].rows.map { it.key })
        assertEquals(rows, grouped.flatMap { it.rows })
    }
    private fun turn(prefix: String) = listOf(
        TranscriptRow("$prefix-u", "user", text = "do it"),
        TranscriptRow("$prefix-a1", "assistant", text = "let me look", reasoning = "plan"),
        TranscriptRow("$prefix-t1", "tool"), TranscriptRow("$prefix-t2", "tool"),
        TranscriptRow("$prefix-n", "notice"),
        TranscriptRow("$prefix-a2", "assistant", text = "done", reasoning = "check"))

    @Test fun aFinishedTurnKeepsOnlyTheQuestionAndTheAnswer() {
        val folded = foldedEntries(turn("x"), running = false)
        assertEquals(listOf("message", "process", "message"), folded.map { it.kind })
        assertEquals(listOf("x-a1", "x-t1", "x-t2", "x-n", "x-a2"), folded[1].rows.map { it.key.removeSuffix(":thinking") })
        val answer = folded[2].rows.single()
        assertEquals("done", answer.text); assertEquals("", answer.reasoning)
        assertEquals("check", folded[1].rows.last().reasoning); assertEquals("", folded[1].rows.last().text)
    }
    @Test fun theTurnStillRunningStaysUnfolded() {
        val rows = turn("a") + turn("b")
        val folded = foldedEntries(rows, running = true)
        assertEquals(listOf("message", "process", "message", "message", "message", "tools", "activity", "message"), folded.map { it.kind })
        assertEquals(chatEntries(turn("b")), folded.drop(3))
    }
    @Test fun failuresStayVisibleAndTheLastAnswerWins() {
        val rows = listOf(TranscriptRow("u", "user", text = "q"), TranscriptRow("t", "tool"),
            TranscriptRow("bad", "notice", error = true), TranscriptRow("a1", "assistant", text = "first"),
            TranscriptRow("t2", "tool"), TranscriptRow("a2", "assistant", text = "last"))
        val folded = foldedEntries(rows, running = false)
        assertEquals(listOf("u", "process:t", "bad", "a2"), folded.map { it.key })
        assertEquals(listOf("t", "a1", "t2"), folded[1].rows.map { it.key })
    }
    @Test fun anAnswerWithOnlyItsOwnThinkingIsNotFoldedAgain() {
        val rows = listOf(TranscriptRow("u", "user", text = "hi"), TranscriptRow("a", "assistant", text = "hello", reasoning = "greet"))
        assertEquals(chatEntries(rows), foldedEntries(rows, running = false))
    }
    @Test fun aTurnWithoutAnAnswerFoldsItsStepsAndKeepsTheQuestion() {
        val rows = listOf(TranscriptRow("u", "user", text = "q"), TranscriptRow("t", "tool"), TranscriptRow("t2", "tool"))
        val folded = foldedEntries(rows, running = false)
        assertEquals(listOf("message", "process"), folded.map { it.kind })
        assertEquals(listOf("t", "t2"), folded[1].rows.map { it.key })
    }
    @Test fun foldingNeverDropsARow() {
        val rows = turn("a") + turn("b") + listOf(TranscriptRow("c-u", "user", text = "tail"))
        val kept = foldedEntries(rows, running = false).flatMap { it.rows }.map { it.key.removeSuffix(":thinking") }
        assertEquals(rows.map { it.key }.toSet(), kept.toSet())
    }
    @Test fun toolArgumentsNeverHideOutput() {
        val detail = transcriptDetail(TranscriptRow("tool", "tool", text = "actual output", detail = "arguments"))
        assertTrue(detail.contains("actual output")); assertTrue(detail.contains("arguments"))
    }
    @Test fun singleSelectCustomOverridesOptionsButMultiSelectCombines() {
        val questions = listOf(obj("id" to "one"), obj("id" to "many", "multiSelect" to true))
        val result = questionAnswers(questions, mapOf("one" to setOf("A"), "many" to setOf("B", "C")), mapOf("one" to "custom", "many" to "extra")).objects("answers")
        assertEquals(0, result[0].getJSONArray("selected").length())
        assertEquals("custom", result[0].getString("custom"))
        assertEquals(2, result[1].getJSONArray("selected").length())
        assertEquals("extra", result[1].getString("custom"))
    }
    @Test fun blankCustomIsOmittedAndQuestionIdsPreserved() {
        val result = questionAnswers(listOf(obj("id" to "original")), mapOf("original" to setOf("yes")), mapOf("original" to "  ")).objects("answers").single()
        assertEquals("original", result.getString("id")); assertFalse(result.has("custom"))
        assertEquals("yes", result.getJSONArray("selected").getString(0))
    }
    @Test fun onlyContinuedQuestionsAreOfferedForLateAnswer() {
        val projection = obj("userQuestions" to obj("active" to JSONArray(listOf(
            obj("callId" to "live", "state" to "open"), obj("callId" to "late", "state" to "continued")))))
        assertEquals("late", continuedQuestions(projection).single().getString("callId"))
        assertTrue(continuedQuestions(obj()).isEmpty())
    }
}
