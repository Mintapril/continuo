package dev.dsh.pocket.notify

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives every posted notification once the user grants notification access. It reads title and text only while
 * notification triggers are on and the package is not excluded; everything else is dropped unread. Work-profile notifications arrive here too,
 * because the profile owner (isolation) permits cross-profile listeners. Those of the Cowork profile's apps are then
 * cleared from the owner's shade ([ProfileShade]), whether or not they were read.
 */
class NotifyListener : NotificationListenerService() {
    override fun onListenerConnected() { NotifyCenter.init(this); connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotifyCenter.init(this)
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // The system puts the posting app's ApplicationInfo into every notification; it names apps the personal user cannot see.
        val appInfo = n.extras.getParcelable("android.appInfo", ApplicationInfo::class.java) // Notification.EXTRA_BUILDER_APPLICATION_INFO, hidden
        if (NotifyCenter.watches(sbn.packageName)) read(sbn, n, appInfo)
        val system = appInfo?.let { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
        val sticky = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE) != 0
        @Suppress("DEPRECATION") val userId = sbn.userId // UserHandle.getIdentifier is not in the public SDK
        if (ProfileShade.clears(userId, NotifyCenter.profileUserId, sbn.packageName, system, sticky)) runCatching { cancelNotification(sbn.key) }
    }

    private fun read(sbn: StatusBarNotification, n: Notification, appInfo: ApplicationInfo?) {
        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        NotifyCenter.posted(Posted(
            packageName = sbn.packageName, key = sbn.key, work = sbn.user != Process.myUserHandle(), channel = n.channelId.orEmpty(),
            category = n.category, ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0, title = title, text = text, at = sbn.postTime,
        ), appInfo)
    }

    companion object { @Volatile var connected = false; private set }
}
