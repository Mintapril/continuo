package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActivityLog
import org.junit.Assert.*
import org.junit.Test

class ActivityLogTest {
    private val labels = { pkg: String -> pkg.uppercase() }
    private fun ids(log: ActivityLog) = log.json().getJSONArray("events").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("text") } }

    @Test fun launchAnnouncesAppOnce() {
        val log = ActivityLog()
        log.launched("com.maps", "地图")
        val json = log.json()
        assertEquals("地图", json.getString("app"))
        assertEquals("打开 地图", json.getString("text"))
        assertEquals(listOf("打开 地图"), ids(log))
        log.observe("com.maps", labels)
        assertEquals(listOf("打开 地图"), ids(log))
    }

    @Test fun appSwitchNeedsConfirmationAndFirstObservationIsSilent() {
        val log = ActivityLog()
        log.observe("a", labels)
        assertEquals("A", log.json().getString("app"))
        assertTrue(ids(log).isEmpty())
        val before = log.seq()
        log.observe("b", labels)
        assertEquals(before, log.seq())
        log.observe("a", labels)
        log.observe("b", labels)
        assertEquals(before, log.seq())
        log.observe("b", labels)
        assertEquals("B", log.json().getString("app"))
        assertEquals(listOf("切换到 B"), ids(log))
    }

    @Test fun notesCollapseDuplicatesAndAreBounded() {
        val log = ActivityLog()
        log.note("输入文字"); log.note("输入文字")
        assertEquals(1, ids(log).size)
        for (i in 1..20) log.note("动作 $i")
        assertEquals(ActivityLog.MAX_EVENTS, ids(log).size)
        assertEquals("动作 20", ids(log).last())
    }

    @Test fun controlCharactersAreStrippedAndResetClears() {
        val log = ActivityLog()
        log.note("a\nb\u0000c")
        assertEquals(listOf("a b c"), ids(log))
        val before = log.seq()
        log.reset()
        assertTrue(log.seq() > before)
        assertTrue(ids(log).isEmpty())
        assertEquals("", log.json().getString("app"))
    }
}
