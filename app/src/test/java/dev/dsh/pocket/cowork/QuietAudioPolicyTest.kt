package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.PopupPermissionPolicy
import dev.dsh.pocket.cowork.broker.QuietAudioPolicy
import dev.dsh.pocket.cowork.broker.QuietAudioPolicy.App
import dev.dsh.pocket.cowork.broker.QuietAudioPolicy.Change
import org.junit.Assert.*
import org.junit.Test

/** The rule behind "nothing the AI plays on its screen sounds on the phone": every third-party app of the work profile
 * except its owner gets OP_PLAY_AUDIO ignored; system apps, the owner and modes that deny already are left alone. */
class QuietAudioPolicyTest {
    private val owner = "dev.dsh.pocket.isolation"
    private fun changes(vararg apps: App, modes: Map<String, Int> = emptyMap()) =
        QuietAudioPolicy.changes(apps.toList(), owner) { app -> modes[app.packageName] ?: PopupPermissionPolicy.MODE_DEFAULT }

    @Test fun theOpIsPlayAudio() {
        assertEquals(28, QuietAudioPolicy.OP_PLAY_AUDIO)
        assertEquals("android:play_audio", QuietAudioPolicy.OP_NAME)
    }
    @Test fun allowedAndDefaultAreSilenced() {
        val bili = App("tv.danmaku.bili", 1010370, false); val qq = App("com.tencent.mobileqq", 1010365, false)
        assertEquals(listOf(Change("tv.danmaku.bili", 1010370, PopupPermissionPolicy.MODE_ALLOWED), Change("com.tencent.mobileqq", 1010365, PopupPermissionPolicy.MODE_DEFAULT)),
            changes(bili, qq, modes = mapOf("tv.danmaku.bili" to PopupPermissionPolicy.MODE_ALLOWED)))
        assertEquals(1, changes(App("p.a", 1010001, false), modes = mapOf("p.a" to PopupPermissionPolicy.MODE_FOREGROUND)).size)
    }
    @Test fun alreadySilentIsLeftAlone() {
        val a = App("p.a", 1010001, false); val b = App("p.b", 1010002, false)
        assertEquals(emptyList<Change>(), changes(a, b, modes = mapOf("p.a" to PopupPermissionPolicy.MODE_IGNORED, "p.b" to PopupPermissionPolicy.MODE_ERRORED)))
    }
    @Test fun systemAppsTheProfileOwnerAndUnreadableModesAreNotTouched() {
        assertEquals(emptyList<Change>(), changes(App("com.android.vending", 1010239, true), App(owner, 1010361, false)))
        assertEquals(emptyList<Change>(), changes(App("p.a", 1010001, false), modes = mapOf("p.a" to -1)))
    }
}
