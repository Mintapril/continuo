package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnattendedModelsTest {
    @Test fun requestNamesTheKindAndASelectionOrNull() {
        val set = UnattendedModels.request(UnattendedModels.NOTIFY, "codex", "gpt-6-luna", "high").getJSONObject("request")
        assertEquals("notify", set.getString("kind"))
        val selection = set.getJSONObject("selection")
        assertEquals(setOf("provider", "model", "reasoningEffort"), selection.keys().asSequence().toSet())
        assertEquals("gpt-6-luna", selection.getString("model"))
        val noEffort = UnattendedModels.request(UnattendedModels.PATROL, "claude", "claude-opus-5-5", " ").getJSONObject("request").getJSONObject("selection")
        assertFalse(noEffort.has("reasoningEffort"))
        // Clearing sends an explicit null, which the plugin reads as "follow the session".
        val clear = UnattendedModels.request(UnattendedModels.PATROL, null, null, null).getJSONObject("request")
        assertTrue(clear.has("selection")); assertTrue(clear.isNull("selection"))
        assertTrue(clear.toString().contains("\"selection\":null"))
        assertThrows(IllegalArgumentException::class.java) { UnattendedModels.request("other", "a", "b", null) }
        assertEquals("owner", UnattendedModels.request(UnattendedModels.OWNER, "claude", "claude-fable-5-1", null).getJSONObject("request").getString("kind"))
    }

    @Test fun labelsComeFromTheCatalog() {
        val catalog = JSONObject().put("groups", JSONArray().put(JSONObject().put("id", "claude").put("name", "Claude (Subscription)")
            .put("models", JSONArray().put(JSONObject().put("id", "claude-opus-5-5").put("name", "Claude Opus 5.5")))))
        val stored = JSONObject().put("patrol", JSONObject().put("provider", "claude").put("model", "claude-opus-5-5")).put("notify", JSONObject.NULL)
        val patrol = UnattendedModels.selection(stored, UnattendedModels.PATROL)
        assertEquals("Claude (Subscription) · Claude Opus 5.5", UnattendedModels.label(patrol, catalog))
        assertNull(UnattendedModels.selection(stored, UnattendedModels.NOTIFY))
        assertEquals("跟随会话", UnattendedModels.label(null, catalog))
        val gone = JSONObject().put("provider", "codex").put("model", "gpt-6-luna").put("reasoningEffort", "high")
        assertEquals("codex · gpt-6-luna · high", UnattendedModels.label(gone, catalog))
    }
}
