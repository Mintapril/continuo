package dev.dsh.pocket.cowork

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** One message for the broker's takeover input channel. */
sealed interface RemoteKeystroke {
    data class Text(val text: String) : RemoteKeystroke
    data class Key(val key: String) : RemoteKeystroke
}

/**
 * What the phone's own keyboard produced, as takeover input (pure, JVM-testable). The owned display's IME policy is
 * HIDE, so during a takeover the user types on this phone's keyboard into a local proxy and the committed text is
 * forwarded: as text messages of at most 200 characters (the broker's limit, split between code points) and line
 * breaks as ENTER. A password field only takes printable ASCII, which the broker types key by key; anything else
 * would need a semantic write that a masked field cannot take, so such a commit is refused whole (null) and nothing
 * of it is sent.
 */
object RemoteTyping {
    const val MAX_TEXT = 200
    fun commit(text: CharSequence, password: Boolean): List<RemoteKeystroke>? {
        val value = text.toString().replace("\r\n", "\n").replace('\r', '\n')
        if (password && value.any { it != '\n' && (it < ' ' || it > '~') }) return null
        val out = mutableListOf<RemoteKeystroke>()
        value.split('\n').forEachIndexed { index, line ->
            if (index > 0) out += RemoteKeystroke.Key("ENTER")
            val clean = line.filterNot { Character.isISOControl(it) }
            var start = 0
            while (start < clean.length) {
                var end = minOf(clean.length, start + MAX_TEXT)
                if (end < clean.length && Character.isHighSurrogate(clean[end - 1])) end--
                out += RemoteKeystroke.Text(clean.substring(start, end)); start = end
            }
        }
        return out
    }
    /** Backspace presses; one IME request never sends more than 64. */
    fun delete(count: Int): List<RemoteKeystroke> = List(count.coerceIn(0, 64)) { RemoteKeystroke.Key("DEL") }
}

/**
 * Local stand-in for the owned display's focused field. It is a plain focusable View that the phone's keyboard targets:
 * committed text, backspace and enter go to [send]; composing text (pinyin, word prediction) only reaches [composing]
 * until the keyboard commits it. The editor mirrors the remote field's input type; a password field gets a password
 * editor without personalised learning. Nothing typed is kept or logged here.
 */
class RemoteKeyboardView(context: Context, private val send: (RemoteKeystroke) -> Unit,
                         private val refused: () -> Unit, private val composing: (String) -> Unit) : View(context) {
    private val input = context.getSystemService(InputMethodManager::class.java)
    /** The remote field being typed into; a change of kind restarts the keyboard with a matching editor. */
    var remote: RemoteField? = null
        set(value) {
            val restart = field?.password != value?.password || field?.inputType != value?.inputType || field?.multiLine != value?.multiLine
            field = value
            if (restart && hasFocus()) input?.restartInput(this)
        }

    init { isFocusable = true; isFocusableInTouchMode = true }

    fun showKeyboard() { requestFocus(); input?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    fun hideKeyboard() { input?.hideSoftInputFromWindow(windowToken, 0) }

    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val target = remote
        val password = target?.password == true
        val remoteClass = (target?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        outAttrs.inputType = when {
            password && remoteClass == InputType.TYPE_CLASS_NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            remoteClass != 0 -> target!!.inputType
            else -> InputType.TYPE_CLASS_TEXT
        }
        // Enter is a key for the remote field (it may submit there), never "done" that only closes this keyboard.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            if (password) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0
        return Connection()
    }

    private inner class Connection : BaseInputConnection(this@RemoteKeyboardView, false) {
        private var pending = ""
        private fun deliver(text: String) {
            if (text.isEmpty()) return
            val keys = RemoteTyping.commit(text, remote?.password == true)
            if (keys == null) refused() else keys.forEach(send)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { pending = text?.toString().orEmpty(); composing(pending); return true }
        override fun finishComposingText(): Boolean { val text = pending; pending = ""; composing(""); deliver(text); return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { pending = ""; composing(""); deliver(text?.toString().orEmpty()); return true }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { RemoteTyping.delete(beforeLength).forEach(send); return true }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)
        override fun performEditorAction(actionCode: Int): Boolean { send(RemoteKeystroke.Key("ENTER")); return true }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> send(RemoteKeystroke.Key("DEL"))
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> send(RemoteKeystroke.Key("ENTER"))
                else -> event.unicodeChar.takeIf { it > 0 }?.let { deliver(String(Character.toChars(it))) }
            }
            return true
        }
    }
}
