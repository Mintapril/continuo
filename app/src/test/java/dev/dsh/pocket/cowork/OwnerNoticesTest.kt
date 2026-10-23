package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.OwnerNotices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Notes for the owner from runs nobody watches: kept by the broker (OwnerNotices), each shown once by the controller
 *  (OwnerNoticeFeed), also after either side restarts. */
class OwnerNoticesTest {
    private fun state(instance: String, notices: OwnerNotices) = JSONObject().put("instanceId", instance).put("ownerNotices", notices.json())

    @Test fun theBrokerKeepsTheLastFiveNumberedInOrder() {
        val notices = OwnerNotices()
        for (i in 1..7) assertEquals(i.toLong(), notices.post("n$i", 1000L + i))
        val rows = notices.json()
        assertEquals(OwnerNotices.KEEP, rows.length())
        assertEquals((3..7).map { "n$it" }, (0 until rows.length()).map { rows.getJSONObject(it).getString("text") })
        assertEquals(1007L, rows.getJSONObject(4).getLong("at"))
    }

    @Test fun eachNoticeIsShownOncePerBrokerInstance() {
        val notices = OwnerNotices()
        notices.post("first", 1); notices.post("second", 2)
        assertEquals(listOf(1L, 2L), OwnerNoticeFeed.fresh(state("a", notices), "", 0).map { it.seq })
        assertEquals(listOf(2L), OwnerNoticeFeed.fresh(state("a", notices), "a", 1).map { it.seq })
        assertTrue(OwnerNoticeFeed.fresh(state("a", notices), "a", 2).isEmpty())
        // A restarted broker numbers from 1 again: everything it holds is new.
        val restarted = OwnerNotices().also { it.post("after restart", 3) }
        assertEquals(listOf(OwnerNoticeFeed.Notice(1, "after restart", 3)), OwnerNoticeFeed.fresh(state("b", restarted), "a", 2))
    }

    @Test fun nothingIsShownWithoutABrokerStateOrText() {
        assertTrue(OwnerNoticeFeed.fresh(JSONObject(), "", 0).isEmpty())
        assertTrue(OwnerNoticeFeed.fresh(JSONObject().put("ownerNotices", JSONArray()), "", 0).isEmpty())
        val blank = JSONObject().put("instanceId", "a").put("ownerNotices", JSONArray().put(JSONObject().put("seq", 1).put("text", "  ").put("at", 1)))
        assertTrue(OwnerNoticeFeed.fresh(blank, "", 0).isEmpty())
        val unnamed = JSONObject().put("ownerNotices", JSONArray().put(JSONObject().put("seq", 1).put("text", "x").put("at", 1)))
        assertTrue("no instanceId: not a broker state", OwnerNoticeFeed.fresh(unnamed, "", 0).isEmpty())
    }
}
