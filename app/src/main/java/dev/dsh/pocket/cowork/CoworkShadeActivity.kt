package dev.dsh.pocket.cowork

import android.app.Activity
import android.os.Bundle
import dev.dsh.pocket.CallOverlay

/**
 * Notification buttons and the quick-settings tile go through this invisible, non-exported Activity: an Activity launch
 * closes the notification shade, a service call would leave the shade over what opens. It only forwards a fixed action
 * and finishes; it carries no data. 看画面 / 去接手 open the full screen ([CoworkControllerService]); 叫它 / 写便条
 * open the sheet as a window ([CallOverlay]), or CallActivity when no window can be shown.
 */
class CoworkShadeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = intent?.action) {
            WATCH -> CoworkControllerService.show(this, CoworkControllerService.WATCH)
            ACCEPT -> CoworkControllerService.show(this, CoworkControllerService.ACCEPT)
            CALL, NOTE -> {
                val note = action == NOTE
                if (!CallOverlay.show(this, note)) startActivity(CallOverlay.open(this, note, null))
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val WATCH = "dev.dsh.pocket.shade.WATCH"
        const val ACCEPT = "dev.dsh.pocket.shade.ACCEPT"
        const val CALL = "dev.dsh.pocket.shade.CALL"
        const val NOTE = "dev.dsh.pocket.shade.NOTE"
    }
}
