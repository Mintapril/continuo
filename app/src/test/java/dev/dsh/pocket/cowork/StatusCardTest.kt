package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.StatusCard.Mood
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StatusCardTest {
    private fun state(json: String) = JSONObject(json)
    private val idle = state("""{"state":"idle","taskId":"","displayId":5}""")

    @Test fun offlineSaysSoAndSleeps() {
        val card = StatusCard.of("小满", CoworkLinkState.LOST, idle, false, 12)
        assertEquals("小满 · 联系不上", card.title)
        assertEquals(Mood.SLEEP, card.mood)
        assertEquals("它 · 没在线", StatusCard.of("", CoworkLinkState.DISCONNECTED, idle, false, 12).title)
        assertEquals("小满 · 正在醒来", StatusCard.of("小满", CoworkLinkState.CONNECTING, idle, false, 12).title)
    }

    @Test fun workingShowsTheAppAndTheLastAction() {
        val running = state("""{"state":"running","taskId":"cowork-owner","displayId":5,"activity":{"app":"QQ","text":"打开 QQ"}}""")
        val card = StatusCard.of("小满", CoworkLinkState.CONNECTED, running, false, 12)
        assertEquals("小满 · 在 QQ", card.title)
        assertEquals("打开 QQ", card.text)
        assertTrue(card.working)
        assertEquals(Mood.WORK, card.mood)
        assertEquals("小满 · 在忙", StatusCard.of("小满", CoworkLinkState.CONNECTED, state("""{"state":"running","taskId":"t","displayId":5}"""), false, 12).title)
    }

    @Test fun aRequestToTakeOverIsAskingUntilAccepted() {
        val asked = state("""{"state":"waiting_user","taskId":"t","displayId":5,"userControl":true,"interventionBy":"model","interventionReason":"要验证码"}""")
        val card = StatusCard.of("小满", CoworkLinkState.CONNECTED, asked, false, 12)
        assertEquals("小满找你", card.title)
        assertEquals("需要你接手：要验证码", card.text)
        assertTrue(card.asking)
        assertEquals(Mood.CALL, card.mood)
        val taken = StatusCard.of("小满", CoworkLinkState.CONNECTED, asked, true, 12)
        assertFalse(taken.asking)
        assertTrue(taken.ownerControl)
        assertEquals("你在用小满的手机", taken.title)
    }

    @Test fun idleShowsWhatItDidLastAndItsTimeOfDay() {
        val after = state("""{"state":"completed","taskId":"t","displayId":5,"activity":{"app":"B 站","text":"切换到 B 站"}}""")
        val card = StatusCard.of("小满", CoworkLinkState.CONNECTED, after, false, 15)
        assertEquals("小满 · 空闲", card.title)
        assertEquals("刚才：切换到 B 站", card.text)
        assertFalse(card.working)
        assertEquals(Mood.IDLE, card.mood)
        assertEquals("叫它做事，或者写张便条", StatusCard.of("小满", CoworkLinkState.CONNECTED, idle, false, 15).text)
        assertEquals(Mood.SLEEP, StatusCard.idleMood(23)); assertEquals(Mood.SLEEP, StatusCard.idleMood(3))
        assertEquals(Mood.EAT, StatusCard.idleMood(12, 10)); assertEquals(Mood.IDLE, StatusCard.idleMood(12, 40))
        assertEquals("小满 · 停下了", StatusCard.of("小满", CoworkLinkState.CONNECTED, state("""{"state":"paused","taskId":"t","displayId":5}"""), false, 15).title)
    }

    @Test fun anErrorSaysWhatWasLeftUnfinishedThenGoesIdle() {
        val patrol = state("""{"state":"error","taskId":"cowork-app-patrol","instanceId":"i","executionId":"e1","displayId":5,"activity":{"app":"画世界Pro","text":"画了两笔"}}""")
        val card = StatusCard.of("小满", CoworkLinkState.CONNECTED, patrol, false, 15, errorAgeMs = 60_000)
        assertEquals("小满 · 巡视没做完", card.title)
        assertEquals("在 画世界Pro 里出错，停下了", card.text)
        assertFalse(card.working)
        assertEquals(Mood.CALL, card.mood)
        val owner = state("""{"state":"error","taskId":"cowork-owner-20261025-160000","displayId":5}""")
        assertEquals("小满 · 你交代的事没做完", StatusCard.of("小满", CoworkLinkState.CONNECTED, owner, false, 15).title)
        assertEquals("中途出错，停下了", StatusCard.of("小满", CoworkLinkState.CONNECTED, owner, false, 15).text)
        val app = state("""{"state":"error","taskId":"cowork-app-work-com-tencent-mobileqq","displayId":5}""")
        assertEquals("小满 · 刚才那件事没做完", StatusCard.of("小满", CoworkLinkState.CONNECTED, app, false, 15).title)
        // After a while the card is idle again, with what it did last.
        val later = StatusCard.of("小满", CoworkLinkState.CONNECTED, patrol, false, 15, errorAgeMs = StatusCard.ERROR_SHOWN_MS)
        assertEquals("小满 · 空闲", later.title)
        assertEquals("刚才：画了两笔", later.text)
        assertEquals(Mood.IDLE, later.mood)
        // One failed task is one key: the same execution again keeps it, a new task or broker changes it.
        assertEquals("i|cowork-app-patrol|e1", StatusCard.errorKey(patrol))
        assertNull(StatusCard.errorKey(idle))
        assertNull(StatusCard.errorKey(state("""{"state":"error","taskId":""}""")))
    }
}
