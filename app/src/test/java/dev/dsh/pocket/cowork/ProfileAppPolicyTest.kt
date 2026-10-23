package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ProfileAppPolicy
import dev.dsh.pocket.cowork.broker.ProfileAppPolicy.Permission
import org.junit.Assert.*
import org.junit.Test

/** The rules behind phone.grant_permission and phone.uninstall_app: which apps the model manages and what a grant of
 * one permission group does. Flags as read on the phone: 0x300 = the two USER_SENSITIVE flags (undecided),
 * 0x301 = also USER_SET (the owner decided in a prompt or Settings). */
class ProfileAppPolicyTest {
    private val owner = "dev.dsh.pocket.isolation"
    private fun manageable(name: String, system: Boolean = false) = ProfileAppPolicy.manageable(name, system, owner)
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    @Test fun onlyThirdPartyAppsOfOthersAreManaged() {
        assertTrue(manageable("com.tencent.mobileqq"))
        assertTrue(manageable("com.tencent.android.qqdownloader"))
        assertFalse("system app", manageable("com.android.chrome", system = true))
        assertFalse("profile owner", manageable(owner))
        assertFalse("Continuo itself", manageable("dev.dsh.pocket"))
        assertFalse("the acceptance fixture", manageable("dev.dsh.pocket.fixture"))
        assertFalse("settings", manageable("com.android.settings"))
        assertFalse("a package installer", manageable("com.oplus.packageinstaller"))
        assertFalse("a shell", manageable("com.termux"))
        assertFalse(manageable("not a package"))
    }
    @Test fun groupsAreAnExplicitListOfRuntimePermissions() {
        assertEquals(setOf("location", "camera", "microphone", "storage", "contacts", "notifications"), ProfileAppPolicy.GROUPS.keys)
        val all = ProfileAppPolicy.GROUPS.values.flatten()
        assertEquals("a permission belongs to one group", all.size, all.toSet().size)
        assertTrue(all.all { it.matches(Regex("android\\.permission\\.[A-Z_]+")) })
        for (never in listOf("ACCESS_BACKGROUND_LOCATION", "SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE", "PACKAGE_USAGE_STATS",
            "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "CALL_PHONE", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "GET_ACCOUNTS",
            "BIND_ACCESSIBILITY_SERVICE", "REQUEST_INSTALL_PACKAGES", "WRITE_SETTINGS"))
            assertFalse(never, "android.permission.$never" in all)
    }
    @Test fun undecidedPermissionsOfTheGroupAreGrantedInGroupOrder() {
        val plan = ProfileAppPolicy.plan("location", listOf(Permission(coarse, false, 0x300), Permission("android.permission.CAMERA", false, 0), Permission(fine, false, 0)))
        assertEquals(listOf(fine, coarse), plan.grant())
        assertTrue(plan.held().isEmpty() && plan.kept().isEmpty())
        assertNull(plan.refusal())
    }
    @Test fun heldPermissionsAreReportedAndDecidedOnesKept() {
        val plan = ProfileAppPolicy.plan("location", listOf(Permission(fine, true, 0x300), Permission(coarse, false, 0x301)))
        assertEquals(listOf(fine), plan.held()); assertEquals(listOf(coarse), plan.kept()); assertTrue(plan.grant().isEmpty())
        assertNull("something of the group is held", plan.refusal())
        for (decided in listOf(0x1, 0x2, 0x4, 0x10, -1))
            assertEquals(listOf(fine), ProfileAppPolicy.plan("location", listOf(Permission(fine, false, decided))).kept())
    }
    @Test fun nothingToHoldIsARefusal() {
        assertEquals("The app does not request this permission", ProfileAppPolicy.plan("camera", listOf(Permission(fine, false, 0))).refusal())
        assertNotNull(ProfileAppPolicy.plan("camera", listOf(Permission("android.permission.CAMERA", false, 0x2))).refusal())
        assertTrue(ProfileAppPolicy.plan("anything", listOf(Permission(fine, false, 0))).grant().isEmpty())
    }
    @Test fun aDuplicateRequestCountsOnce() {
        val plan = ProfileAppPolicy.plan("notifications", listOf(Permission("android.permission.POST_NOTIFICATIONS", false, 0), Permission("android.permission.POST_NOTIFICATIONS", true, 0)))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), plan.grant())
    }
}
