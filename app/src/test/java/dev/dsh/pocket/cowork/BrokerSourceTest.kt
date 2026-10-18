package dev.dsh.pocket.cowork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A duplicate element in `Set.of(...)` throws at class-initialisation time, which kills the whole root broker
 *  before it can log anything (observed once: BrokerMain.<clinit> failed, `main` became unreachable). The broker
 *  classes cannot be loaded on the JVM, so the source is checked instead. */
class BrokerSourceTest {
    // Gradle runs unit tests with the app module as working directory; fall back to the module path from the repo root.
    private val root = listOf(File("src/main/java/dev/dsh/pocket/cowork/broker"), File("app/src/main/java/dev/dsh/pocket/cowork/broker")).firstOrNull { it.isDirectory } ?: File("src/main/java/dev/dsh/pocket/cowork/broker")

    private fun sources(): List<File> {
        assertTrue("broker sources missing at ${root.absolutePath}", root.isDirectory)
        return root.listFiles { f -> f.extension == "java" }.orEmpty().sortedBy { it.name }
    }

    @Test fun noDuplicateSetElements() {
        val pattern = Regex("""\b(?:Set|Map)\.of\s*\(([^;]*?)\)\s*;""", RegexOption.DOT_MATCHES_ALL)
        val problems = ArrayList<String>()
        for (file in sources()) {
            val text = file.readText()
            // Comments are stripped so a quoted example in a comment cannot be mistaken for code.
            val code = text.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            for (match in pattern.findAll(code)) {
                val body = match.groupValues[1]
                val values = Regex(""""([A-Za-z0-9_.:${'$'}-]+)"""").findAll(body).map { it.groupValues[1] }.toList()
                if (values.isEmpty()) continue
                val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                if (duplicates.isNotEmpty()) problems += "${file.name}: duplicate ${duplicates.joinToString()}"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    /** The native tool sets and the plugin's tool tables must name the same agent tools. */
    @Test fun toolInventoryMatchesThePlugin() {
        val gate = File(root, "ActionGate.java").readText()
        val tools = Regex("""TOOLS\s*=\s*Set\.of\(([^)]*)\)""").find(gate)?.groupValues?.get(1)
            ?.let { body -> Regex(""""([a-z]+\.[a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toSet() }
            ?: emptySet()
        assertTrue("TOOLS not found", tools.isNotEmpty())
        val plugin = listOf(File("../cowork-plugin/src/broker.mjs"), File("cowork-plugin/src/broker.mjs")).firstOrNull { it.isFile } ?: File("../cowork-plugin/src/broker.mjs")
        val text = plugin.readText()
        val documented = Regex("""'(phone\.[a-z_]+)':\s*object""").findAll(text).map { it.groupValues[1] }.toSet()
        // phone.intervention_status is a native-only internal tool: declared in the plugin table, never registered for the model.
        assertEquals("agent tools differ between native and plugin", tools, documented)
    }
}
