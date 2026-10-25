package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.TreeDiff
import org.junit.Assert.*
import org.junit.Test

/** An input's settled screen comes as what changed since the model's last full screen, with ids that stay. */
class TreeDiffTest {
    private fun node(text: String, top: Int, rid: String = "", flags: String = "c", role: String = "") =
        TreeDiff.Node(text, "", role, rid, 0, top, 100, top + 10, flags)
    private val chat = listOf(node("返回", 0), node("张三", 20), node("在吗", 40, flags = ""), node("", 60, rid = "input", flags = "ce", role = "EditText"), node("发送", 80))

    private fun full(d: TreeDiff, nodes: List<TreeDiff.Node> = chat, obs: String = "o1"): TreeDiff.Read =
        d.read("qq", nodes, true).also { d.delivered(obs) }

    @Test fun anInputThatChangesLittleComesAsItsChangeAgainstTheBase() {
        val d = TreeDiff()
        val base = full(d)
        assertTrue(base.full)
        assertEquals(listOf("n0", "n1", "n2", "n3", "n4"), base.ids)
        // Typed into the field: the same element (resource id and place), with its new text.
        val typed = chat.toMutableList().also { it[3] = node("晚上见", 60, rid = "input", flags = "cef", role = "EditText") }
        val r = d.read("qq", typed, false)
        assertFalse(r.full)
        assertEquals("o1", r.base)
        assertEquals(base.ids, r.ids)
        assertEquals(listOf(3), r.changed)
        assertEquals(emptyList<Int>(), r.added)
        assertEquals(emptyList<String>(), r.removed)
        assertEquals(4, r.unchanged)
        d.delivered("o2")
        // Sent: the message appears, the field is empty again. Still against the base o1, not against o2.
        val sent = chat + node("晚上见", 50, flags = "")
        val s = d.read("qq", sent, false)
        assertEquals("o1", s.base)
        assertEquals(listOf(5), s.added)
        assertEquals("n5", s.ids[5])
        assertEquals(emptyList<Int>(), s.changed)
        assertEquals(5, s.unchanged)
        d.delivered("o3")
        // A later read keeps the id the added element got.
        assertEquals("n5", d.read("qq", sent, false).ids[5])
    }

    @Test fun aMovedElementKeepsItsIdARemovedOneIsReportedAndIdsAreNeverReused() {
        val d = TreeDiff()
        full(d, chat + listOf(node("一", 100), node("二", 110), node("三", 120)))
        // A list scrolled a little: "张三" moved, "返回" is gone, a new row came.
        val moved = listOf(node("张三", 25), node("在吗", 40, flags = ""), node("", 60, rid = "input", flags = "ce", role = "EditText"), node("发送", 80),
            node("一", 100), node("二", 110), node("三", 120), node("四", 130))
        val r = d.read("qq", moved, false)
        assertFalse(r.full)
        assertEquals("n1", r.ids[0])
        assertEquals(listOf(0), r.changed)
        assertEquals(listOf("n0"), r.removed)
        assertEquals(listOf(7), r.added)
        assertEquals("n8", r.ids[7])
        // "返回" comes back: it is still the base's n0 (its state then), never an id of another element.
        val back = d.read("qq", listOf(node("返回", 0)) + moved, false)
        assertEquals("n0", back.ids[0])
    }

    @Test fun aScreenThatChangedALotAnotherAppOrAnExplicitReadIsFullAndTheNewBase() {
        val d = TreeDiff()
        full(d)
        val other = listOf(node("设置", 0), node("通用", 20), node("隐私", 40), node("关于", 60))
        val r = d.read("qq", other, false)
        assertTrue("most elements differ", r.full)
        assertEquals(listOf("n5", "n6", "n7", "n8"), r.ids)
        d.delivered("o2")
        assertTrue("another app", d.read("wechat", other, false).full)
        d.delivered("o3")
        assertTrue("phone_ui_tree asks for the full screen", d.read("wechat", other, true).full)
        d.delivered("o4")
        assertEquals("o4", d.read("wechat", other, false).base)
        assertTrue("an empty screen", d.read("wechat", emptyList(), false).full)
    }

    @Test fun aFullReadThatNeverReachedTheModelIsNoBaseAndATaskStartsAgain() {
        val d = TreeDiff()
        assertTrue("no base yet", d.read("qq", chat, false).full)
        // Not delivered (a stale read retried): the next read is full again.
        assertTrue(d.read("qq", chat, false).full)
        d.delivered("o1")
        assertFalse(d.read("qq", chat, false).full)
        d.delivered("o2")
        d.reset()
        val r = d.read("qq", chat, false)
        assertTrue(r.full)
        assertEquals("n0", r.ids[0])
    }
}
