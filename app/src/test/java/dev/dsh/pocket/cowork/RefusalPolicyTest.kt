package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.RefusalPolicy
import org.junit.Assert.*
import org.junit.Test

class RefusalPolicyTest {
    @Test fun transitionsAreTransient(){
        assertTrue(RefusalPolicy.transientDenied("APP_UNKNOWN","No verified work-profile foreground"))
        assertTrue(RefusalPolicy.transientDenied("SCREEN_SETTLING",""))
        assertTrue(RefusalPolicy.transientDenied("APP_DENIED","Exact profile window ownership unavailable"))
        assertTrue(RefusalPolicy.transientDenied("APP_DENIED","Foreign task on profile display"))
    }
    /** A slow window or settings probe is a stall, not a verdict (this branch used to be unreachable). */
    @Test fun probeTimeoutIsTransientButIdentityChangeIsNot(){
        assertTrue(RefusalPolicy.transientDenied("SECURITY_UNAVAILABLE","Cannot verify owned-display window protection"))
        assertTrue(RefusalPolicy.transientDenied("SECURITY_UNAVAILABLE","Cannot verify system capture protection"))
        assertFalse(RefusalPolicy.transientDenied("SECURITY_UNAVAILABLE","Profile or owned display identity changed"))
        assertFalse(RefusalPolicy.transientDenied("SECURITY_UNAVAILABLE","Profile focus isolation unavailable"))
    }
    @Test fun boundaryViolationsPause(){
        for(message in listOf("App is not approved for this workspace","Verified managed-profile identity and exact window ownership required",
            "Verified work profile unavailable; no personal-user fallback","App is not installed in the work profile; call phone_apps"))
            assertFalse(message,RefusalPolicy.survives("APP_DENIED",message))
        for(code in listOf("SECURE_WINDOW","APP_CONFLICT","LOCKED","CONTROL_INTERRUPTED","CONTROLLER_OFFLINE","NO_DISPLAY"))
            assertFalse(code,RefusalPolicy.survives(code,"x"))
        for(code in listOf("INPUT_REJECTED","LAUNCH_TIMEOUT","NODE_STALE","STALE_OBSERVATION","SCREEN_SETTLING","TREE_REQUIRED"))
            assertTrue(code,RefusalPolicy.survives(code,"x"))
    }
    @Test fun onlySurvivedTransitionsAreReportedAsSettling(){
        assertEquals("SCREEN_SETTLING",RefusalPolicy.reported(ActionGate.Denied("APP_UNKNOWN","No verified work-profile foreground")).code)
        assertEquals("SCREEN_SETTLING",RefusalPolicy.reported(ActionGate.Denied("SECURITY_UNAVAILABLE","Cannot verify owned-display window protection")).code)
        // A refusal that paused the task must not claim "the session is still running".
        val paused=ActionGate.Denied("APP_DENIED","Verified managed-profile identity and exact window ownership required")
        assertSame(paused,RefusalPolicy.reported(paused))
        val stale=ActionGate.Denied("STALE_OBSERVATION","Profile window changed during capture")
        assertSame(stale,RefusalPolicy.reported(stale))
        val rejected=ActionGate.Denied("INPUT_REJECTED","x")
        assertSame(rejected,RefusalPolicy.reported(rejected))
    }
    @Test fun settlingExpiresOnlyAfterTheLimitOfOneRun(){
        val s=RefusalPolicy.Settling()
        assertFalse(s.expired(1_000))
        assertFalse(s.expired(1_000+RefusalPolicy.SETTLING_LIMIT_MS))
        assertTrue(s.expired(1_001+RefusalPolicy.SETTLING_LIMIT_MS))
    }
    /** The bug behind repeated "Turn aborted": a run left over from an earlier owner paused a new turn at its first check. */
    @Test fun resetStartsANewRun(){
        val s=RefusalPolicy.Settling()
        assertFalse(s.expired(1_000))
        s.reset()
        assertFalse(s.expired(600_000))
        assertFalse(s.expired(600_000+RefusalPolicy.SETTLING_LIMIT_MS))
        assertTrue(s.expired(600_001+RefusalPolicy.SETTLING_LIMIT_MS))
    }
    @Test fun clockRegressionRestartsTheRun(){
        val s=RefusalPolicy.Settling()
        assertFalse(s.expired(100_000))
        assertFalse(s.expired(50_000))
        assertFalse(s.expired(50_000+RefusalPolicy.SETTLING_LIMIT_MS))
    }
}
