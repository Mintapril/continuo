package dev.dsh.pocket

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.dsh.pocket.cowork.CoworkShadeActivity
import dev.dsh.pocket.data.IdentityStore

/**
 * The quick-settings tile 叫它: one tap puts the call sheet over whatever is on screen. It opens as a window
 * ([CallOverlay]), not an activity, because ColorOS grows a card out of the tile around any activity a tile starts.
 * Only an activity launch closes ColorOS's control center (the accessibility "dismiss shade" action does not), so the
 * tile starts the invisible [CoworkShadeActivity], which shows the window (or opens CallActivity without the overlay
 * permission).
 */
class CallTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = "叫${IdentityStore.current(this).shown}"
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { call() } else call()
    }

    private fun call() {
        val intent = Intent(this, CoworkShadeActivity::class.java).setAction(CoworkShadeActivity.CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 90, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
