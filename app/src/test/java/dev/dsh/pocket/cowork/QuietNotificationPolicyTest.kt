package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.QuietNotificationPolicy
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "work-profile notifications stay off the main screen": every alerting channel of a third-party app
 * is held at MIN, so it is still posted for the notification triggers. Importances as read on the phone: QQ's 普通消息
 * channel is 4 (heads-up), 新消息通知 3 (sound), 系统静默通道 2. */
class QuietNotificationPolicyTest {
    private val owner = "dev.dsh.pocket.isolation"

    @Test fun thirdPartyAppsAreCoveredButNotSystemAppsOrTheProfileOwner() {
        assertTrue(QuietNotificationPolicy.covers("com.tencent.mobileqq", false, owner))
        assertFalse(QuietNotificationPolicy.covers("com.android.packageinstaller", true, owner))
        assertFalse(QuietNotificationPolicy.covers(owner, false, owner))
    }
    @Test fun everyChannelThatAlertsIsLoweredToMinAndBlockedOnesStayBlocked() {
        assertEquals(1, QuietNotificationPolicy.MIN)
        for (importance in listOf(5, 4, 3, 2)) assertTrue("importance $importance", QuietNotificationPolicy.lowers(importance))
        assertFalse("already MIN", QuietNotificationPolicy.lowers(1))
        assertFalse("blocked (NONE): lowering it to MIN would unblock it", QuietNotificationPolicy.lowers(0))
        assertFalse("UNSPECIFIED", QuietNotificationPolicy.lowers(-1000))
    }
}
