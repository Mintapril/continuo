package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure decision for allowing notifications of apps newly installed into the work profile ({@link NotificationGrant}).
 * No Android types, so the rule is unit tested on the JVM.
 *
 * A profile app runs on the owned display, so its own notification prompt would appear there, where nobody answers it,
 * and an app without the permission posts nothing the notification triggers could read. The user asked for every app
 * installed later to be allowed; apps already in the profile keep their state. The grant is made only for a package
 * that just appeared in the profile ({@link Installs}), is third-party and not the profile owner, requests
 * POST_NOTIFICATIONS (apps targeting SDK 32 and lower request it implicitly), does not hold it yet and carries none of
 * the decision flags: a choice of the user, a policy or the system is kept. A negative `flags` means they could not be
 * read, and the app is skipped. */
public final class NotificationGrantPolicy {
    public static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    /** PackageManager.FLAG_PERMISSION_USER_SET, _USER_FIXED, _POLICY_FIXED and _SYSTEM_FIXED. */
    public static final int DECIDED = 1 | 2 | 4 | 16;

    public record App(String packageName, boolean system, boolean requests, boolean granted, int flags) {}

    private NotificationGrantPolicy() {}

    public static boolean grants(App app, String profileOwner) {
        return !app.system() && !profileOwner.equals(app.packageName()) && app.requests() && !app.granted()
            && app.flags() >= 0 && (app.flags() & DECIDED) == 0;
    }

    /** The profile's packages seen so far. A package change names installs, updates, uninstalls and component changes
     *  alike (the broker's own launcher sweep included); only the first sight of a package after the baseline, or after
     *  it was uninstalled, is an install. */
    public static final class Installs {
        private final Set<String> seen = new HashSet<>();

        public Installs(Collection<String> baseline) { seen.addAll(baseline); }

        /** `installed`: the package exists in the profile now. True when it was just installed. */
        public synchronized boolean appeared(String name, boolean installed) {
            if (!installed) { seen.remove(name); return false; }
            return seen.add(name);
        }
    }
}
