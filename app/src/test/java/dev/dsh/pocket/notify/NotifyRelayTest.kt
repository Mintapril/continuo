package dev.dsh.pocket.notify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotifyRelayTest {
    private class FakeHost : NotifyRelay.Host {
        var t = 1_000L
        var armed: Long? = null
        val awake = mutableListOf<Long>()
        val sent = mutableListOf<NotifyThrottle.Batch>()
        var answer: () -> NotifyWire.Outcome = { NotifyWire.Outcome(queued = true, judged = true, meaningful = 0.9, judgeError = null, kept = 1, total = 1) }
        override fun now() = t
        override fun arm(delayMs: Long) { armed = delayMs }
        override fun send(batch: NotifyThrottle.Batch): NotifyWire.Outcome { sent += batch; return answer() }
        override fun awake(ms: Long) { awake += ms }
    }

    private fun posted(at: Long, pkg: String = "com.tencent.mobileqq", text: String = "在吗", category: String? = "msg") =
        Posted(pkg, "k$at", work = true, channel = "msg", category = category, ongoing = false, groupSummary = false, title = "张三", text = text, at = at)

    private fun relay(host: FakeHost, config: NotifyConfig = NotifyConfig(enabled = true)) = NotifyRelay(host).apply { configure(config) }

    @Test fun configRoundTripsAndRefusesAnythingElse() {
        val c = NotifyConfig(true, setOf("com.android.bankabc", "com.tencent.mm"), 10)
        assertEquals(c, NotifyConfig.parse(JSONObject(c.json().toString())))
        assertNull(NotifyConfig.parse(null))
        assertNull("extra key", NotifyConfig.parse(c.json().put("x", 1)))
        assertNull("quiet out of range", NotifyConfig.parse(c.json().put("quietSeconds", 0)))
        assertNull("quiet not a number", NotifyConfig.parse(c.json().put("quietSeconds", "5")))
        assertNull("enabled not a boolean", NotifyConfig.parse(c.json().put("enabled", "true")))
        assertNull("not a package", NotifyConfig.parse(c.json().put("excluded", JSONArray().put("a b"))))
        assertNull("too many", NotifyConfig.parse(c.json().put("excluded", JSONArray((0..NotifyConfig.MAX_EXCLUDED).map { "p$it" }))))
    }

    @Test fun offExcludedOwnAndNoiseAreNeverRead() {
        val host = FakeHost()
        val off = relay(host, NotifyConfig(enabled = false))
        assertFalse(off.watches("com.tencent.mobileqq"))
        off.posted(posted(1_000), "QQ"); assertNull(host.armed)
        val on = relay(host, NotifyConfig(enabled = true, excluded = setOf("com.android.bankabc")))
        assertFalse(on.watches("com.android.bankabc")); assertFalse(on.watches(NotifyDefaults.OWN_PACKAGE)); assertTrue(on.watches("com.tencent.mobileqq"))
        on.posted(posted(1_000, category = "promo"), "QQ"); assertNull("structural noise", host.armed)
    }

    @Test fun aBatchGoesOutWhenQuietAndTheCpuStaysUpUntilTheTurnStarts() {
        val host = FakeHost()
        val r = relay(host)
        r.posted(posted(1_000), "QQ")
        assertEquals(5_000L, host.armed)
        assertEquals(5_000 + NotifyDefaults.SEND_AWAKE_MS, host.awake.last())
        host.t = 4_000; r.posted(posted(4_000, text = "还在吗"), "QQ")
        assertEquals("each notification restarts the wait", 5_000L, host.armed)
        host.t = 8_000; r.flush(); assertTrue("not due yet", host.sent.isEmpty())
        host.t = 9_000; r.flush()
        assertEquals(listOf(2), host.sent.map { it.count })
        assertEquals("queued: held until the Cowork turn has started", NotifyDefaults.HANDOFF_AWAKE_MS, host.awake.last())
        assertTrue(r.log().single().text.startsWith("QQ · 2 条：Jev 判定 1/1 条有意义"))
    }

    @Test fun nothingQueuedOrAFailureLetsTheCpuGoAndIsLogged() {
        val host = FakeHost().apply { answer = { NotifyWire.Outcome(queued = false, judged = true, meaningful = 0.1, judgeError = null) } }
        val r = relay(host)
        r.posted(posted(1_000), "QQ"); host.t = 6_000; r.flush()
        assertEquals(0L, host.awake.last())
        host.answer = { error("DSH 未运行") }
        host.t = 7_000; r.posted(posted(7_000, text = "又来"), "QQ"); host.t = 12_000; r.flush()
        assertEquals(0L, host.awake.last())
        assertEquals(listOf("QQ · 1 条：未能送去判断（DSH 未运行）", "QQ · 1 条：Jev 判定都没有实际意义（最高 0.10），未唤起"), r.log().map { it.text })
    }

    @Test fun aBatchCollectedWhileSendingKeepsItsOwnHold() {
        val host = FakeHost()
        lateinit var r: NotifyRelay
        host.answer = { host.t += 1_000; r.posted(posted(host.t, text = "发送时到的"), "QQ"); NotifyWire.Outcome(true, true, 0.9, null, 1, 1) }
        r = relay(host)
        r.posted(posted(1_000), "QQ"); host.t = 6_000; r.flush()
        assertEquals("the new batch's hold, not the handoff", 5_000 + NotifyDefaults.SEND_AWAKE_MS, host.awake.last())
    }

    @Test fun turningItOffDropsThePendingBatchAndTheHold() {
        val host = FakeHost()
        val r = relay(host)
        r.posted(posted(1_000), "QQ")
        r.configure(NotifyConfig(enabled = false))
        assertEquals(0L, host.awake.last())
        host.t = 10_000; r.flush(); assertTrue(host.sent.isEmpty())
    }

    @Test fun unseenScreenTextJoinsTheBatchAsScreenItem() {
        val host = FakeHost()
        val r = relay(host)
        r.screen("com.tencent.mobileqq", "QQ", listOf("  ", "张三：在吗"), 1_000, "screen-1")
        r.screen("com.tencent.mobileqq", "QQ", listOf(" "), 1_100, "screen-2")
        host.t = 6_100; r.flush()
        val item = host.sent.single().items.single()
        assertTrue(item.screen); assertEquals("张三：在吗", item.text); assertEquals("QQ", item.app)
    }

    @Test fun theLogKeepsTheNewestForty() {
        val host = FakeHost()
        val r = relay(host)
        repeat(NotifyRelay.LOG_SIZE + 5) { i -> host.t = 100_000L * (i + 1); r.posted(posted(host.t, text = "m$i"), "QQ"); host.t += 6_000; r.flush() }
        assertEquals(NotifyRelay.LOG_SIZE, r.log().size)
        assertTrue(r.log().first().at > r.log().last().at)
    }
}
