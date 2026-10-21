package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.NotificationGrantPolicy
import dev.dsh.pocket.cowork.broker.NotificationGrantPolicy.App
import dev.dsh.pocket.cowork.broker.NotificationGrantPolicy.Installs
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "apps installed into the work profile from now on may post notifications": only a package that just
 * appeared, only when nobody decided yet. Flags as read on the phone: 0x300 = the two USER_SENSITIVE flags (undecided),
 * 0x301 = also USER_SET (denied in a prompt or Settings), 0x320 = GRANTED_BY_DEFAULT. */
class NotificationGrantPolicyTest {
    private val owner = "dev.dsh.pocket.isolation"
    private fun grants(app: App) = NotificationGrantPolicy.grants(app, owner)

    @Test fun undecidedAppIsGranted() {
        assertTrue(grants(App("com.kiwibrowser.browser", false, true, false, 0x300)))
        assertTrue(grants(App("p.fresh", false, true, false, 0)))
    }
    @Test fun aDecisionIsKept() {
        assertFalse(grants(App("com.zhihu.android", false, true, false, 0x301))) // USER_SET
        assertFalse(grants(App("p.a", false, true, false, 0x2))) // USER_FIXED
        assertFalse(grants(App("p.a", false, true, false, 0x4))) // POLICY_FIXED
        assertFalse(grants(App("p.a", false, true, false, 0x10))) // SYSTEM_FIXED
    }
    @Test fun grantedOrNotRequestedIsLeftAlone() {
        assertFalse(grants(App("tv.danmaku.bili", false, true, true, 0x320)))
        assertFalse(grants(App("com.unionpay.tsmservice", false, false, false, 0)))
    }
    @Test fun systemAppsTheProfileOwnerAndUnreadableFlagsAreSkipped() {
        assertFalse(grants(App("com.android.vending", true, true, false, 0)))
        assertFalse(grants(App(owner, false, true, false, 0)))
        assertFalse(grants(App("p.a", false, true, false, -1)))
    }
    @Test fun onlyTheFirstSightAfterTheBaselineIsAnInstall() {
        val installs = Installs(listOf("com.kiwibrowser.browser"))
        assertFalse("an app present at start (a launcher sweep, an update)", installs.appeared("com.kiwibrowser.browser", true))
        assertTrue(installs.appeared("p.new", true))
        assertFalse("named again by a later change", installs.appeared("p.new", true))
    }
    @Test fun anAppInstalledBackCountsAgain() {
        val installs = Installs(listOf("p.a"))
        assertFalse(installs.appeared("p.a", false))
        assertTrue(installs.appeared("p.a", true))
    }
}
