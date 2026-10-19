package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.PopupPermissionPolicy
import dev.dsh.pocket.cowork.broker.PopupPermissionPolicy.App
import dev.dsh.pocket.cowork.broker.PopupPermissionPolicy.Change
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "a work-profile app that is not on the AI screen cannot open a screen anywhere": third-party apps
 * of the profile lose "display over other apps" and ColorOS's background pop-up permission; nothing else is touched. */
class PopupPermissionPolicyTest {
    private val owner = "dev.dsh.pocket.isolation"
    private val saw = PopupPermissionPolicy.OP_SYSTEM_ALERT_WINDOW
    private val bsa = PopupPermissionPolicy.OP_BACKGROUND_START_ACTIVITY
    private fun changes(vararg apps: App, modes: Map<Pair<String, Int>, Int> = emptyMap()) =
        PopupPermissionPolicy.changes(apps.toList(), owner) { app, op -> modes[app.packageName to op] ?: PopupPermissionPolicy.MODE_DEFAULT }

    @Test fun grantedPermissionsAreClosed() {
        val zhihu = App("com.zhihu.android", 1010362, false)
        assertEquals(listOf(Change("com.zhihu.android", 1010362, saw, 0, 2), Change("com.zhihu.android", 1010362, bsa, 0, 1)),
            changes(zhihu, modes = mapOf(("com.zhihu.android" to saw) to 0, ("com.zhihu.android" to bsa) to 0)))
    }
    @Test fun defaultModeIsClosedToo() {
        val store = App("com.tencent.android.qqdownloader", 1010378, false)
        assertEquals(listOf(Change(store.packageName, store.uid, saw, 3, 2), Change(store.packageName, store.uid, bsa, 3, 1)), changes(store))
    }
    @Test fun foregroundOnlyModeIsClosed() {
        val app = App("p.a", 1010001, false)
        assertEquals(2, changes(app, modes = mapOf(("p.a" to saw) to PopupPermissionPolicy.MODE_FOREGROUND, ("p.a" to bsa) to 1)).single().to)
    }
    @Test fun alreadyDeniedIsLeftAlone() {
        val app = App("p.a", 1010001, false)
        assertEquals(emptyList<Change>(), changes(app, modes = mapOf(("p.a" to saw) to 1, ("p.a" to bsa) to 2)))
    }
    @Test fun systemAppsAndTheProfileOwnerAreNotTouched() {
        assertEquals(emptyList<Change>(), changes(App("com.android.vending", 1010239, true), App(owner, 1010361, false)))
    }
    @Test fun unreadableOpIsSkipped() {
        val app = App("p.a", 1010001, false)
        assertEquals(listOf(Change("p.a", 1010001, saw, 3, 2)), changes(app, modes = mapOf(("p.a" to bsa) to -1)))
    }
}
