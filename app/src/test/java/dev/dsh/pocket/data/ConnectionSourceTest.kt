package dev.dsh.pocket.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's DSH connection (PocketViewModel) cannot load on the JVM, so its source is checked instead.
 *  2026-11-01: `session/modelCatalog` took 10–20 s on the phone and sat inside the 12 s ready barrier; the timeout
 *  (a CancellationException) passed through the supervisor's rethrow and ended it, so the app never reconnected on
 *  its own until it was restarted. */
class ConnectionSourceTest {
    private fun source(path: String): String {
        val root = listOf(File("."), File("..")).firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: throw AssertionError("repository sources missing from ${File(".").absolutePath}")
        return File(root, path).readText()
    }
    private val model get() = source("app/src/main/java/dev/dsh/pocket/PocketViewModel.kt")

    /** withTimeout throws TimeoutCancellationException, which every `catch (CancellationException) { throw e }` passes on. */
    @Test fun connectionCodeHasNoTimeoutThatPassesAsCancellation() {
        for (path in listOf("app/src/main/java/dev/dsh/pocket/PocketViewModel.kt", "app/src/main/java/dev/dsh/pocket/data/RuntimeController.kt"))
            assertFalse("$path: use withTimeoutOrNull", Regex("""withTimeout\(""").containsMatchIn(source(path)))
    }

    @Test fun onlyItsOwnCancellationEndsTheSupervisor() {
        val once = model.substringAfter("private suspend fun autoConnectOnce()").substringBefore("private fun startSupervisor()")
        assertFalse(once.contains("catch (e: CancellationException) { throw e }"))
        assertTrue(Regex("""catch \(e: CancellationException\) \{ currentCoroutineContext\(\)\.ensureActive\(\) \}""").findAll(once).count() == 2)
    }

    /** Only what the app needs to call itself connected runs before `ready.complete`; the model catalog loads after. */
    @Test fun theModelCatalogDoesNotHoldUpTheConnection() {
        val barrier = model.substringAfter("\"ready\" -> {").substringBefore("ready.complete(Unit)")
        assertFalse(barrier.contains("session/modelCatalog"))
        val after = model.substringAfter("ready.complete(Unit)").substringBefore("\"waterfall\" ->")
        assertTrue(after.contains("streams += viewModelScope.launch") && after.contains("session/modelCatalog"))
    }
}
