package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ChatText
import org.junit.Assert.*
import org.junit.Test

/** phone.read_chat reads a chat upward and joins the screens by position: names, times and short replies that repeat
 *  on every screen stay with their messages, and each message comes once. */
class ChatTextTest {
    /** A chat laid out top to bottom: each line has a height, items are separated by a gap. */
    private class Chat {
        val texts = ArrayList<String>(); val tops = ArrayList<Int>(); val heights = ArrayList<Int>()
        var end = 0
        fun line(text: String, height: Int) { texts += text; tops += end; heights += height; end += height }
        fun gap(height: Int) { end += height }
        /** The message list showing content from offset at screen y listTop, listHeight high; lines cut by its edges. */
        fun screen(offset: Int, listHeight: Int = 1700, listTop: Int = 200): ChatText.Screen {
            val lines = texts.indices.filter { tops[it] < offset + listHeight && tops[it] + heights[it] > offset }.map {
                ChatText.Line(texts[it], listTop + maxOf(tops[it] - offset, 0), listTop + minOf(tops[it] + heights[it] - offset, listHeight))
            }
            return ChatText.Screen(lines, listTop, listTop + listHeight, offset == 0)
        }
        fun from(offset: Int) = texts.indices.filter { tops[it] + heights[it] > offset }.map { texts[it] }
    }

    /** Reads up from the newest message: each swipe moves the finger by the next distance and the list by that less the
     *  touch slop, never past the top. */
    private fun readUp(chat: Chat, fingers: List<Int>, listHeight: Int = 1700, slop: (Int) -> Int = { 0 }): Pair<ChatText, Int> {
        val text = ChatText()
        var offset = maxOf(0, chat.end - listHeight)
        text.add(chat.screen(offset, listHeight), 0)
        for ((i, finger) in fingers.withIndex()) {
            if (offset == 0) break
            offset = maxOf(0, offset - (finger - slop(i)))
            if (!text.add(chat.screen(offset, listHeight), finger)) break
        }
        return text to offset
    }

    /** A group chat: a name over each run of messages from one person (as QQ and WeChat show it), a time now and then,
     *  and most messages one of a few short replies. */
    private fun groupChat(seed: Long, shortReplies: Int, items: Int = 150): Chat {
        val random = java.util.Random(seed)
        val people = listOf("张三", "李四", "王五", "小明")
        val short = listOf("好的", "哈哈", "收到", "嗯", "[图片]", "[表情]", "可以", "1").take(shortReplies)
        val chat = Chat()
        var last = ""
        for (item in 0 until items) {
            if (item % 9 == 0) { chat.line("昨天 ${10 + item / 60}:${(item * 7 % 60).toString().padStart(2, '0')}", 50); chat.gap(20) }
            val who = people[random.nextInt(people.size)]
            if (who != last) chat.line(who, 40)
            last = who
            if (random.nextInt(100) < 65) chat.line(short[random.nextInt(short.size)], 100)
            else chat.line("第${item}条消息，内容比较长" + "啊".repeat(random.nextInt(30)), 100 + 60 * random.nextInt(4))
            chat.gap(24)
        }
        return chat
    }

    @Test fun namesTimesAndShortRepliesStayAndEachMessageComesOnce() {
        val chat = Chat()
        for ((who, said) in listOf("张三" to "周末有空吗", "李四" to "好的", "张三" to "好的", "李四" to "那明天见", "张三" to "好的", "李四" to "好的")) {
            chat.line(who, 40); chat.line(said, 100); chat.gap(20)
        }
        val (text, offset) = readUp(chat, listOf(300, 300, 300), listHeight = 500)
        assertEquals(0, offset)
        assertEquals(chat.texts.joinToString("\n"), text.text())
        assertEquals(3, text.pages())
    }

    /** The realistic case: the finger rests before it lifts, so each swipe moves the list three quarters of the list,
     *  less a touch slop of up to 40 pixels. Every chat comes back exactly, however few different replies it has. */
    @Test fun aLongGroupChatIsRebuiltExactly() {
        for (seed in 1L..300L) for (shortReplies in listOf(1, 2, 4, 8)) {
            val chat = groupChat(seed, shortReplies)
            val random = java.util.Random(seed * 31 + shortReplies)
            val (text, offset) = readUp(chat, List(11) { 1275 }, slop = { random.nextInt(41) })
            assertEquals("seed $seed, $shortReplies short replies", chat.from(offset).joinToString("\n"), text.text())
        }
    }

    /** Swipes from 30 % to 98 % of the list: overlaps down to a few pixels, a single cut line, or a single 好的. */
    @Test fun anyOverlapIsJoinedExactly() {
        for (seed in 1L..300L) for (shortReplies in listOf(1, 2, 8)) {
            val chat = groupChat(seed, shortReplies)
            val random = java.util.Random(seed * 17 + shortReplies)
            val (text, offset) = readUp(chat, List(11) { 550 + random.nextInt(1116) }, slop = { random.nextInt(41) })
            assertEquals("seed $seed, $shortReplies short replies", chat.from(offset).joinToString("\n"), text.text())
        }
    }

    /** At the top of the history the last swipe moves the list less than the finger. */
    @Test fun readingToTheTopEndsWithTheFirstMessage() {
        for (seed in 1L..100L) {
            val chat = groupChat(seed, 4, items = 12 + seed.toInt() % 20)
            val (text, offset) = readUp(chat, List(11) { 1275 }, slop = { 20 })
            assertEquals(0, offset)
            assertEquals("seed $seed", chat.texts.joinToString("\n"), text.text())
        }
    }

    /** The scrolling node may also hold the chat's title, its input bar and a "new messages" chip, which stay put while
     *  the messages move: they come once (from the screen reading began on) and the messages join as without them. */
    @Test fun barsInsideTheScrollingNodeComeOnce() {
        val bars = listOf(ChatText.Line("技术交流群(32)", 200, 330), ChatText.Line("3条新消息", 360, 420), ChatText.Line("发消息", 1780, 1900))
        fun withBars(s: ChatText.Screen) = ChatText.Screen((s.lines + bars).sortedBy { it.top }, s.top, s.bottom, s.atTop)
        for (seed in 1L..200L) {
            val chat = groupChat(seed, 3)
            val random = java.util.Random(seed)
            val text = ChatText()
            var offset = maxOf(0, chat.end - 1700)
            text.add(withBars(chat.screen(offset)), 0)
            repeat(11) { if (offset > 0) { offset = maxOf(0, offset - (1275 - random.nextInt(41))); text.add(withBars(chat.screen(offset)), 1275) } }
            val lines = text.text().split("\n")
            for (bar in bars) assertEquals("seed $seed", 1, lines.count { it == bar.text })
            assertEquals("seed $seed", chat.from(offset), lines.filter { line -> bars.none { it.text == line } })
        }
    }

    /** A message cut by the top edge of the screen below is whole on the screen above, and the other way round; one
     *  taller than the list is cut on both. */
    @Test fun aMessageCutByAnEdgeComesOnce() {
        val chat = Chat()
        chat.line("张三", 40); chat.line("这一条消息很长很长很长很长，跨过了两屏的接缝", 600); chat.line("李四", 40); chat.line("好的", 100)
        val text = ChatText()
        text.add(chat.screen(500, listHeight = 300), 0) // the long message cut at the top, then 李四 and 好的
        text.add(chat.screen(100, listHeight = 300), 400) // only the long message, cut at both edges
        text.add(chat.screen(0, listHeight = 300), 100)
        assertEquals(chat.texts.joinToString("\n"), text.text())
    }

    /** A line that changed between the two screens (a message edited meanwhile) does not break the join. */
    @Test fun aLineThatChangedInTheOverlapDoesNotBreakTheJoin() {
        val chat = Chat()
        for (i in 0 until 8) { chat.line("张三", 40); chat.line("第${i}条比较长的消息内容", 100); chat.gap(20) }
        val bottom = chat.screen(800, listHeight = 480)
        val changed = ChatText.Screen(bottom.lines.map { if (it.text == "第6条比较长的消息内容") ChatText.Line("第6条（已编辑）", it.top, it.bottom) else it }, bottom.top, bottom.bottom, false)
        val text = ChatText()
        text.add(changed, 0)
        var offset = 800
        for (next in listOf(560, 320, 80, 0)) { text.add(chat.screen(next, listHeight = 480), offset - next); offset = next }
        assertEquals(chat.texts.joinToString("\n").replace("第6条比较长的消息内容", "第6条（已编辑）"), text.text())
    }

    /** The screens share a single short line, the last above and the first below: it comes once. */
    @Test fun aSingleSharedShortLineComesOnce() {
        val below = ChatText.Screen(listOf(ChatText.Line("好的", 200, 230), ChatText.Line("李四", 254, 294)), 200, 700, false)
        val above = ChatText.Screen(listOf(ChatText.Line("张三", 500, 540), ChatText.Line("好的", 540, 640)), 200, 700, false)
        val text = ChatText()
        text.add(below, 0); text.add(above, 410)
        assertEquals("张三\n好的\n李四", text.text())
    }

    /** The list moved past a whole screen: the screens share nothing and every line of the one above is new. */
    @Test fun screensThatShareNothingAreAllNew() {
        val below = ChatText.Screen(listOf(ChatText.Line("张三", 200, 240), ChatText.Line("好的", 240, 340)), 200, 700, false)
        val above = ChatText.Screen(listOf(ChatText.Line("李四", 300, 340), ChatText.Line("好的", 340, 440)), 200, 700, false)
        val text = ChatText()
        text.add(below, 0); text.add(above, 500)
        assertEquals("李四\n好的\n张三\n好的", text.text())
        assertEquals(2, text.pages())
    }

    @Test fun anOlderScreenThatDoesNotFitIsLeftOutWhole() {
        val newest = "新".repeat(ChatText.MAX - 10)
        val text = ChatText()
        assertTrue(text.add(ChatText.Screen(listOf(ChatText.Line(newest, 200, 1900)), 200, 1900, false), 0))
        assertFalse(text.add(ChatText.Screen(listOf(ChatText.Line("更早的一条消息放不下了", 200, 300)), 200, 1900, false), 1275))
        assertEquals(newest, text.text())
        assertEquals(1, text.pages())
    }

    @Test fun aFirstScreenTooLongKeepsItsNewestEnd() {
        val text = ChatText()
        assertFalse(text.add(ChatText.Screen(listOf(ChatText.Line("旧".repeat(100), 200, 300), ChatText.Line("新".repeat(ChatText.MAX), 300, 1900)), 200, 1900, false), 0))
        assertEquals("新".repeat(ChatText.MAX), text.text())
    }
}
