package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.LauncherEntrySweep
import org.junit.Assert.*
import org.junit.Test

/** The sweep rule behind "a work-profile install never shows an icon on the personal home screen": an entry is only
 * taken when it is still enabled, not ours yet and not running inside the owned display. */
class LauncherEntrySweepTest {
    private fun targets(vararg components:String,owned:Set<String> = emptySet(),inUse:Set<String> = emptySet(),disabled:Set<String> = emptySet()) =
        LauncherEntrySweep.targets(components.toList(),owned,inUse) { if (it in disabled) LauncherEntrySweep.DISABLED else 0 }

    @Test fun freshInstallIsTaken() {
        assertEquals(listOf("p/.A"),targets("p/.A"))
    }
    @Test fun alreadyHiddenEntryIsLeftAlone() {
        assertEquals(emptyList<String>(),targets("p/.A",owned=setOf("p/.A")))
    }
    @Test fun runningEntryIsNeverDisabled() {
        assertEquals(emptyList<String>(),targets("p/.A",inUse=setOf("p/.A")))
    }
    @Test fun entryDisabledBySomebodyElseIsNotOursToTouch() {
        assertEquals(emptyList<String>(),targets("p/.A",disabled=setOf("p/.A")))
    }
    @Test fun onlyTheVisibleOnesAreTaken() {
        assertEquals(listOf("b/.B","d/.D"),targets("a/.A","b/.B","c/.C","d/.D","e/.E",
            owned=setOf("a/.A"),inUse=setOf("c/.C"),disabled=setOf("e/.E")))
    }
    @Test fun existingHiddenEntriesStayUntouchedWhenANewAppArrives() {
        val hidden=setOf("a/.A","c/.C")
        assertEquals(listOf("new/.N"),targets("a/.A","c/.C","new/.N",owned=hidden))
    }
    @Test fun emptyProfileSweepsNothing() {
        assertEquals(emptyList<String>(),targets())
    }
}