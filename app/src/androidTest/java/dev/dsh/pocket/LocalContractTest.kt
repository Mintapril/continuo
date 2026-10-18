package dev.dsh.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Explicit deployment check: no prompt, provider call, API-key access or runtime restart. */
@RunWith(AndroidJUnit4::class)
class LocalContractTest {
    @Test fun authenticateStreamAndPreserveSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = File(context.filesDir, "deployment-login.seed")
        val vault = CredentialVault(context)
        val token = if (seed.exists()) seed.readText().trim().also {
            check(it.matches(Regex("[A-Za-z0-9_-]{16,}")))
            check(seed.delete()) { "Could not remove private deployment seed" }
        } else vault.load() ?: error("Private deployment seed not provided")
        val api = DshApi()
        api.login(token); vault.save(token)
        val ready = withTimeout(10_000) { api.stream("\$events", obj()).first { it.text("type") == "ready" } }
        assertTrue(ready.text("clientId").isNotEmpty())
        val catalog = api.rpc("session/modelCatalog") as org.json.JSONObject
        assertTrue(catalog.objects("groups").isNotEmpty())
        val workspace = withTimeout(10_000) { api.stream("workspace/follow", obj()).first() }
        assertEquals("baseline", workspace.text("type"))
        val created = api.call("session/create", obj("cwd" to "/workspace"))
        val id = created.getString("sessionId")
        val title = "DSH Pocket native contract check"
        val renamed = api.call("session/rename", obj("sessionId" to id, "title" to title))
        val snapshot = withTimeout(10_000) { api.stream("session/follow", obj("request" to obj("address" to obj("kind" to "session", "sessionId" to id), "assistantStream" to true, "maxMessages" to 50))).first() }
        assertEquals("snapshot", snapshot.text("type"))
        Transcript().accept(snapshot)
        val projection = api.call("session/projections", obj("sessionId" to id))
        assertEquals(title, projection.getJSONObject("values").text("title"))
        assertTrue(projection.optLong("asOfSeq") >= renamed.optLong("seq"))
        val jobs = withTimeout(10_000) { api.stream("job/list", obj("request" to obj("sessionId" to id))).first() }
        assertEquals("rows", jobs.text("type"))
        assertTrue(jobs.objects("jobs").isEmpty())
        api.call("workspace/archiveSession", obj("sessionId" to id))
        // Keep one non-destructive, archived acceptance session for restart persistence checks.
        File(context.filesDir, "contract-check.session").writeText(id)
        assertEquals(token, vault.load())
    }
}
