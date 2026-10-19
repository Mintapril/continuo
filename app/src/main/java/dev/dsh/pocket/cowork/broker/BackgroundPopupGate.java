package dev.dsh.pocket.cowork.broker;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.lang.reflect.*;
import java.util.*;

/** Keeps the third-party apps of the verified work profile without a right to open screens from the background (rule:
 * {@link PopupPermissionPolicy}). Runs at broker start, on every package change in the profile (an install session at once,
 * anything else within a second) and every 10 s as drift
 * repair (a permission granted again later is closed again). Only the two app-op modes are written (the overlay one is
 * skipped where the policy counts it as denied already); apps, data and every other permission are unchanged. Each change is logged once to the root-only broker.log. */
final class BackgroundPopupGate {
    private final ProfileContext profile;
    private final AppOpsManager ops;
    private final Method raw, set;
    private final Set<String> failed = new HashSet<>();

    BackgroundPopupGate(Context system, ProfileContext profile) throws Exception {
        this.profile = profile;
        ops = system.getSystemService(AppOpsManager.class);
        if (ops == null) throw new IllegalStateException("app ops unavailable");
        raw = AppOpsManager.class.getMethod("unsafeCheckOpRawNoThrow", int.class, int.class, String.class);
        set = AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class);
    }

    /** Returns how many modes were changed. Best effort, never throws. */
    synchronized int enforce() {
        try {
            profile.current();
            ArrayList<PopupPermissionPolicy.App> apps = new ArrayList<>();
            for (PackageInfo pkg : profile.context().getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                ApplicationInfo info = pkg.applicationInfo;
                if (info == null) continue;
                apps.add(new PopupPermissionPolicy.App(info.packageName, info.uid,
                    (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                    pkg.requestedPermissions != null && Arrays.asList(pkg.requestedPermissions).contains(Manifest.permission.SYSTEM_ALERT_WINDOW)));
            }
            int done = 0;
            for (PopupPermissionPolicy.Change c : PopupPermissionPolicy.changes(apps, ProfileWorkspace.DESKTOP, this::mode)) {
                String key = c.packageName() + ":" + c.op();
                try {
                    set.invoke(ops, c.op(), c.uid(), c.packageName(), c.to());
                    // A UID-wide mode takes precedence over the package mode and the raw read reflects it: if the op
                    // still does not deny, the write had no effect. Not touched: a UID mode also covers other packages.
                    int after = mode(new PopupPermissionPolicy.App(c.packageName(), c.uid(), false, false), c.op());
                    if (after != PopupPermissionPolicy.MODE_IGNORED && after != PopupPermissionPolicy.MODE_ERRORED) {
                        if (failed.add(key)) System.err.println("background pop-up gate had no effect for " + key + ": mode is still " + after);
                        continue;
                    }
                    done++; failed.remove(key);
                    System.err.println("background pop-ups closed for " + c.packageName() + ": op " + c.op() + " " + c.from() + " -> " + c.to());
                } catch (Exception e) {
                    Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                    if (failed.add(key)) System.err.println("background pop-up gate failed for " + key + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
            return done;
        } catch (Exception e) {
            System.err.println("background pop-up gate unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 0;
        }
    }

    private int mode(PopupPermissionPolicy.App app, int op) {
        try { return (Integer) raw.invoke(ops, op, app.uid(), app.packageName()); }
        catch (Exception e) { return -1; }
    }
}
