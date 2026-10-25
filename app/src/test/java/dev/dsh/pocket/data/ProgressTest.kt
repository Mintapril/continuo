package dev.dsh.pocket.data

import dev.dsh.pocket.data.Progress.Step
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ProgressTest {
    private var n = 0
    private fun row(role: String, text: String = "", title: String = "", detail: String = "", streaming: Boolean = false, reasoning: String = "") =
        TranscriptRow("r${n++}", role, title, text, reasoning, detail, streaming)
    private fun tool(name: String, args: String = "{}", running: Boolean = true) =
        row("tool", title = "$name — ${if (running) "执行中" else "完成"}", detail = "Call ID: c\n\nArguments:\n$args", streaming = running)

    @Test fun theStepComesFromTheLastRowOfTheTurn() {
        val said = row("user", "帮我看看 QQ")
        fun at(vararg rows: TranscriptRow, queued: Boolean = false) = Progress.now(listOf(row("assistant", "上一轮"), said) + rows, running = true, queued = queued)
        assertNull(Progress.now(listOf(said), running = false))
        assertEquals(Step.SENT, Progress.now(emptyList(), running = false, sent = true)?.step)
        // A message on its way and the start of its turn keep one key, so the words stay when it shows up.
        assertEquals(Progress.now(emptyList(), running = false, sent = true)?.key, at()?.key)
        assertEquals(Step.SENT, at()?.step)
        assertEquals(Step.QUEUED, at(queued = true)?.step)
        assertEquals(Step.THINKING, at(row("assistant", reasoning = "嗯", streaming = true))?.step)
        assertEquals(Step.REPLYING, at(row("assistant", "好的，我", streaming = true))?.step)
        assertEquals(Step.THINKING, at(row("assistant", "我去看看"))?.step)
        assertEquals(Step.LOOKING, at(tool("phone_ui_tree"))?.step)
        val launch = at(tool("phone_launch_app", """{"packageName":"com.tencent.mobileqq"}"""))
        assertEquals(Step.OPENING, launch?.step)
        assertEquals("com.tencent.mobileqq", launch?.target)
        assertEquals(Step.BACK, at(tool("phone_key", """{"key":"BACK"}"""))?.step)
        assertEquals(Step.HOME, at(tool("phone_key", """{"key":"HOME"}"""))?.step)
        assertEquals(Step.NOTE_WRITE, at(tool("schedule_create"))?.step)
        assertEquals(Step.MEMORY, at(tool("memory_search"))?.step)
        assertEquals(Step.BUSY, at(tool("something_new"))?.step)
        // A finished call: deciding what next. Each call is a step of its own, even of the same kind.
        val done = at(tool("phone_tap", running = false))
        assertEquals(Step.THINKING, done?.step)
        assertNotEquals(at(tool("phone_tap"))?.key, at(tool("phone_tap"))?.key)
        assertEquals(Step.COMPACTING, at(row("notice", title = "正在整理上下文"))?.step)
        assertEquals(Step.RETRYING, at(row("notice", title = "正在重试模型请求"))?.step)
        assertEquals(Step.THINKING, at(row("notice", title = "上下文整理结束"))?.step)
    }

    @Test fun everyLineIsItsOwnAndEveryStepHasWords() {
        val all = Progress.POOLS.values.flatten()
        assertEquals("a line in two places: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}", all.size, all.toSet().size)
        Step.entries.forEach { step -> assertTrue("$step has too few lines", Progress.lines(step, null).size >= 4) }
        // The busiest steps have the most to say.
        listOf(Step.THINKING, Step.LOOKING, Step.TAPPING, Step.TYPING, Step.SCROLLING).forEach { assertTrue(Progress.lines(it, null).size >= 10) }
        assertTrue(Progress.lines(Step.OPENING, "QQ").contains("打开 QQ，冲"))
        assertFalse(Progress.lines(Step.OPENING, null).any { "{app}" in it })
        assertFalse(Progress.lines(Step.OPENING, " ").any { "{app}" in it })
    }

    @Test fun aTurnNeverRepeatsALineUntilItsPoolIsSpent() {
        val book = Progress.Phrasebook(Random(7))
        val pool = Progress.lines(Step.TAPPING, "QQ")
        val shown = (pool.indices).map { book.pick(Step.TAPPING, "QQ") }
        assertEquals(pool.toSet(), shown.toSet())
        // Spent: it starts over, but never with the line just shown.
        val next = book.pick(Step.TAPPING, "QQ")
        assertNotEquals(shown.last(), next)
        // A step lasting goes on to the still-going lines once its own are spent.
        val thinking = Progress.lines(Step.THINKING, null)
        val lasting = (0 until thinking.size + 3).map { book.pick(Step.THINKING, null, lasting = true) }
        assertEquals(thinking.toSet(), lasting.take(thinking.size).toSet())
        assertTrue(lasting.drop(thinking.size).all { it in Progress.lines(Step.STILL, null) })
        assertEquals(lasting.size, lasting.toSet().size)
        // Never twice in a row, over a long turn of mixed steps.
        val steps = listOf(Step.LOOKING, Step.TAPPING, Step.THINKING, Step.TYPING, Step.BACK)
        val words = (0 until 400).map { book.pick(steps[it % steps.size], "QQ", lasting = it % 3 == 0) }
        assertTrue(words.zipWithNext().none { (a, b) -> a == b })
        // A new turn starts fresh.
        book.reset()
        val single = Progress.lines(Step.KEY, null)
        assertEquals(single.size, (single.indices).map { book.pick(Step.KEY, null) }.toSet().size)
    }
}
