package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ParkedApps
import org.junit.Assert.*
import org.junit.Test

/** Apps parked on the owned display survive a display rebuild: the broker restarts them one by one and parks them
 *  behind the AI desktop again (seen on the phone: QQ left without an activity was killed by ColorOS minutes later). */
class ParkedAppsTest {
    private val qq = "com.tencent.mobileqq"
    private val bili = "tv.danmaku.bili"

    @Test fun theSavedFileIsReadLeniently() {
        assertEquals(listOf(qq, bili), ParkedApps(listOf(" $qq ", "", "not a package", "x;rm -rf", bili, qq)).list())
    }

    @Test fun whatIsSeenWhileParkedIsSavedOnlyWhenItChanges() {
        val parked = ParkedApps(emptyList())
        assertTrue(parked.seen(listOf(qq)))
        assertFalse(parked.seen(listOf(qq)))
        assertTrue("the AI closed an app", parked.seen(emptyList()))
        assertEquals(emptyList<String>(), parked.list())
    }

    @Test fun aNewDisplayRestoresEverySavedAppAndKeepsTheSetMeanwhile() {
        val parked = ParkedApps(listOf(qq, bili))
        parked.displayCreated()
        assertFalse("an empty new display must not overwrite the saved set", parked.seen(emptyList()))
        assertEquals(qq, parked.next())
        parked.restored(qq)
        assertFalse("still refilling", parked.seen(listOf(qq)))
        assertEquals(bili, parked.next())
        parked.restored(bili)
        assertNull(parked.next())
        assertFalse(parked.seen(listOf(qq, bili)))
        assertEquals(listOf(qq, bili), parked.list())
    }

    @Test fun aFailedRestoreIsRetriedBehindTheOthersThenGivenUp() {
        val parked = ParkedApps(listOf(qq, bili))
        parked.displayCreated()
        assertEquals(qq, parked.next()); parked.failed(qq)
        assertEquals("the others go first", bili, parked.next()); parked.restored(bili)
        repeat(ParkedApps.MAX_ATTEMPTS - 1) { assertEquals(qq, parked.next()); parked.failed(qq) }
        assertNull("given up after ${ParkedApps.MAX_ATTEMPTS} attempts", parked.next())
        assertEquals("still remembered for the next rebuild", listOf(qq, bili), parked.list())
        parked.displayCreated()
        assertEquals(qq, parked.next())
    }

    @Test fun anUninstalledAppIsForgotten() {
        val parked = ParkedApps(listOf(qq, bili))
        parked.displayCreated()
        assertTrue(parked.drop(qq))
        assertFalse(parked.drop(qq))
        assertEquals(bili, parked.next())
        assertEquals(listOf(bili), parked.list())
    }
}
