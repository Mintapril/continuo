package dev.dsh.pocket.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ModesTest {
    private fun session(id: String, preset: String? = "standard", running: Boolean = false, blank: Boolean = false, open: Boolean = false) = JSONObject()
        .put("sessionId", id).put("running", running).put("blank", blank)
        .put("projections", JSONObject().put("values", JSONObject().apply {
            if (preset != null) put("agentPreset", preset)
            if (open) put("userQuestions", JSONObject().put("active", org.json.JSONArray().put(JSONObject().put("callId", "c").put("state", "open"))))
        }))

    @Test fun onlyCoworkPresetMapsToCowork() {
        assertEquals("cowork-phone", TaskMode.COWORK.preset)
        assertEquals(TaskMode.COWORK, sessionMode(session("a", "cowork")))
        assertEquals(TaskMode.COWORK, sessionMode(session("new", "cowork-phone")))
        assertNull(presetTag(session("new", "cowork-phone")))
        assertEquals(TaskMode.CODE, sessionMode(session("b", "standard")))
        assertEquals(TaskMode.CODE, sessionMode(session("c", "ptc")))
        assertEquals(TaskMode.CODE, sessionMode(session("d", null)))
        assertEquals("ptc", presetTag(session("c", "ptc")))
        assertNull(presetTag(session("b", "standard")))
        assertNull(presetTag(session("a", "cowork")))
    }

    @Test fun brokenPresetsDoNotEnableTaskModes() {
        assertNull(availablePresetIds(null))
        val broken = JSONObject("""{"presets":[{"id":"standard"},{"id":"cowork-phone","broken":{"message":"missing secret"}}]}""")
        assertEquals(setOf("standard"), availablePresetIds(broken))
        val ready = JSONObject("""{"presets":[{"id":"standard"},{"id":"cowork-phone"},{"id":"","broken":null}]}""")
        assertEquals(setOf("standard", "cowork-phone"), availablePresetIds(ready))
    }

    @Test fun filtersSeparateArchivedRunningAndInput() {
        val archived = setOf("z")
        assertTrue(matchesFilter(session("z"), SessionFilter.ARCHIVED, archived, emptySet()))
        assertFalse(matchesFilter(session("z"), SessionFilter.ALL, archived, emptySet()))
        assertTrue(matchesFilter(session("r", running = true), SessionFilter.WORKING, archived, emptySet()))
        assertFalse(matchesFilter(session("r", running = true), SessionFilter.COMPLETED, archived, emptySet()))
        assertTrue(matchesFilter(session("q", open = true), SessionFilter.NEEDS_INPUT, archived, emptySet()))
        assertTrue(matchesFilter(session("p"), SessionFilter.NEEDS_INPUT, archived, setOf("p")))
        assertFalse(matchesFilter(session("b", blank = true), SessionFilter.COMPLETED, archived, emptySet()))
        assertTrue(matchesFilter(session("d"), SessionFilter.COMPLETED, archived, emptySet()))
    }

    @Test fun toolTargetPrefersCommandAndPathAndTruncates() {
        val command = TranscriptRow("t1", "tool", "bash — 完成", detail = "Call ID: x\nArguments:\n{\"command\":\"\\n  ls -la /workspace\\nmore\"}")
        assertEquals("ls -la /workspace", toolTarget(command))
        val path = TranscriptRow("t2", "tool", "read — 完成", detail = "Arguments:\n{\"file_path\":\"/a/b.kt\",\"limit\":20}")
        assertEquals("/a/b.kt", toolTarget(path))
        val queries = TranscriptRow("t3", "tool", "web_search — 完成", detail = "Arguments:\n{\"queries\":[\"one\",\"two\"]}")
        assertEquals("one", toolTarget(queries))
        val long = TranscriptRow("t4", "tool", "bash — 完成", detail = "Arguments:\n{\"command\":\"${"x".repeat(300)}\"}")
        assertEquals(160, toolTarget(long).length)
        assertEquals("", toolTarget(TranscriptRow("t5", "tool", "bash — 完成", detail = "Arguments:\nnot json")))
        assertEquals("bash", toolName(command))
    }

    @Test fun toolLabelsCoverCommonFamilies() {
        assertEquals("terminal", toolLabel("bash").kind)
        assertEquals("web", toolLabel("web_search").kind)
        assertEquals("write", toolLabel("edit").kind)
        assertEquals("read", toolLabel("read").kind)
        assertEquals("search", toolLabel("grep").kind)
        assertEquals("question", toolLabel("ask_user_question").kind)
        assertEquals("generic", toolLabel("zzz").kind)
    }

    @Test fun modelChoiceKeepsProviderVisibleAndFallsBackToIds() {
        val catalog = JSONObject("""{"groups":[{"id":"p1","name":"Provider One","models":[{"id":"m","name":"Model M","reasoning":{"efforts":[{"id":"high","name":"High"}]}}]}]}""")
        val choice = modelChoice(catalog, JSONObject().put("provider", "p1").put("model", "m").put("reasoningEffort", "high"))
        assertEquals("Provider One", choice.providerName)
        assertEquals("Model M", choice.modelName)
        assertEquals("High", choice.effortName)
        assertTrue(choice.selected)
        val unknown = modelChoice(catalog, JSONObject().put("provider", "p2").put("model", "x"))
        assertEquals("p2", unknown.providerName)
        assertEquals("x", unknown.modelName)
        assertFalse(modelChoice(catalog, null).selected)
    }

    @Test fun todosAndGoalHideCompletedGoal() {
        val projection = JSONObject("""{"todos":[{"content":"a","status":"completed"},{"content":"","status":"pending"},{"content":"b","status":"in_progress"}],
            "goal":{"goal":{"objective":"ship","phase":"active"}}}""")
        assertEquals(listOf(TodoEntry("a", "completed"), TodoEntry("b", "in_progress")), todoItems(projection))
        assertEquals("ship", goalInfo(projection)?.objective)
        assertNull(goalInfo(JSONObject("""{"goal":{"goal":{"objective":"ship","phase":"complete"}}}""")))
        assertNull(goalInfo(JSONObject("""{"goal":null}""")))
    }

    @Test fun relativeTimeBuckets() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = Calendar.getInstance(zone).apply { set(2026, Calendar.OCTOBER, 15, 15, 0, 0) }.timeInMillis
        assertEquals("刚刚", relativeTime(now - 10_000, now, zone))
        assertEquals("5 分钟前", relativeTime(now - 5 * 60_000, now, zone))
        assertEquals("3 小时前", relativeTime(now - 3 * 3_600_000, now, zone))
        assertEquals("昨天", relativeTime(now - 20 * 3_600_000, now, zone))
        assertEquals("10月1日", relativeTime(now - 14L * 86_400_000, now, zone))
        assertEquals("", relativeTime(0, now, zone))
    }

    @Test fun greetingIsStableForSeedAndNeverBlank() {
        for (hour in 0..23) for (day in 1..7) for (seed in 0..9) assertTrue(greeting(hour, day, seed).isNotBlank())
        assertEquals(greeting(9, Calendar.MONDAY, 3), greeting(9, Calendar.MONDAY, 3))
        assertEquals("workspace", workspaceLabel("/workspace/"))
        assertEquals("本机", workspaceLabel(""))
    }
}
