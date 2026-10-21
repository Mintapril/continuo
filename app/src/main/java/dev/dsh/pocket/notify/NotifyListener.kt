package dev.dsh.pocket.notify

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives every posted notification once the user grants notification access. It reads title and text only while
 * notification triggers are on and the package is not excluded; everything else is dropped unread. Work-profile notifications arrive here too,
 * because the profile owner (isolation) permits cross-profile listeners.
 */
class NotifyListener : NotificationListenerService() {
    override fun onListenerConnected() { NotifyCenter.init(this); connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotifyCenter.init(this)
        if (sbn.packageName == packageName || !NotifyCenter.watches(sbn.packageName)) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // The system puts the posting app's ApplicationInfo into every notification; it names apps the personal user cannot see.
        val appInfo = extras.getParcelable("android.appInfo", ApplicationInfo::class.java) // Notification.EXTRA_BUILDER_APPLICATION_INFO, hidden
        NotifyCenter.posted(Posted(
            packageName = sbn.packageName, key = sbn.key, work = sbn.user != Process.myUserHandle(), channel = n.channelId.orEmpty(),
            category = n.category, ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0, title = title, text = text, at = sbn.postTime,
        ), appInfo)
    }

    companion object { @Volatile var connected = false; private set }
}
