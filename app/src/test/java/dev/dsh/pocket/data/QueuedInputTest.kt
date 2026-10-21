package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuedInputTest {
    private fun message(id: String, vararg parts: JSONObject) = JSONObject().put("id", id).put("role", "user").put("content", JSONArray(parts.toList()))
    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
    private fun projection(nextStep: List<JSONObject> = emptyList(), nextTurn: List<JSONObject> = emptyList()) =
        JSONObject().put("inbox", JSONObject().put("next-step", JSONArray(nextStep)).put("next-turn", JSONArray(nextTurn)))

    @Test fun oldestIsNextStepInputBeforeQueuedTurnsLikeDshClaimsIt() {
        assertNull(QueuedInput.oldest(JSONObject()))
        assertNull(QueuedInput.oldest(projection()))
        assertEquals("a", QueuedInput.oldest(projection(nextTurn = listOf(message("a", text("先")), message("b", text("后")))))!!.getString("id"))
        assertEquals("s", QueuedInput.oldest(projection(listOf(message("s", text("补充"))), listOf(message("a", text("先")))))!!.getString("id"))
    }

    @Test fun onlyPlainTextIsSentAgain() {
        val content = QueuedInput.textContent(message("a", text("第一段"), text("第二段")))!!
        assertEquals(2, content.length())
        assertEquals("第二段", content.getJSONObject(1).getString("text"))
        assertEquals(setOf("type", "text"), content.getJSONObject(0).keys().asSequence().toSet())
        assertNull(QueuedInput.textContent(message("i", text("看图"), JSONObject().put("type", "image").put("attachmentId", "x"))))
        assertNull(QueuedInput.textContent(message("e")))
    }
}
