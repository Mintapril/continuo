package dev.dsh.pocket.cowork.broker;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import java.lang.reflect.*;
import java.util.*;

/** Allows notifications for apps installed into the verified work profile while the broker runs (rule:
 * {@link NotificationGrantPolicy}). Runs only for the packages a package change of the profile names (an install session
 * at once, anything else within a second), never as a sweep of every app or as drift repair: apps already in the profile
 * at broker start, and an app the user turns off later, keep their state. Writes only the POST_NOTIFICATIONS grant,
 * through the same call as `pm grant`, after which the system syncs the app op. Each grant or failure is logged to the
 * root-only broker.log. */
final class NotificationGrant {
    private final ProfileContext profile;
    private final UserHandle user;
    private final Method flags, grant;
    private final NotificationGrantPolicy.Installs installs;

    NotificationGrant(ProfileContext profile) throws Exception {
        this.profile = profile;
        user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, profile.userId());
        flags = PackageManager.class.getMethod("getPermissionFlags", String.class, String.class, UserHandle.class);
        grant = PackageManager.class.getMethod("grantRuntimePermission", String.class, String.class, UserHandle.class);
        profile.current();
        ArrayList<String> baseline = new ArrayList<>();
        for (PackageInfo pkg : profile.context().getPackageManager().getInstalledPackages(0)) baseline.add(pkg.packageName);
        installs = new NotificationGrantPolicy.Installs(baseline);
    }

    /** Returns how many apps were allowed. Best effort, never throws. */
    synchronized int allow(List<String> packages) {
        int done = 0;
        try {
            profile.current();
            PackageManager pm = profile.context().getPackageManager();
            for (String name : packages) {
                PackageInfo pkg;
                try { pkg = pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS); }
                catch (PackageManager.NameNotFoundException gone) { installs.appeared(name, false); continue; }
                ApplicationInfo info = pkg.applicationInfo;
                if (info == null || !installs.appeared(name, true)) continue;
                boolean requests = false, granted = false;
                if (pkg.requestedPermissions != null) for (int i = 0; i < pkg.requestedPermissions.length; i++) {
                    if (!NotificationGrantPolicy.PERMISSION.equals(pkg.requestedPermissions[i])) continue;
                    requests = true;
                    granted = pkg.requestedPermissionsFlags != null && (pkg.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                }
                int current;
                try { current = (Integer) flags.invoke(pm, NotificationGrantPolicy.PERMISSION, name, user); }
                catch (Exception e) { current = -1; }
                NotificationGrantPolicy.App app = new NotificationGrantPolicy.App(name,
                    (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0, requests, granted, current);
                if (!NotificationGrantPolicy.grants(app, ProfileWorkspace.DESKTOP)) continue;
                try {
                    grant.invoke(pm, name, NotificationGrantPolicy.PERMISSION, user);
                    done++;
                    System.err.println("notifications allowed for new work-profile app " + name);
                } catch (Exception e) {
                    Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                    System.err.println("notification grant failed for " + name + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("notification grant unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return done;
    }
}
