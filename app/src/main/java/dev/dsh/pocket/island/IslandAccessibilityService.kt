package dev.dsh.pocket.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * A deliberately inert accessibility service, used only for the window layer.
 *
 * A plain TYPE_APPLICATION_OVERLAY window sits below the status bar (measured on this device:
 * base layer 111000 versus the status bar's 151000), so the island could never cover the clock,
 * the Wi-Fi/battery icons or ColorOS' status-bar chips. Windows created from an AccessibilityService
 * context as TYPE_ACCESSIBILITY_OVERLAY do sit above the status bar.
 *
 * It reads nothing: the metadata declares canRetrieveWindowContent=false and every callback below
 * is empty. If the user turns this service off the island simply falls back to the old layer.
 */
class IslandAccessibilityService : AccessibilityService() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.i(TAG, "connected; the island may now rise above the status bar")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.i(TAG, "unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        android.util.Log.i(TAG, "destroyed")
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DshIsland"

        @Volatile
        var instance: IslandAccessibilityService? = null
            private set

        val available: Boolean get() = instance != null
    }
}
