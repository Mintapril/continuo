package dev.dsh.pocket.data

import dev.dsh.pocket.data.ChatLog.Kind
import org.junit.Assert.*
import org.junit.Test

/** The home page's 聊天记录: what the owner said, what started a turn, the notes it wrote down and its last reply. */
class ChatLogTest {
    private var n = 0
    private fun row(role: String, text: String = "", title: String = "", detail: String = "", error: Boolean = false, streaming: Boolean = false, source: String = "") =
        TranscriptRow("r${n++}", role, title, text, detail = detail, error = error, streaming = streaming, source = source)
    private fun note(args: String) = row("tool", title = "schedule_create — 完成", detail = "Arguments:\n$args")

    @Test fun aTurnShowsWhatTheOwnerSaidTheNotesAndOnlyTheLastReply() {
        val rows = listOf(
            row("system", "persona"),
            row("user", "明早提醒我带伞"),
            row("assistant", "我记一下"),
            row("tool", title = "phone_ui_tree — 完成"),
            note("""{"title":"带伞","prompt":"下雨提醒带伞","daily":{"time":"08:00:00"}}"""),
            row("assistant", "记好了，每天早上八点看天气。"),
            row("notice", "[OWNER WAITING]"),
        )
        val log = ChatLog.of(rows, running = false)
        assertEquals(listOf(Kind.OWNER, Kind.NOTE, Kind.REPLY), log.map { it.kind })
        assertEquals("明早提醒我带伞", log[0].text)
        assertEquals("下雨提醒带伞", log[1].text)
        assertTrue(log[1].label.startsWith("每天"))
        assertEquals("记好了，每天早上八点看天气。", log[2].text)
    }

    @Test fun aFailedOrStreamingReplyIsNoReplyAndTheRunningTurnThinks() {
        val rows = listOf(
            row("user", "看看 B 站"),
            row("assistant", "出错了", error = true),
            row("user", "在吗", source = "user"),
            row("assistant", "在", streaming = true),
        )
        val running = ChatLog.of(rows, running = true)
        assertEquals(listOf(Kind.OWNER, Kind.OWNER, Kind.THINKING), running.map { it.kind })
        assertEquals(listOf(Kind.OWNER, Kind.OWNER), ChatLog.of(rows, running = false).map { it.kind })
        assertEquals(emptyList<ChatLog.Line>(), ChatLog.of(emptyList(), running = true))
        // What it said so far stays, and the running turn goes on to say what it is doing.
        val working = rows + listOf(row("assistant", "我先去看看"), row("tool", title = "phone_ui_tree — 执行中", streaming = true))
        assertEquals(listOf(Kind.OWNER, Kind.OWNER, Kind.REPLY, Kind.THINKING), ChatLog.of(working, running = true).map { it.kind })
    }

    @Test fun whatItDidOnItsOwnInTheSameConversationStaysOutOfTheChat() {
        val rows = listOf(
            row("user", "帮我看看 QQ", source = "user"),
            row("assistant", "看过了，没有新消息。"),
            // A due patrol, then a notification joining it: its own runs, in 事件.
            row("user", "[巡视]\n逛逛 B 站", title = "巡视", source = "schedule"),
            row("tool", title = "phone_ui_tree — 完成"),
            row("notice", "[NOTIFICATION TRIGGER]", title = "cowork-notify", source = "webhook"),
            row("assistant", "巡视 B站：看了 3 个视频"),
            // The owner speaks in the middle of that run: their words and the reply after them are the chat.
            row("user", "先别逛了，几点了？", source = "user"),
            row("tool", title = "phone_ui_tree — 完成"),
            row("assistant", "下午三点。"),
            // A notification starts a run of its own after that.
            row("notice", "[NOTIFICATION TRIGGER]", title = "cowork-notify", source = "webhook"),
            row("assistant", "回了张三"),
            row("user", "[SELF NOTE]", title = "便条", source = "schedule"),
            row("assistant", "看过了"),
        )
        val log = ChatLog.of(rows, running = false)
        assertEquals(listOf("帮我看看 QQ", "看过了，没有新消息。", "先别逛了，几点了？", "下午三点。"), log.map { it.text })
        assertEquals(listOf(Kind.OWNER, Kind.REPLY, Kind.OWNER, Kind.REPLY), log.map { it.kind })
        assertFalse("its own run goes on: nothing to say in the chat", ChatLog.of(rows, running = true).any { it.kind == Kind.THINKING })
        val asked = rows.take(8)
        assertEquals("the owner just spoke in its run: it is busy answering", Kind.THINKING, ChatLog.of(asked, running = true).last().kind)
        // The 叫它 sheet shows the owner's last part only, and says what it is doing only while that part runs.
        assertEquals(listOf("先别逛了，几点了？", "", "下午三点。"), ChatLog.lastOwnerPart(rows).map { it.text })
        assertFalse(ChatLog.ownerSpeaking(rows))
        assertTrue(ChatLog.ownerSpeaking(asked))
        assertEquals(emptyList<TranscriptRow>(), ChatLog.lastOwnerPart(rows.drop(2).take(4)))
        // Other notices (a nudge, a summary of the context) do not start a part.
        val nudged = listOf(row("user", "在吗", source = "user"), row("notice", "[OWNER WAITING]", source = "cowork-screen"), row("assistant", "在"))
        assertEquals(listOf(Kind.OWNER, Kind.REPLY), ChatLog.of(nudged, running = false).map { it.kind })
    }

    @Test fun aNoteIsOnlyAWrittenScheduleCreate() {
        assertNull(ChatLog.noteOf(row("tool", title = "schedule_list — 完成", detail = "Arguments:\n{}")))
        assertNull(ChatLog.noteOf(note("""{"prompt":"  "}""")))
        assertNull(ChatLog.noteOf(row("tool", title = "schedule_create — 失败", detail = "Arguments:\n{\"prompt\":\"x\"}", error = true)))
        assertEquals(ChatLog.Note("回头看看他回没回", "到点"), ChatLog.noteOf(note("""{"title":"便条：看回复","prompt":"[便条] 回头看看他回没回","after_seconds":600}""")))
        assertEquals("每周", ChatLog.noteOf(note("""{"prompt":"x","weekly":{}}"""))?.timing)
    }
}
