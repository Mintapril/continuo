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

    /** The root broker (UID 0, started by app_process, no app record) cannot register the usual package listeners on
     *  Android 16 (see ProfileWorkspace.watchInstalls): a LauncherApps callback throws "Calling package name mismatch"
     *  because no package has UID 0, registerReceiver returns null without registering anything, and package-monitor
     *  callbacks skip UIDs outside the visibility allow list (application UIDs only). The install watch registered the
     *  first one from the initial commit on and only ever logged its failure; it uses a PackageInstaller session callback. */
    @Test fun noPackageListenerTheRootBrokerCannotRegister() {
        val forbidden = mapOf(
            "LauncherApps callback" to Regex("""\bLauncherApps\s*\.\s*Callback\b"""),
            "broadcast receiver" to Regex("""\bregisterReceiver\w*\s*\("""),
            "package-monitor callback" to Regex("""\bregisterPackageMonitorCallback\b"""),
        )
        val problems = ArrayList<String>()
        var sessionWatch = false
        for (file in sources()) {
            val code = file.readText().replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
            for ((what, pattern) in forbidden) if (pattern.containsMatchIn(code)) problems += "${file.name}: $what"
            if (file.name == "ProfileWorkspace.java" && code.contains(".registerSessionCallback(")) sessionWatch = true
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
        assertTrue("the install watch must register a PackageInstaller session callback", sessionWatch)
    }

    private fun code(name: String) = File(root, name).readText().replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
    /** The body of the method declared by [signature] (braces matched; these methods have none in strings). */
    private fun body(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++ else if (code[i] == '}' && --depth == 0) return code.substring(open, i + 1)
        }
        throw AssertionError("$signature has no end")
    }

    /** A phone_gesture stroke skips the full ownership check before its first event only because the caller has just
     *  made the same one (StrokePath.checkDue). Everything else stays: the window comparison before each later stroke,
     *  takeover and cancellation every frame, UID-targeted input on the owned display, the target check before the lift. */
    @Test fun gestureStrokesKeepTheirChecks() {
        val workspace = code("ProfileWorkspace.java")
        val stroke = body(workspace, "public synchronized void stroke(")
        val forced = Regex("""strokeCheck\(\s*uid\s*,\s*interrupted\s*,\s*0\s*\)""").findAll(stroke).toList()
        assertEquals("one forced full check per stroke", 1, forced.size)
        val lift = stroke.indexOf("MotionEvent.ACTION_UP")
        assertTrue("the forced check runs after the last MOVE, right before the lift", forced[0].range.first > stroke.lastIndexOf("ACTION_MOVE") && forced[0].range.first < lift && forced[0].range.first < stroke.indexOf("ACTION_POINTER_UP"))
        val injections = Regex("""ProfileInput\.fingers\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*,""").findAll(stroke).toList()
        assertTrue("stroke injects nothing?", injections.size >= 4)
        for (m in injections) assertEquals("every event targets the admitted UID on the owned display", listOf("id", "user", "uid"), m.groupValues.drop(1))
        assertTrue(Regex("""\bid\s*=\s*displayId\(\)""").containsMatchIn(stroke) && Regex("""\buser\s*=\s*profile\.userId\(\)""").containsMatchIn(stroke))
        assertTrue("takeover and cancellation are checked before the first DOWN", stroke.indexOf("strokeCheck(") in 0 until injections[0].range.first)

        val check = body(workspace, "private long strokeCheck(")
        val skip = check.indexOf("StrokePath.checkDue(")
        assertTrue("strokeCheck uses StrokePath.checkDue", skip > 0)
        assertTrue("takeover and cancellation every frame, before the full check may be skipped", check.indexOf("interrupted.getAsBoolean()") in 0 until skip && check.indexOf("cancellation.cancelled()") in 0 until skip)

        val reread = body(workspace, "public synchronized String strokeFingerprint(")
        val read = reread.indexOf("structure("); val full = reread.indexOf("check(true,false)")
        assertTrue("strokeFingerprint reads, makes a full check and reads again", read in 0 until full && full < reread.lastIndexOf("structure("))

        val gesture = body(code("BrokerMain.java"), "private JSONObject gesture(")
        val stamps = Regex("""\bchecked\s*=\s*SystemClock\.(\w+)\(\)""").findAll(gesture).toList()
        assertTrue("the check time is taken for the first and the later strokes", stamps.size >= 2)
        assertTrue("stroke() runs on the uptime clock", stamps.all { it.groupValues[1] == "uptimeMillis" })
        assertTrue("the first stroke's time follows fingerprint()", stamps.first().range.first > gesture.indexOf("workspace.fingerprint()"))
        val guard = gesture.indexOf("workspace.guard()"); val changed = gesture.indexOf("\"WINDOW_CHANGED\""); val call = gesture.indexOf("workspace.stroke(")
        assertTrue("a later stroke's time follows guard() and the window comparison", guard in 0 until changed && changed < stamps.last().range.first && stamps.last().range.first < call)
        assertTrue("stroke() gets that time", Regex("""workspace\.stroke\([^;]*,\s*checked\s*\)\s*;""").containsMatchIn(gesture))
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
