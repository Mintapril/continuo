package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ProtectedTaskSweep
import dev.dsh.pocket.cowork.broker.StrayTaskSweep
import dev.dsh.pocket.cowork.broker.StrayTaskSweep.Task
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "a protected window nobody can use leaves the AI screen": only the work-profile task on the owned
 * display that the protection check names, never a personal task, a task elsewhere or the AI desktop. */
class ProtectedTaskSweepTest {
    private val profile = 10
    private val owned = 4
    private val desktop = "dev.dsh.pocket.isolation"
    private fun task(id: Int, user: Int = profile, display: Int = owned, pkg: String? = "com.zhihu.android", parent: Int = StrayTaskSweep.NO_PARENT) =
        Task(id, parent, user, display, pkg)
    private fun targets(named: Set<Int>, vararg tasks: Task, ownedDisplay: Int = owned) =
        ProtectedTaskSweep.targets(named, tasks.toList(), ownedDisplay, profile, desktop)

    @Test fun theTaskHoldingTheProtectedWindowIsRemoved() {
        assertEquals(listOf(1187), targets(setOf(1187), task(1188, pkg = desktop), task(1187), task(1185, pkg = "com.tencent.mobileqq")))
    }
    @Test fun aWindowOutsideAnyTaskRemovesNothing() {
        assertEquals(emptyList<Int>(), targets(setOf(-1), task(1187)))
    }
    @Test fun aPersonalTaskIsNeverRemoved() {
        assertEquals(emptyList<Int>(), targets(setOf(1191), task(1191, user = 0)))
    }
    @Test fun aTaskOnAnotherDisplayIsNotOurs() {
        assertEquals(emptyList<Int>(), targets(setOf(1178), task(1178, display = 0)))
    }
    @Test fun theDesktopStays() {
        assertEquals(emptyList<Int>(), targets(setOf(1188), task(1188, pkg = desktop)))
    }
    @Test fun nothingWithoutAnOwnedDisplay() {
        assertEquals(emptyList<Int>(), targets(setOf(1187), task(1187), ownedDisplay = -1))
    }
    /** The check names root tasks, the task list leaf tasks: a container is removed only when all it holds is ours. */
    @Test fun aContainerOfOurTasksIsRemovedAsAWhole() {
        assertEquals(listOf(3), targets(setOf(3), task(5, parent = 3), task(6, parent = 3, pkg = "com.tencent.mobileqq")))
        assertEquals(emptyList<Int>(), targets(setOf(3), task(5, parent = 3), task(6, parent = 3, user = 0)))
        assertEquals(emptyList<Int>(), targets(setOf(3), task(5, parent = 3), task(6, parent = 3, display = 0)))
        assertEquals(emptyList<Int>(), targets(setOf(3), task(5, parent = 3), task(6, parent = 3, pkg = desktop)))
        assertEquals("a task nested elsewhere is not the container", emptyList<Int>(), targets(setOf(3), task(5, parent = 7)))
    }
    @Test fun eachTaskOnce() {
        assertEquals(listOf(1187, 1190), targets(setOf(1187, 1190), task(1187), task(1190), task(1187)))
    }
}
