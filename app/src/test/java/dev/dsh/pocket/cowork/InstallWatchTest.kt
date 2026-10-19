package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.InstallWatch
import dev.dsh.pocket.cowork.broker.InstallWatch.Event
import org.junit.Assert.*
import org.junit.Test

/** The install watch sweeps the work profile (launcher entries, background pop-ups) exactly when one of its
 * package-installer sessions has installed something. */
class InstallWatchTest {
    @Test fun successfulSessionSweeps() {
        assertTrue(InstallWatch.sweeps(InstallWatch.finished(true)))
    }
    @Test fun failedSessionInstalledNothing() {
        assertFalse(InstallWatch.sweeps(InstallWatch.finished(false)))
    }
    @Test fun eventsBeforeTheInstallDoNotSweep() {
        for (event in listOf(Event.CREATED, Event.BADGING_CHANGED, Event.ACTIVE_CHANGED, Event.PROGRESS_CHANGED))
            assertFalse(event.name, InstallWatch.sweeps(event))
    }
    @Test fun onlyAnInstalledSessionSweeps() {
        assertEquals(listOf(Event.INSTALLED), Event.values().filter { InstallWatch.sweeps(it) })
    }

    // The 1 Hz change read (getChangedPackages): null means nothing changed for the profile since from().
    @Test fun firstReadIsTheBaseline() {
        val changes = InstallWatch.Changes()
        assertEquals(0, changes.from())
        assertFalse(changes.read(17))
        assertEquals(17, changes.from())
    }
    @Test fun aChangeAfterTheBaselineCounts() {
        val changes = InstallWatch.Changes()
        changes.read(17)
        assertTrue(changes.read(20))
        assertEquals(20, changes.from())
    }
    @Test fun nothingChangedKeepsTheCursor() {
        val changes = InstallWatch.Changes()
        changes.read(17)
        assertFalse(changes.read(null))
        assertEquals(17, changes.from())
        assertTrue(changes.read(18))
    }
    @Test fun profileWithoutEarlierChangesCountsItsFirstOne() {
        val changes = InstallWatch.Changes()
        assertFalse(changes.read(null))
        assertEquals(0, changes.from())
        assertTrue(changes.read(5))
    }
    @Test fun eachChangeCountsOnce() {
        val changes = InstallWatch.Changes()
        changes.read(3)
        assertTrue(changes.read(4))
        assertFalse(changes.read(null))
        assertTrue(changes.read(9))
        assertFalse(changes.read(null))
    }
}
