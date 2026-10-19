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
}
