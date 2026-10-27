package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.ChatKeeperStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings rows read the keeper through one fixed root command; these are its outputs. */
class ChatKeeperStatusTest {
    private val qq = "com.tencent.mobileqq"
    private val wechat = "com.tencent.mm"
    private val bili = "tv.danmaku.bili"
    private val label = { pkg: String -> ChatKeeperStatus.KNOWN[pkg] ?: if (pkg == bili) "哔哩哔哩" else pkg }
    private val defaults = "LIST $qq\nLIST $wechat\n"

    @Test fun runningWithBothAppsProtected() {
        val status = ChatKeeperStatus.parse("RUNNING pid=9878\n${defaults}PROTECTED $qq\nPROTECTED $wechat\n")
        assertEquals(State.RUNNING, status.state)
        assertEquals(listOf(qq, wechat), status.packages)
        assertEquals(setOf(qq, wechat), status.protectedPackages)
        assertTrue(status.on && status.switchable)
        assertEquals("QQ、微信在线，系统清后台时跳过它们", status.summary(label))
    }

    @Test fun runningWithOnlyQq() {
        val status = ChatKeeperStatus.parse("RUNNING pid=9878\n${defaults}PROTECTED $qq\n")
        assertEquals("QQ在线；微信没在运行，打开一次后才会保持", status.summary(label))
    }

    @Test fun runningWithNothingAlive() {
        assertEquals("QQ、微信都没在运行，打开一次后才会保持", ChatKeeperStatus.parse("RUNNING pid=1\n$defaults").summary(label))
        assertEquals("哔哩哔哩没在运行，打开一次后才会保持", ChatKeeperStatus.parse("RUNNING pid=1\nLIST $bili\n").summary(label))
    }

    @Test fun aSingleProtectedAppIsCalledIt() {
        assertEquals("哔哩哔哩在线，系统清后台时跳过它", ChatKeeperStatus.parse("RUNNING pid=1\nLIST $bili\nPROTECTED $bili\n").summary(label))
    }

    @Test fun anEmptyListSaysSo() {
        val status = ChatKeeperStatus.parse("RUNNING pid=1\n")
        assertTrue(status.packages.isEmpty())
        assertEquals("没选应用，什么都不保持", status.summary(label))
    }

    @Test fun theListIsReadLikeTheKeeperReadsIt() {
        val status = ChatKeeperStatus.parse("RUNNING pid=1\nLIST $bili\nLIST $bili\nLIST not-a-package\nLIST $qq\nPROTECTED $wechat\n")
        assertEquals(listOf(bili, qq), status.packages)
        assertTrue("a protected app off the list does not count", status.protectedPackages.isEmpty())
    }

    @Test fun disabledIsOffButKeepsTheList() {
        val status = ChatKeeperStatus.parse("DISABLED\n$defaults")
        assertEquals(State.DISABLED, status.state)
        assertEquals(listOf(qq, wechat), status.packages)
        assertFalse(status.on)
        assertTrue(status.switchable)
    }

    @Test fun stoppedStaysOnBecauseTheMonitorRestartsIt() {
        val status = ChatKeeperStatus.parse("STOPPED\n$defaults")
        assertTrue(status.on && status.switchable)
    }

    @Test fun busyKeepsTheSwitchStill() {
        val status = ChatKeeperStatus.parse("BUSY\n$defaults")
        assertTrue(status.on)
        assertFalse(status.switchable)
    }

    @Test fun notInstalledCannotBeSwitched() {
        val status = ChatKeeperStatus.parse("NOT_INSTALLED\n")
        assertFalse(status.on || status.switchable)
    }

    @Test fun unknownOutputIsAFailure() {
        val status = ChatKeeperStatus.parse("sh: dumpsys: inaccessible\n")
        assertEquals(State.FAILED, status.state)
        assertFalse(status.switchable)
        assertEquals("读不到保活状态", status.summary(label))
    }

    @Test fun protectedLinesAreIgnoredUnlessRunning() {
        assertTrue(ChatKeeperStatus.parse("STOPPED\n${defaults}PROTECTED $qq\n").protectedPackages.isEmpty())
    }
}
