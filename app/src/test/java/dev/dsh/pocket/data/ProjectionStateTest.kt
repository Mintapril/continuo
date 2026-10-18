package dev.dsh.pocket.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectionStateTest {
    private fun block(seq: Long, values: JSONObject, kind: String? = null) = JSONObject()
        .put("asOfSeq", seq).put("values", values).apply { if (kind != null) put("kind", kind) }
    private fun values(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test fun olderFollowSnapshotCannotRollBackNewerControlProjection() {
        val state = ProjectionState()
        state.update("modelSelection", "new", 20)
        state.baseline(block(10, values("modelSelection" to "old", "permissions" to "ask")))
        assertEquals("new", state.value().getString("modelSelection"))
        assertEquals("ask", state.value().getString("permissions"))
    }

    @Test fun completeSnapshotAbsenceBecomesTombstoneAgainstLateFrames() {
        val state = ProjectionState()
        state.update("removed", "old", 8)
        state.baseline(block(12, values()))
        state.update("removed", "stale", 11)
        state.update("neverSeen", "also stale", 10)
        assertFalse(state.value().has("removed"))
        assertFalse(state.value().has("neverSeen"))
        state.update("removed", "new", 13)
        assertEquals("new", state.value().getString("removed"))
    }

    @Test fun olderControlBaselineCannotClearNewerFollowValues() {
        val state = ProjectionState()
        state.baseline(block(20, values("inbox" to "new")))
        state.baseline(block(10, values("inbox" to "old", "removed" to "stale")))
        assertEquals("new", state.value().getString("inbox"))
        assertFalse(state.value().has("removed"))
    }

    @Test fun cachedCursorIsNeverComparedWithLiveSessionSequence() {
        val state = ProjectionState()
        state.hint(block(999, values("title" to "cached", "stale" to true), "cached"))
        state.baseline(block(2, values("title" to "live")))
        assertEquals("live", state.value().getString("title"))
        assertFalse(state.value().has("stale"))
        state.hint(block(1000, values("title" to "cached again", "stale" to true), "cached"))
        assertEquals("live", state.value().getString("title"))
        assertFalse(state.value().has("stale"))
    }

    @Test fun sequencedHintsArePartialAndOutputIsDetached() {
        val state = ProjectionState()
        state.baseline(block(5, values("a" to 1, "b" to 2)))
        state.hint(block(6, values("a" to 3), "sequenced"))
        assertEquals(2, state.value().getInt("b"))
        state.value().put("a", 500)
        assertEquals(3, state.value().getInt("a"))
    }
}
