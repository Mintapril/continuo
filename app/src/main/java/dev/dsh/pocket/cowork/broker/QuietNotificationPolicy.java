package dev.dsh.pocket.cowork.broker;

/** Pure rule for keeping work-profile notifications off the main screen ({@link QuietNotificationGate}). No Android
 * types, so it is unit tested on the JVM.
 *
 * The work profile's apps run on the owned display, but the system shows their notifications to the personal user: a
 * heads-up banner, sound and vibration on the main screen, a status-bar icon, an incoming-call screen. The user asked
 * for none of that. Blocking the channels (importance NONE) would also hide the notifications from the notification
 * triggers, so every channel of a third-party app other than the profile owner is held at IMPORTANCE_MIN instead: still
 * posted and read by listeners, but silent, with no banner, full-screen launch or status-bar icon. A blocked channel
 * stays blocked. */
public final class QuietNotificationPolicy {
    /** NotificationManager.IMPORTANCE_MIN. */
    public static final int MIN = 1;

    private QuietNotificationPolicy() {}

    public static boolean covers(String packageName, boolean system, String profileOwner) {
        return !system && !profileOwner.equals(packageName);
    }

    /** Whether a channel of a covered app is lowered to MIN: anything that alerts more than MIN. NONE (0) stays blocked;
     *  UNSPECIFIED (-1000) is never stored for a channel. */
    public static boolean lowers(int importance) {
        return importance > MIN;
    }
}
