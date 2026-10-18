package dev.dsh.pocket.cowork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoworkLinkStateTest {
    @Test fun connectionLabelsDoNotDependOnTaskPresence() {
        assertEquals("未连接执行器",CoworkLinkState.DISCONNECTED.label)
        assertEquals("已连接执行器",CoworkLinkState.CONNECTED.label)
        assertNotEquals(CoworkLinkState.DISCONNECTED.label,CoworkLinkState.CONNECTED.label)
        assertTrue(CoworkLinkState.CONNECTED.hint.contains("暂无任务"))
        assertTrue(CoworkLinkState.CONNECTED.hint.contains("不会出现副屏"))
    }
    @Test fun duplicateConnectDisabledDuringAttemptAndHealthyConnection() {
        for(state in CoworkLinkState.entries) {
            assertEquals(state !in setOf(CoworkLinkState.STARTING,CoworkLinkState.AUTHENTICATING,
                CoworkLinkState.CONNECTING,CoworkLinkState.CONNECTED),state.canConnect)
        }
    }
    @Test fun successAppearsOnlyAfterAttemptFinishes()=runTest {
        val states=mutableListOf<CoworkLinkState>();var invalidated=false
        val value=connectWithFeedback({states.add(it)},{invalidated=true}) {
            assertEquals(listOf(CoworkLinkState.STARTING),states)
            states.add(CoworkLinkState.AUTHENTICATING);delay(50)
            states.add(CoworkLinkState.CONNECTING);delay(50)
            assertFalse(states.contains(CoworkLinkState.CONNECTED));"authenticated"
        }
        assertEquals("authenticated",value);assertFalse(invalidated)
        assertEquals(listOf(CoworkLinkState.STARTING,CoworkLinkState.AUTHENTICATING,
            CoworkLinkState.CONNECTING,CoworkLinkState.CONNECTED),states)
    }
    @Test fun failureInvalidatesAndNeverClaimsConnected()=runTest {
        val states=mutableListOf<CoworkLinkState>();var invalidated=false
        val problem=IllegalStateException("授权失败")
        try {
            connectWithFeedback({states.add(it)},{invalidated=true}) {throw problem}
            fail("Expected failure")
        } catch(e:IllegalStateException) {assertSame(problem,e)}
        assertTrue(invalidated);assertEquals(listOf(CoworkLinkState.STARTING,CoworkLinkState.FAILED),states)
    }
    @Test fun cancellationIsNotSwallowedOrReportedAsSuccess()=runTest {
        val states=mutableListOf<CoworkLinkState>();var invalidated=false
        val cancelled=CancellationException("Activity left")
        try {
            connectWithFeedback({states.add(it)},{invalidated=true}) {throw cancelled}
            fail("Expected cancellation")
        } catch(e:CancellationException) {assertSame(cancelled,e)}
        assertTrue(invalidated);assertEquals(listOf(CoworkLinkState.STARTING,CoworkLinkState.DISCONNECTED),states)
    }
    @Test fun stalledAttemptHasExplicitTimeoutError()=runTest {
        val states=mutableListOf<CoworkLinkState>();var invalidated=false
        try {
            connectWithFeedback({states.add(it)},{invalidated=true}) {delay(60_000)}
            fail("Expected timeout")
        } catch(e:IllegalStateException) {assertTrue(e.message.orEmpty().contains("连接超时"))}
        assertTrue(invalidated);assertEquals(listOf(CoworkLinkState.STARTING,CoworkLinkState.FAILED),states)
    }
}
