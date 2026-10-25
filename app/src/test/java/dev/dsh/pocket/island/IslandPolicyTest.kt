package dev.dsh.pocket.island

import org.junit.Assert.*
import org.junit.Test

class IslandPolicyTest {
    @Test fun controlsCannotResumeOrApproveAndTerminalTaskCannotBeStopped() {
        for (state in IslandTaskState.entries) {
            val task = IslandTaskUpdate("a", 1, state)
            assertEquals(state == IslandTaskState.RUNNING, IslandPolicy.allows(task, IslandAction.PAUSE))
            assertEquals(!state.terminal, IslandPolicy.allows(task, IslandAction.STOP))
            assertTrue(IslandPolicy.allows(task, IslandAction.OPEN_TASK))
        }
        assertEquals(setOf(IslandAction.PAUSE, IslandAction.STOP, IslandAction.OPEN_TASK), IslandAction.entries.toSet())
        assertFalse(IslandPolicy.allows(null, IslandAction.STOP))
    }

    @Test fun accessibilityListKeepsOtherServicesAndNeverDuplicates() {
        val island = "dev.dsh.pocket/dev.dsh.pocket.island.IslandAccessibilityService"
        val scene = "com.omarea.vtools/.AccessibilitySceneMode"
        assertEquals("$scene:$island", IslandAccessibilityList.withComponent(scene, island))
        assertEquals(island, IslandAccessibilityList.withComponent(null, island))
        assertEquals(island, IslandAccessibilityList.withComponent("null", island))
        assertEquals("$scene:$island", IslandAccessibilityList.withComponent(":$scene::", island))
        assertNull(IslandAccessibilityList.withComponent("$scene:$island", island))
        assertNull(IslandAccessibilityList.withComponent("dev.dsh.pocket/.island.IslandAccessibilityService", island))
        assertTrue(IslandAccessibilityList.shellSafe("$scene:$island:a.b/c.D\$E"))
        assertFalse(IslandAccessibilityList.shellSafe("$island'; reboot; '"))
        assertFalse(IslandAccessibilityList.shellSafe(""))
    }
}
