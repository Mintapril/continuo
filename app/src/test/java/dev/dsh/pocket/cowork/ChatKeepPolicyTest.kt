package dev.dsh.pocket.cowork

import dev.dsh.notifykeep.ChatKeepPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which processes the keeper pins and how it reads its app list, plus APK-owned installation contracts; no Android lifecycle mocks. */
class ChatKeepPolicyTest {
    private val qq = "com.tencent.mobileqq"
    private val wx = "com.tencent.mm"
    private val reason = "my-reason"
    private val sample = "  *APP* UID 1010370 ProcessRecord{ab 123:com.tencent.mm/u10a370}\n" +
        "    cached=false empty=false\n" +
        "    mHasForegroundServices=false forcingToImportant=ImportanceToken { ac foreign-owner 123 binder }\n" +
        "  Foreground Processes:\n" +
        "    PID #999: ImportanceToken { zz my-reason 999 binder }\n"

    @Test fun qqMainIsAccepted() {
        assertTrue(ChatKeepPolicy.accepts(qq, qq, 1010324, 1010324, true, false, false))
    }

    @Test fun qqMsfIsAccepted() {
        assertTrue(ChatKeepPolicy.accepts(qq, "$qq:MSF", 1010324, 1010324, true, false, false))
    }

    @Test fun wechatMainIsAccepted() {
        assertTrue(ChatKeepPolicy.accepts(wx, wx, 1010370, 1010370, true, false, false))
    }

    @Test fun wechatPushIsAccepted() {
        assertTrue(ChatKeepPolicy.accepts(wx, "$wx:push", 1010370, 1010370, true, false, false))
    }

    @Test fun wechatPatchIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, "$wx:patch", 1010370, 1010370, true, false, false))
    }

    @Test fun wechatBrIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, "$wx:br", 1010370, 1010370, true, false, false))
    }

    @Test fun qqMsfPrefixDoesNotMatchAnotherProcess() {
        assertFalse(ChatKeepPolicy.accepts(qq, "$qq:MSF.fake", 1010324, 1010324, true, false, false))
    }

    @Test fun personalProfileIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, wx, 10370, 10370, true, false, false))
    }

    @Test fun fullUidMustMatchThePackage() {
        assertFalse(ChatKeepPolicy.accepts(wx, wx, 1010370, 1010324, true, false, false))
    }

    @Test fun forceStoppedPackageIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, wx, 1010370, 1010370, true, true, false))
    }

    @Test fun disabledPackageIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, wx, 1010370, 1010370, false, false, false))
    }

    @Test fun suspendedPackageIsExcluded() {
        assertFalse(ChatKeepPolicy.accepts(wx, wx, 1010370, 1010370, true, false, true))
    }

    @Test fun anotherListedAppKeepsItsMainAndPushProcessesOnly() {
        val bili = "tv.danmaku.bili"
        assertTrue(ChatKeepPolicy.accepts(bili, bili, 1010360, 1010360, true, false, false))
        assertTrue(ChatKeepPolicy.accepts(bili, "$bili:pushservice", 1010360, 1010360, true, false, false))
        assertFalse(ChatKeepPolicy.accepts(bili, "$bili:web", 1010360, 1010360, true, false, false))
        assertFalse("another app's process", ChatKeepPolicy.accepts(bili, "$qq:MSF", 1010360, 1010360, true, false, false))
    }

    @Test fun theDefaultListIsQqAndWechat() {
        assertEquals(listOf(qq, wx), ChatKeepPolicy.DEFAULT_PACKAGES)
    }

    @Test fun theListFileSkipsBlanksCommentsInvalidNamesAndDuplicates() {
        val text = "\n# chat apps\n $qq \ncom.tencent.mm\n$qq\nnot-a-package\nx; rm -rf /\ntv.danmaku.bili\n"
        assertEquals(listOf(qq, wx, "tv.danmaku.bili"), ChatKeepPolicy.parsePackages(text))
    }

    @Test fun theListIsCapped() {
        val text = (1..20).joinToString("\n") { "com.example.app$it" }
        assertEquals(ChatKeepPolicy.MAX_PACKAGES, ChatKeepPolicy.parsePackages(text).size)
    }

    @Test fun anEmptyListPinsNothing() {
        assertTrue(ChatKeepPolicy.parsePackages("\n").isEmpty())
    }

    @Test fun onlyPackageNamesAreShellSafe() {
        assertTrue(ChatKeepPolicy.validName("com.tencent.mobileqq"))
        listOf("", "qq", "com..qq", "com.qq'", "com.qq;id", "com.qq id", "1com.qq", "com.qq\n").forEach {
            assertFalse(it, ChatKeepPolicy.validName(it))
        }
    }

    @Test fun startTicksUsesField22AfterTheLastClosingParenthesis() {
        val stat = buildString {
            append("123 (weird ) process) S")
            for (i in 4..21) append(" 0")
            append(" 987654 0 0")
        }
        assertEquals(987654L, ChatKeepPolicy.startTicks(stat))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedProcStatIsRejected() {
        ChatKeepPolicy.startTicks("short")
    }

    @Test fun anotherImportanceOwnerIsNotConfusedWithTheForegroundSummary() {
        val parsed = requireNotNull(ChatKeepPolicy.parseStates(sample, reason)[123])
        assertFalse(parsed.ours())
        assertTrue(parsed.other())
        assertFalse(parsed.fgs())
    }

    @Test fun ourImportanceTokenIsRecognised() {
        val parsed = requireNotNull(ChatKeepPolicy.parseStates(sample.replace("foreign-owner", reason), reason)[123])
        assertTrue(parsed.ours())
        assertFalse(parsed.other())
    }

    @Test fun foregroundServiceStateIsRecognised() {
        val parsed = requireNotNull(ChatKeepPolicy.parseStates(sample.replace("mHasForegroundServices=false", "mHasForegroundServices=true"), reason)[123])
        assertTrue(parsed.fgs())
    }

    @Test fun dumpWithoutProcessRecordsIsEmpty() {
        assertTrue(ChatKeepPolicy.parseStates("ACTIVITY MANAGER RUNNING PROCESSES\n", reason).isEmpty())
    }

    // Supports both repository-root JUnitCore and app-module working directories.
    private fun source(path: String): String {
        val root = listOf(File("."), File("..")).firstOrNull {
            File(it, "app/build.gradle.kts").isFile && File(it, "runtime/notification-keeper/assets").isDirectory
        } ?: throw AssertionError("repository sources missing from ${File(".").absolutePath}")
        val file = File(root, path)
        assertTrue("source missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test fun keeperLifecycleAssetsArePackagedInTheApp() {
        val build = source("app/build.gradle.kts")
        assertTrue("main assets must include the project-owned keeper scripts", Regex(
            """sourceSets\s*\.\s*getByName\("main"\)\s*\.\s*assets\s*\.\s*srcDir\("\.\./runtime/notification-keeper/assets"\)"""
        ).containsMatchIn(build))
    }

    @Test fun connectInstallsTheKeeperEvenWhenTheControllerIsAlreadyConnected() {
        val connection = source("app/src/main/java/dev/dsh/pocket/cowork/CoworkConnection.kt")
        val signature = "internal suspend fun connect(context:Context)"
        assertTrue("connect entry point missing", connection.contains(signature))
        val connect = connection.substringAfter(signature).substringBefore("\n    }")
            .replace(Regex("""//[^\n]*"""), "").replace(Regex("""\s+"""), "")
        assertTrue("install must use the application context on IO", connect.contains("valapp=context.applicationContext") &&
            connect.contains("withContext(Dispatchers.IO){ChatKeeperRuntime.ensureInstalled(app)}"))
        val install = connect.indexOf("ChatKeeperRuntime.ensureInstalled(app)")
        val connectedReturn = connect.indexOf("if(link.value==CoworkLinkState.CONNECTED)return")
        assertTrue("a healthy connection must still install updated keeper assets", install >= 0 && connectedReturn > install)
    }

    @Test fun keeperLaunchesFromTheCurrentlyInstalledApkRatherThanASeparateJar() {
        val script = source("runtime/notification-keeper/assets/chat-notify-keeper/keeper.sh")
        assertTrue("resolve the installed APK on every start", script.contains("APK=\$(pm path --user 0 dev.dsh.pocket"))
        assertTrue("run the keeper class from the APK", script.contains("CLASS=dev.dsh.notifykeep.ChatNotificationKeeper") &&
            script.contains("CLASSPATH=\"\$APK\" /system/bin/nohup /system/bin/app_process /system/bin \"\$CLASS\""))
        assertTrue("detect APK updates", script.contains("sha256sum \"\$APK\"") && script.contains("\"\$HASH\" = \"\$OLD\""))
        assertFalse("the shipped lifecycle must not depend on keeper.jar", script.contains("keeper.jar"))
    }
}
