package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ScreenNews
import dev.dsh.pocket.cowork.broker.ScreenWatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** New text on the AI screen that the model neither caused nor saw: reported with its next observation (ScreenWatch),
 *  and what a finished turn left unseen goes, through the broker state (ScreenNews), to the notification triggers
 *  (ScreenNewsFeed). */
class ScreenWatchTest {
    private val chat = listOf("张三", "在吗", "发送")

    @Test fun whatAppearsWhileTheModelThinksComesWithItsNextObservationOnce() {
        val w = ScreenWatch()
        assertEquals(emptyList<String>(), w.observed("qq", chat))
        w.idle("qq", chat + "李四：晚上吃饭吗")          // an in-app banner about another chat
        w.idle("qq", chat)                               // gone again before the model looks
        w.idle("qq", chat + "明天几点到？")              // a new message in this chat
        w.idle("qq", chat + "明天几点到？")              // still there: recorded once
        w.acting()
        assertEquals(listOf("李四：晚上吃饭吗", "明天几点到？"), w.observed("qq", chat + "明天几点到？"))
        assertEquals("reported once", emptyList<String>(), w.observed("qq", chat + "明天几点到？"))
    }

    @Test fun nothingIsRecordedBeforeTheFirstObservationAfterAnInputWithoutOneOrInAnotherApp() {
        val w = ScreenWatch()
        w.idle("qq", chat + "x")
        assertEquals("nothing observed yet", emptyList<String>(), w.observed("qq", chat))
        w.acting()                                       // e.g. phone_launch_app or phone_read_page: no screen returned
        w.idle("qq", chat + "loading done")
        assertEquals(emptyList<String>(), w.observed("qq", chat + "loading done"))
        w.idle("com.other", listOf("另一个应用"))
        w.idle("", listOf("?"))
        assertEquals(emptyList<String>(), w.observed("qq", chat + "loading done"))
    }

    @Test fun whatTheModelTypedIsItsOwnMessageNotNews() {
        val w = ScreenWatch()
        w.observed("qq", chat)
        w.typed("好的，明天见"); w.typed("  "); w.typed("x")
        w.idle("qq", chat + listOf("好的，明天见", "好的，明天见 12:03", "明天见", "已送达", "x", "xy"))
        assertEquals("its own bubble, the bubble with a time, a part of it: not news", listOf("已送达", "xy"), w.observed("qq", chat))
        assertTrue(ScreenWatch.own("好的", listOf("好的，明天见")))
        assertFalse("one character is not enough to call a line its own", ScreenWatch.own("好", listOf("你好呀")))
    }

    @Test fun equalLinesAreCountedSoASecondEqualMessageIsNew() {
        val w = ScreenWatch()
        w.observed("qq", listOf("好的"))
        w.idle("qq", listOf("好的", "好的"))
        assertEquals(listOf("好的"), w.observed("qq", listOf("好的", "好的")))
    }

    @Test fun theEndOfATurnHandsOverWhatTheModelNeverSawAndStartsOver() {
        val w = ScreenWatch()
        w.observed("qq", chat)
        w.idle("qq", chat + "banner")
        w.typed("我到了")
        val pending = w.detach()
        assertEquals("qq", pending.pkg())
        assertEquals(listOf("banner", "最后一条", "最后一条"), pending.unseen("qq", chat + listOf("banner", "我到了", "最后一条", "最后一条")))
        assertEquals("another app in front at the end: only what was recorded", listOf("banner"), pending.unseen("com.other", listOf("y")))
        assertEquals(listOf("banner"), pending.unseen("", emptyList()))
        assertEquals("detach starts over", emptyList<String>(), w.observed("qq", chat))
        w.acting()
        assertEquals("after an input without an observation, the final read cannot be judged", emptyList<String>(), w.detach().unseen("qq", chat + "z"))
        w.observed("qq", chat); w.idle("qq", chat + "q"); w.reset()
        assertEquals(emptyList<String>(), w.detach().unseen("qq", chat))
    }

    @Test fun reportsKeepTheNewestLines() {
        val w = ScreenWatch()
        w.observed("qq", emptyList())
        val lines = (1..25).map { "m$it" }
        w.idle("qq", lines)
        assertEquals((6..25).map { "m$it" }, w.observed("qq", lines))
        w.observed("qq", emptyList())
        assertEquals((6..25).map { "m$it" }, w.detach().unseen("qq", lines))
    }

    @Test fun theBrokerKeepsTheLastNewsCutToTheirNewestLines() {
        val news = ScreenNews()
        assertEquals("nothing to post", 0L, news.post("qq", "QQ", listOf(" ", ""), 1))
        assertEquals(0L, news.post("", "QQ", listOf("x"), 1))
        for (i in 1..4) assertEquals(i.toLong(), news.post("com.tencent.mobileqq", "QQ", listOf("a$i", "b$i"), 1000L + i))
        val rows = news.json()
        assertEquals(ScreenNews.KEEP, rows.length())
        assertEquals(listOf(2L, 3L, 4L), (0 until rows.length()).map { rows.getJSONObject(it).getLong("seq") })
        val last = rows.getJSONObject(2)
        assertEquals("com.tencent.mobileqq", last.getString("package")); assertEquals("QQ", last.getString("app")); assertEquals(1004L, last.getLong("at"))
        assertEquals(listOf("a4", "b4"), (0 until 2).map { last.getJSONArray("lines").getString(it) })
        val long = ScreenNews()
        long.post("qq", "", listOf("x".repeat(500), "y".repeat(200), "z".repeat(300)), 1)
        val kept = long.json().getJSONObject(0)
        assertEquals("qq", kept.getString("app"))
        assertEquals("the newest lines within MAX_CHARS", listOf("y".repeat(200), "z".repeat(300)), (0 until kept.getJSONArray("lines").length()).map { kept.getJSONArray("lines").getString(it) })
        val huge = ScreenNews().apply { post("qq", "QQ", listOf("h".repeat(700)), 1) }.json().getJSONObject(0).getJSONArray("lines")
        assertEquals(ScreenNews.MAX_CHARS, huge.getString(0).length)
    }

    @Test fun theControllerHandsEachEntryOverOncePerBrokerInstanceAndSkipsOldOnes() {
        val news = ScreenNews()
        news.post("com.tencent.mobileqq", "QQ", listOf("在吗"), 1_000); news.post("com.tencent.mobileqq", "QQ", listOf("还在吗"), 2_000)
        val state = JSONObject().put("instanceId", "a").put("screenNews", news.json())
        assertEquals(listOf(1L, 2L), ScreenNewsFeed.fresh(state, "", 0, 3_000, 10_000).map { it.seq })
        assertEquals(listOf(ScreenNewsFeed.News(2, "com.tencent.mobileqq", "QQ", listOf("还在吗"), 2_000)), ScreenNewsFeed.fresh(state, "a", 1, 3_000, 10_000))
        assertTrue(ScreenNewsFeed.fresh(state, "a", 2, 3_000, 10_000).isEmpty())
        assertEquals("too old: Continuo was not running", listOf(2L), ScreenNewsFeed.fresh(state, "", 0, 11_500, 10_000).map { it.seq })
        assertEquals(2L, ScreenNewsFeed.lastSeq(state))
        assertTrue(ScreenNewsFeed.fresh(JSONObject().put("screenNews", news.json()), "", 0, 3_000, 10_000).isEmpty())
        assertEquals(0L, ScreenNewsFeed.lastSeq(JSONObject()))
    }
}
