package dev.dsh.pocket.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JevFocusTest {
    // What coworkUnattended/jevFocus returns (cowork-plugin jev-focus-store.mjs readFocus): entries by `<profile>:<package>`.
    private val stored = JSONObject("""{
        "work:com.tencent.mobileqq": {"text": "内部测试群有人 @ 我才算", "at": 1792000000000, "by": "patrol", "revision": 2},
        "personal:com.tencent.mm": {"text": "只看私信", "at": 1792000100000, "by": "owner", "revision": 1},
        "work:com.shop": {"text": "", "at": 1792000200000, "by": "app", "revision": 3}
    }""")

    @Test fun entriesListTheFocusesInEffectNewestFirst() {
        val list = JevFocus.entries(stored)
        assertEquals(listOf("personal:com.tencent.mm", "work:com.tencent.mobileqq"), list.map { it.app })
        val qq = list[1]
        assertEquals("work", qq.profile); assertEquals("com.tencent.mobileqq", qq.packageName)
        assertEquals("内部测试群有人 @ 我才算", qq.text); assertEquals(1_792_000_000_000L, qq.at); assertEquals("patrol", qq.by)
        assertTrue("a cleared focus is not listed", list.none { it.app == "work:com.shop" })
        assertEquals(emptyList<JevFocus.Entry>(), JevFocus.entries(null))
        assertEquals(emptyList<JevFocus.Entry>(), JevFocus.entries(JSONObject("""{"bad": {"text": "x"}, "work:": {"text": "y"}}""")))
    }

    @Test fun clearNamesTheAppAndTheChangeReadsInWords() {
        assertEquals("""{"request":{"app":"work:com.tencent.mobileqq"}}""", JevFocus.clearRequest("work:com.tencent.mobileqq").toString())
        assertEquals("巡视时", JevFocus.byLabel("patrol")); assertEquals("通知唤起时", JevFocus.byLabel("notify"))
        assertEquals("和你对话时", JevFocus.byLabel("owner")); assertEquals("", JevFocus.byLabel("app"))
        assertEquals(JevFocus.READ, "coworkUnattended/jevFocus"); assertEquals(JevFocus.CLEAR, "coworkUnattended/clearJevFocus")
    }
}
