package dev.dsh.pocket.notify

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotifyPolicyTest {
    private fun posted(at: Long, title: String = "张三", text: String = "在吗", key: String = "k$at", pkg: String = "com.tencent.mobileqq",
                       category: String? = "msg", ongoing: Boolean = false, summary: Boolean = false, work: Boolean = true) =
        Posted(pkg, key, work, "msg", category, ongoing, summary, title, text, at)

    /** Same bytes, secret and MAC as cowork-plugin/test/strict-notify.test.mjs VECTOR. */
    @Test fun macMatchesThePluginVector() {
        val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val body = "{\"v\":2,\"id\":\"00000000-0000-4000-8000-000000000001\",\"sentAt\":1792000000000,\"count\":2,\"firstAt\":1791999990000,\"lastAt\":1791999999000,\"items\":[{\"package\":\"com.tencent.mobileqq\",\"app\":\"QQ\",\"profile\":\"work\",\"sender\":\"张三\",\"text\":\"在吗\",\"at\":1791999999000}]}"
        assertEquals("939a63c9aebd71efd91d356a9d8210c075955f24142ab6c82a79a256648737d2", NotifyWire.mac(secret, body.toByteArray(Charsets.UTF_8)))
    }

    @Test fun bodyCarriesTheExactPluginSchemaWithEveryAppsText() {
        val batch = NotifyThrottle.Batch(2, 1_000, 2_000, listOf(
            NotifyThrottle.Item("com.tencent.mobileqq", "QQ", true, "张三", "在吗", 1_500),
            NotifyThrottle.Item("tv.danmaku.bili", "B站", false, "王五", "私信", 2_000)))
        val body = JSONObject(NotifyWire.body(batch, "00000000-0000-4000-8000-000000000001", 3_000))
        assertEquals(setOf("v", "id", "sentAt", "count", "firstAt", "lastAt", "items"), body.keys().asSequence().toSet())
        assertEquals(2, body.getInt("v")); assertEquals(2, body.getInt("count"))
        val items = body.getJSONArray("items")
        assertEquals(setOf("package", "app", "profile", "sender", "text", "at"), items.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(listOf("work", "personal"), (0 until items.length()).map { items.getJSONObject(it).getString("profile") })
        assertEquals("B站", items.getJSONObject(1).getString("app")); assertEquals("私信", items.getJSONObject(1).getString("text"))
        // The plugin refuses times after sentAt; a clock step back on the phone must not void the batch.
        val stepped = JSONObject(NotifyWire.body(batch.copy(lastAt = 9_000), "00000000-0000-4000-8000-000000000003", 1_800))
        assertEquals(1_800L, stepped.getLong("lastAt")); assertEquals(1_000L, stepped.getLong("firstAt"))
        assertEquals(1_800L, stepped.getJSONArray("items").getJSONObject(1).getLong("at"))
    }

    @Test fun filterDropsOnlyStructuralNoise() {
        assertEquals(Verdict.ACCEPT, NotifyFilter.check(posted(1)))
        assertEquals(Verdict.ONGOING, NotifyFilter.check(posted(1, ongoing = true)))
        assertEquals(Verdict.SUMMARY, NotifyFilter.check(posted(1, summary = true)))
        assertEquals(Verdict.CATEGORY, NotifyFilter.check(posted(1, category = "promo")))
        assertEquals(Verdict.ACCEPT, NotifyFilter.check(posted(1, category = null)))
        assertEquals(Verdict.EMPTY, NotifyFilter.check(posted(1, title = " ", text = "")))
    }

    @Test fun differentAppsJoinOneBatchAndEachNotificationRestartsTheTenSecondWait() {
        val t = NotifyThrottle()
        assertEquals(Verdict.ACCEPT, t.add(posted(1_000, text = "a"), "QQ"))
        assertEquals(11_000L, t.fireAt())
        assertEquals(Verdict.ACCEPT, t.add(posted(8_000, text = "b", pkg = "tv.danmaku.bili", work = false), "B站"))
        assertEquals(18_000L, t.fireAt())
        assertNull(t.take(17_999))
        val batch = t.take(18_000)!!
        assertEquals(2, batch.count); assertEquals(1_000L, batch.firstAt); assertEquals(8_000L, batch.lastAt)
        assertEquals(listOf("QQ", "B站"), batch.items.map { it.app }); assertEquals(listOf(true, false), batch.items.map { it.work })
        assertNull(t.fireAt()); assertEquals(0, t.waiting())
    }

    @Test fun aStreamThatNeverPausesStillGoesOutAfterMaxWait() {
        val t = NotifyThrottle()
        var at = 0L
        while (at <= 200_000) { t.add(posted(at, text = "m$at"), "QQ"); at += 5_000 }
        assertEquals(NotifyDefaults.MAX_WAIT_MS, t.fireAt())
        assertEquals(41, t.take(NotifyDefaults.MAX_WAIT_MS)!!.count)
    }

    @Test fun repostedContentIsNotCountedTwice() {
        val t = NotifyThrottle()
        assertEquals(Verdict.ACCEPT, t.add(posted(1_000, key = "same", text = "在吗"), "QQ"))
        assertEquals(Verdict.REPEAT, t.add(posted(2_000, key = "same", text = "在吗"), "QQ"))
        assertEquals(Verdict.ACCEPT, t.add(posted(3_000, key = "same", text = "在吗？还有事"), "QQ"))
        assertEquals(Verdict.ACCEPT, t.add(posted(1_000 + NotifyThrottle.REPEAT_MS + 10_000, key = "same", text = "在吗？还有事"), "QQ"))
        assertEquals(3, t.take(Long.MAX_VALUE)!!.count)
    }

    @Test fun excerptsAreCleanedAndCapped() {
        val t = NotifyThrottle()
        for (i in 0 until 25) t.add(posted(i.toLong(), title = "a\u0007b" + "x".repeat(100), text = "y".repeat(500) + i), "QQ")
        val batch = t.take(Long.MAX_VALUE)!!
        assertEquals(25, batch.count); assertEquals(NotifyThrottle.MAX_ITEMS, batch.items.size)
        assertEquals(80, batch.items[0].sender.length); assertTrue(batch.items[0].sender.startsWith("a b"))
        assertEquals(400, batch.items[0].text.length)
        assertEquals(24L, batch.items.last().at) // the newest twenty are kept
    }

    @Test fun appLabelsAreOneShortLineWithAFallback() {
        assertEquals("QQ", NotifyDefaults.label(" QQ ", "com.tencent.mobileqq"))
        assertEquals("哔哩 哔哩", NotifyDefaults.label("哔哩\n哔哩", "tv.danmaku.bili"))
        assertEquals("a b", NotifyDefaults.label("a" + String(Character.toChars(0x2028)) + "b", "x.y"))
        assertEquals("bili", NotifyDefaults.label(null, "tv.danmaku.bili"))
        assertEquals("bili", NotifyDefaults.label(" \t ", "tv.danmaku.bili"))
        assertEquals(60, NotifyDefaults.label("名".repeat(80), "x.y").length)
        assertEquals(listOf("a.b", "c.d", "e.f"), NotifyDefaults.words("a.b，c.d, e.f\na.b"))
    }

    @Test fun outcomeAndJudgeErrors() {
        assertEquals(NotifyWire.Outcome(true, true, 0.93, null, 1, 11), NotifyWire.outcome("{\"queued\":true,\"judged\":true,\"meaningful\":0.93,\"kept\":1,\"total\":11}"))
        assertEquals(NotifyWire.Outcome(false, false, null, "no-key"), NotifyWire.outcome("{\"queued\":false,\"judged\":false,\"judgeError\":\"no-key\"}"))
        assertEquals(NotifyWire.Outcome(false, true, 0.1, null, 0, 3), NotifyWire.outcome("{\"queued\":false,\"judged\":true,\"meaningful\":0.1,\"kept\":0,\"total\":3}"))
        assertEquals("Jev 返回 HTTP 429", NotifyWire.judgeError("http-429"))
        assertEquals("DSH 里没有 Jev 密钥", NotifyWire.judgeError("no-key"))
    }
}
