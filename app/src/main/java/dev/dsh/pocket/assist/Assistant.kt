package dev.dsh.pocket.assist

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.dsh.pocket.CallActivity
import dev.dsh.pocket.CallOverlay
import dev.dsh.pocket.R

/**
 * Continuo as the phone's digital assistant (系统设置 → 默认应用 → 数字助理, the owner's choice): the system's assist
 * gestures bring up 叫它 over whatever is on screen. The session itself shows nothing: it puts up the same sheet the
 * tile and the status card use ([CallOverlay]), or CallActivity without the overlay permission, and hides at once.
 * Nothing on the owner's screen is collected (no assist structure, no screenshot), and nothing opens while the phone
 * is locked: whoever picks up a locked phone cannot give it orders.
 */
class AssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    companion object {
        /** Whether the owner made Continuo the phone's assistant. */
        fun isAssistant(context: Context): Boolean =
            context.getSystemService(RoleManager::class.java)?.let { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT) } == true

        /** The system page where the owner picks the assistant (the role cannot be requested by an app). */
        fun settingsIntent(context: Context): Intent? = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.firstOrNull { it.resolveActivity(context.packageManager) != null }
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    init { setTheme(R.style.Theme_Continuo_Sheet) }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked && !CallOverlay.show(context)) runCatching { startAssistantActivity(CallActivity.call(context)) }
            .onFailure { android.util.Log.w("ContinuoAssist", "the call sheet could not be shown", it) }
        hide()
    }
}

/**
 * The system requires an assistant to name a speech recognizer. This one is never used: not exported and without an
 * intent filter, so it can never become the phone's recognizer (Continuo's own speech input keeps using the phone's).
 */
class NoRecognition : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
