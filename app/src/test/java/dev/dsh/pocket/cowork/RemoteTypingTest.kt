package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.RemoteKeystroke.Key
import dev.dsh.pocket.cowork.RemoteKeystroke.Text
import org.junit.Assert.*
import org.junit.Test

class RemoteTypingTest {
    @Test fun committedTextIsForwardedWithLineBreaksAsEnter() {
        assertEquals(listOf(Text("你好")), RemoteTyping.commit("你好", password = false))
        assertEquals(listOf(Text("a"), Key("ENTER"), Text("b")), RemoteTyping.commit("a\r\nb", password = false))
        assertEquals(listOf(Key("ENTER")), RemoteTyping.commit("\n", password = false).orEmpty().filterNot { it == Text("") })
        assertEquals(listOf(Text("ab")), RemoteTyping.commit("a\tb", password = false))
    }

    @Test fun passwordTakesPrintableAsciiOnlyAndIsRefusedWhole() {
        assertEquals(listOf(Text("Pa55 w0rd!~")), RemoteTyping.commit("Pa55 w0rd!~", password = true))
        assertNull(RemoteTyping.commit("密码", password = true))
        assertNull(RemoteTyping.commit("abc😀", password = true))
        assertNull(RemoteTyping.commit("a\tb", password = true))
        assertEquals(listOf(Text("x"), Key("ENTER")), RemoteTyping.commit("x\n", password = true).orEmpty().filterNot { it == Text("") })
    }

    @Test fun longTextIsSplitAtTheBrokerLimitWithoutBreakingCodePoints() {
        val chunks = RemoteTyping.commit("a".repeat(199) + "😀" + "b".repeat(5), password = false)!!.map { (it as Text).text }
        assertTrue(chunks.all { it.length <= RemoteTyping.MAX_TEXT })
        assertEquals("a".repeat(199), chunks[0])
        assertEquals("😀bbbbb", chunks[1])
        assertEquals(450, RemoteTyping.commit("z".repeat(450), password = false)!!.sumOf { (it as Text).text.length })
    }

    @Test fun backspacesAreBounded() {
        assertEquals(3, RemoteTyping.delete(3).size)
        assertEquals(64, RemoteTyping.delete(1000).size)
        assertTrue(RemoteTyping.delete(-1).isEmpty())
        assertTrue(RemoteTyping.delete(2).all { it == Key("DEL") })
    }
}
