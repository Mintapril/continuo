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

    /** Takeover typing: the owned display keeps IME policy HIDE (the viewer raises this phone's keyboard instead), the
     *  focused-field report is metadata only, and a password field never takes a semantic (masked text + new) write. */
    @Test fun takeoverTypingKeepsTheImeHiddenAndNeverReadsFieldText() {
        val workspace = File(root, "ProfileWorkspace.java").readText()
        assertTrue(workspace.contains("setDisplayImePolicy\",int.class,int.class).invoke(wm,displayId(),2)"))
        val structure = File(root, "ProfileStructure.java").readText()
        val focused = structure.substringAfter("synchronized JSONObject focusedField(").substringBefore("synchronized void disconnect(")
        assertTrue(focused.contains("isPassword()"))
        assertTrue("focused-field report must not read or write text", !focused.contains("getText(") && !focused.contains("ACTION_SET_TEXT"))
        val append = structure.substringAfter("synchronized void appendFocused(").substringBefore("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE")
        assertTrue("appendFocused must refuse password fields before writing", append.contains("if(focus.isPassword())ActionGate.fail(\"SECRET_INPUT\""))
        val broker = File(root, "BrokerMain.java").readText()
        assertTrue(broker.contains("JSONObject field=takeover.active()?workspace.userField():null"))
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
     *  takeover and cancellation every frame, UID-targeted input on the owned display, a target check less than CHECK_MS
     *  before the lift. That check no longer sits between the last MOVE and the UP: a forced one there (about 90 ms)
     *  held the finger still long enough for Android to drop the fling velocity, and made every press longer. */
    @Test fun gestureStrokesKeepTheirChecks() {
        val workspace = code("ProfileWorkspace.java")
        val stroke = body(workspace, "public synchronized long stroke(")
        val checks = Regex("""strokeCheck\(\s*uid\s*,\s*interrupted\s*,\s*(\w+)\s*,([^;]*),\s*(true|false)\s*\)""").findAll(stroke).toList()
        assertTrue("every check counts from the last full check; none is forced", checks.size >= 3 && checks.all { it.groupValues[1] == "checked" })
        assertEquals("all checks of a stroke go through strokeCheck with its lift", checks.size, Regex("""strokeCheck\(""").findAll(stroke).count())
        val lift = stroke.indexOf("MotionEvent.ACTION_UP"); val last = checks.last()
        assertTrue("the lift's own check follows the last MOVE and takes the lift as now (only the CHECK_MS rule)", last.range.first > stroke.lastIndexOf("ACTION_MOVE") && last.range.first < lift && last.range.first < stroke.indexOf("ACTION_POINTER_UP") && last.groupValues[2].trim() == "SystemClock.uptimeMillis()" && last.groupValues[3] == "true")
        assertTrue("the hold before the lift passes its check time on", Regex("""checked\s*=\s*rest\(\s*uid\s*,\s*holdEndMs""").containsMatchIn(stroke))
        assertTrue("the last frame ends on the movement's end", Regex("""SystemClock\.sleep\(\s*Math\.max\(\s*0\s*,\s*Math\.min\(\s*16\s*,\s*end\s*-\s*SystemClock\.uptimeMillis\(\)\s*\)\s*\)\s*\)""").containsMatchIn(stroke))
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

    /** A tap and a stroke that goes down near it within 300 ms are a double tap for Android. Since presses last as long
     *  as declared, two taps in one phone_gesture came that close: the later stroke waits until DOUBLE_TAP_MS have passed
     *  since the app had the tap's UP, counted in the call's time budget and ending after the checks before the stroke. */
    @Test fun nearbyTapsStayTwoTaps() {
        val stroke = body(code("ProfileWorkspace.java"), "public synchronized long stroke(")
        assertTrue("stroke() returns when the app had the UP", stroke.lastIndexOf("return SystemClock.uptimeMillis();") > stroke.lastIndexOf("MotionEvent.ACTION_UP"))
        val gesture = body(code("BrokerMain.java"), "private JSONObject gesture(")
        assertTrue("the wait counts from that time", Regex("""lifted\s*=\s*workspace\.stroke\(""").containsMatchIn(gesture))
        val wait = Regex("""notBefore\s*=\s*StrokePath\.doubleTap\(\s*fingers\[i-1]\s*,\s*fingers\[i]\s*,[^;]*\?\s*lifted\s*\+\s*StrokePath\.DOUBLE_TAP_MS\s*\+\s*1\s*:\s*0\s*;""").find(gesture)
        assertTrue("strictly more than DOUBLE_TAP_MS after a tap, only when the next stroke would make a double tap", wait != null)
        val before = gesture.indexOf("notBefore-checks-SystemClock.uptimeMillis()"); val limit = gesture.indexOf("\"GESTURE_TIME_LIMIT\"")
        assertTrue("most of the wait comes before the time limit check, so it counts in the call's budget", before in 0 until limit)
        val last = Regex("""\bchecked\s*=\s*SystemClock\.uptimeMillis\(\)""").findAll(gesture).last().range.first
        val after = gesture.indexOf("SystemClock.sleep(Math.max(0,notBefore-checked))")
        assertTrue("the rest of it ends after the checks, right before the stroke", after > last && after < gesture.indexOf("workspace.stroke("))
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
