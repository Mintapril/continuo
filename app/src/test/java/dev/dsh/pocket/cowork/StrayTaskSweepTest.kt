package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.StrayTaskSweep
import dev.dsh.pocket.cowork.broker.StrayTaskSweep.Task
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "a work-profile activity that lands on the main screen is moved to the AI screen instead of
 * pausing the turn": only root tasks of the work profile on the main screen, never a personal task. */
class StrayTaskSweepTest {
    private val profile = 10
    private val owned = 9
    private val owner = "dev.dsh.pocket.isolation"
    private fun task(id: Int, user: Int = profile, display: Int = 0, parent: Int = StrayTaskSweep.NO_PARENT, pkg: String? = "com.example.store") =
        Task(id, parent, user, display, pkg)
    private fun targets(vararg tasks: Task, ownedDisplay: Int = owned) =
        StrayTaskSweep.targets(tasks.toList(), ownedDisplay, profile, owner)

    @Test fun profileTaskOnTheMainScreenIsTaken() {
        assertEquals(listOf(653), targets(task(653)))
    }
    @Test fun personalTaskIsNeverTaken() {
        assertEquals(emptyList<Int>(), targets(task(644, user = 0)))
    }
    @Test fun taskAlreadyOnTheOwnedDisplayIsLeftAlone() {
        assertEquals(emptyList<Int>(), targets(task(656, display = owned)))
    }
    @Test fun anotherVirtualDisplayIsNotOurs() {
        assertEquals(emptyList<Int>(), targets(task(70, display = 5)))
    }
    @Test fun nothingMovesWithoutAnOwnedDisplay() {
        assertEquals(emptyList<Int>(), targets(task(653), ownedDisplay = -1))
    }
    @Test fun nestedTaskMayShareItsContainerAndStays() {
        assertEquals(emptyList<Int>(), targets(task(4, parent = 2)))
    }
    @Test fun unreadableParentIsTreatedAsNested() {
        assertEquals(emptyList<Int>(), targets(task(4, parent = Int.MIN_VALUE)))
    }
    @Test fun profileOwnerSetupScreensStayOnTheMainScreen() {
        assertEquals(emptyList<Int>(), targets(task(80, pkg = owner)))
    }
    @Test fun eachRootTaskIsTakenOnce() {
        assertEquals(listOf(653, 660), targets(task(653), task(653), task(644, user = 0), task(660, pkg = null), task(656, display = owned)))
    }
}
