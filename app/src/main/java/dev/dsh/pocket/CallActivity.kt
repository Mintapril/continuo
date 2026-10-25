package dev.dsh.pocket

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dsh.pocket.ui.CallSheet
import dev.dsh.pocket.ui.NoteSheet
import dev.dsh.pocket.ui.PocketTheme

/**
 * 叫它 and 记便条: a half sheet over whatever app is in front, so the owner rarely opens Continuo itself. Started from
 * the status card, the quick-settings tile, the launcher shortcuts, the share sheet (交给它 / 记便条), the app, and as
 * the system's assist activity when Continuo is the assistant without its voice interaction service. Shared text only
 * fills the draft; nothing is sent until the owner sends it. The window opens and closes without an animation: the
 * sheet slides in and out by itself.
 */
class CallActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
        if (dev.dsh.pocket.island.IslandTouchGuard.isObscured(event)) true else super.dispatchTouchEvent(event)

    private var micAnswer: ((Boolean) -> Unit)? = null
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micAnswer?.invoke(granted); micAnswer = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        enableEdgeToEdge()
        val note = isNote(intent)
        val shared = sharedText(intent) ?: intent?.getStringExtra(EXTRA_DRAFT)?.take(4_000)?.ifBlank { null }
        val voice = intent?.getBooleanExtra(EXTRA_VOICE, false) == true
        setContent {
            val model: PocketViewModel = viewModel()
            PocketTheme(model.theme, opaque = false) {
                if (note) NoteSheet(model, shared, close = ::finish)
                else CallSheet(model, shared, close = ::finish, askMic = { answer -> micAnswer = answer; micRequest.launch(Manifest.permission.RECORD_AUDIO) }, startVoice = voice)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dev.dsh.pocket.island.IslandRuntime.restore(applicationContext)
        dev.dsh.pocket.cowork.CoworkControllerService.ensureRunning(applicationContext)
    }

    @Suppress("DEPRECATION")
    override fun finish() { super.finish(); if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0) }

    companion object {
        const val ACTION_CALL = "dev.dsh.pocket.action.CALL"
        const val ACTION_NOTE = "dev.dsh.pocket.action.NOTE"
        /** Text to start the draft with; it is only filled in, never sent. */
        const val EXTRA_DRAFT = "dev.dsh.pocket.extra.DRAFT"
        /** Open 叫它 on press-and-hold speech (asking for the microphone first when needed). */
        const val EXTRA_VOICE = "dev.dsh.pocket.extra.VOICE"
        private fun open(context: Context, action: String, draft: String?) = Intent(context, CallActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).apply { if (draft != null) putExtra(EXTRA_DRAFT, draft) }
        fun call(context: Context, draft: String? = null): Intent = open(context, ACTION_CALL, draft)
        fun note(context: Context, draft: String? = null): Intent = open(context, ACTION_NOTE, draft)

        /** 记便条 by action, or by the share entry (activity-alias) it came through. */
        private fun isNote(intent: Intent?): Boolean =
            intent?.action == ACTION_NOTE || intent?.component?.className?.endsWith(".ShareNote") == true

        /** Text from the share sheet, bounded; anything else is ignored. */
        private fun sharedText(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            return listOf(subject, text).filter { it.isNotEmpty() }.distinct().joinToString("\n").take(4_000).ifBlank { null }
        }
    }
}
