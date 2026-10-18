package dev.dsh.pocket.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingPromptStoreTest {
    private fun request(session: String = "a", id: String = "request-1") = JSONObject()
        .put("sessionId", session).put("requestId", id)
        .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "hello")))

    @Test fun durableRequestSurvivesRecreatedStoreAndIsDetached() {
        val disk = mutableMapOf<String, String>()
        fun store() = PendingPromptStore({ disk[it] }, { id, value -> if (value == null) disk.remove(id) else disk[id] = value })
        val original = request()
        store().retain("a", original)
        original.put("requestId", "mutated")
        val restored = store().get("a")!!
        assertEquals("request-1", restored.getString("requestId"))
        restored.put("requestId", "another-mutation")
        assertEquals("request-1", store().get("a")!!.getString("requestId"))
    }

    @Test fun sessionsCannotConsumeOrClearEachOthersRequests() {
        val disk = mutableMapOf<String, String>()
        val store = PendingPromptStore({ disk[it] }, { id, value -> if (value == null) disk.remove(id) else disk[id] = value })
        store.retain("a", request())
        store.retain("b", request("b", "request-2"))
        assertThrows(IllegalStateException::class.java) { store.clear("a", "request-2") }
        store.clear("a", "request-1")
        assertNull(store.get("a"))
        assertEquals("request-2", store.get("b")!!.getString("requestId"))
    }

    @Test fun unresolvedRequestCannotBeReplacedByNewUuid() {
        val disk = mutableMapOf<String, String>()
        val store = PendingPromptStore({ disk[it] }, { id, value -> if (value == null) disk.remove(id) else disk[id] = value })
        store.retain("a", request())
        assertThrows(IllegalStateException::class.java) { store.retain("a", request(id = "fresh-uuid")) }
        assertEquals("request-1", store.get("a")!!.getString("requestId"))
    }

    @Test fun corruptionFailsClosedInsteadOfLookingLikeEmptyOutbox() {
        val store = PendingPromptStore({ "{\"sessionId\":\"wrong\",\"requestId\":\"x\",\"content\":[]}" }, { _, _ -> fail("Must not write") })
        assertThrows(IllegalStateException::class.java) { store.get("a") }
        assertThrows(IllegalStateException::class.java) { store.retain("a", request()) }
    }

    @Test fun diskFailureDoesNotPretendRequestWasRetained() {
        val store = PendingPromptStore({ null }, { _, _ -> throw java.io.IOException("disk full") })
        assertThrows(java.io.IOException::class.java) { store.retain("a", request()) }
    }
}
