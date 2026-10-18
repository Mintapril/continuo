package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import org.junit.Assert.*
import org.junit.Test

class ActionGateTest {
    private fun gate() = ActionGate(listOf("dev.dsh.pocket.fixture"))
    private fun denied(code:String, action:()->Unit) { try { action(); fail("Expected $code") } catch (e:ActionGate.Denied) { assertEquals(code,e.code) } }
    @Test fun unknownCapabilitiesDenied() { for(t in listOf("shell","exec","subagent","mcp","phone.resume","phone.approve")) denied("TOOL_DENIED"){gate().checkTool(t)} }
    @Test fun ownerIsolation() { val g=gate();g.start("A");denied("SESSION_MISMATCH"){g.requireActive("B")};denied("SESSION_BUSY"){g.start("B")} }
    @Test fun singleUseObservation() { val g=gate();g.start("A");val id=g.observe("A","page",100);g.consume("A",id,"page",101);denied("STALE_OBSERVATION"){g.consume("A",id,"page",102)} }
    @Test fun invalidationOnScreenChange() { val g=gate();g.start("A");val id=g.observe("A","page",100);denied("STALE_OBSERVATION"){g.consume("A",id,"other",101)} }
    @Test fun allowsVisionLatencyWithinSixtySeconds() { val g=gate();g.start("A");val id=g.observe("A","page",100);g.consume("A",id,"page",60100) }
    @Test fun expiresAfterSixtySeconds() { val g=gate();g.start("A");val id=g.observe("A","page",100);denied("STALE_OBSERVATION"){g.consume("A",id,"page",60101)} }
    @Test fun pauseInvalidatesAndResumeRequiresReobserve() { val g=gate();g.start("A");val id=g.observe("A","page",100);g.pause("A");denied("PAUSED"){g.consume("A",id,"page",101)};g.resume("A");denied("STALE_OBSERVATION"){g.consume("A",id,"page",101)} }
    @Test fun stoppingReleasesOwner() { val g=gate();g.start("A");g.stop("A");assertNull(g.owner());g.start("B");g.requireActive("B") }
    @Test fun packagesFailClosed() { val g=gate();denied("APP_DENIED"){g.requirePackage("com.android.settings")};denied("APP_DENIED"){g.allowPackage("com.termux")};denied("APP_DENIED"){g.allowPackage("sh;rm")};g.requirePackage("dev.dsh.pocket.fixture") }
    @Test fun dangerousAppsAreIneligible() { for(pkg in listOf("dev.dsh.pocket","com.android.shell","com.android.packageinstaller","com.android.settings","me.weishu.kernelsu","com.termux")) { assertFalse(ActionGate.eligiblePackage(pkg));denied("APP_DENIED"){gate().allowPackage(pkg)} } }
    @Test fun revokeImmediatelyDeniesApp() { val g=gate();g.allowPackage("com.example.notes");g.requirePackage("com.example.notes");g.revokePackage("com.example.notes");denied("APP_DENIED"){g.requirePackage("com.example.notes")} }
    @Test fun noRollbackTime() { val g=gate();g.start("A");val id=g.observe("A","page",100);denied("STALE_OBSERVATION"){g.consume("A",id,"page",99)} }
}
