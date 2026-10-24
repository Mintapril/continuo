package dev.dsh.pocket.cowork.broker;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.lang.reflect.*;
import java.util.*;

/** Keeps the third-party apps of the verified work profile silent (rule: {@link QuietAudioPolicy}). Runs at broker
 * start, on every package change in the profile, and every 10 s as drift repair (a mode turned back on in Settings is
 * set again). Only the play-audio app-op is written; apps, data and every other permission are unchanged. Refuses to
 * start when the op number does not name android:play_audio on this build. Each change is logged once to the root-only
 * broker.log. */
final class QuietAudioGate {
    private final ProfileContext profile;
    private final AppOpsManager ops;
    private final Method raw, set;
    private final Set<String> failed = new HashSet<>();

    QuietAudioGate(Context system, ProfileContext profile) throws Exception {
        this.profile = profile;
        ops = system.getSystemService(AppOpsManager.class);
        if (ops == null) throw new IllegalStateException("app ops unavailable");
        Object name = AppOpsManager.class.getMethod("opToPublicName", int.class).invoke(null, QuietAudioPolicy.OP_PLAY_AUDIO);
        if (!QuietAudioPolicy.OP_NAME.equals(name)) throw new IllegalStateException("op " + QuietAudioPolicy.OP_PLAY_AUDIO + " is " + name);
        raw = AppOpsManager.class.getMethod("unsafeCheckOpRawNoThrow", int.class, int.class, String.class);
        set = AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class);
    }

    /** Returns how many apps were silenced. Best effort, never throws. */
    synchronized int enforce() {
        try {
            profile.current();
            ArrayList<QuietAudioPolicy.App> apps = new ArrayList<>();
            for (PackageInfo pkg : profile.context().getPackageManager().getInstalledPackages(0)) {
                ApplicationInfo info = pkg.applicationInfo;
                if (info == null) continue;
                apps.add(new QuietAudioPolicy.App(info.packageName, info.uid,
                    (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0));
            }
            int done = 0;
            for (QuietAudioPolicy.Change c : QuietAudioPolicy.changes(apps, ProfileWorkspace.DESKTOP, this::mode)) {
                try {
                    set.invoke(ops, QuietAudioPolicy.OP_PLAY_AUDIO, c.uid(), c.packageName(), PopupPermissionPolicy.MODE_IGNORED);
                    // A UID-wide mode takes precedence over the package mode and the raw read reflects it: if the op
                    // still does not deny, the write had no effect. Not touched: a UID mode also covers other packages.
                    int after = mode(new QuietAudioPolicy.App(c.packageName(), c.uid(), false));
                    if (after != PopupPermissionPolicy.MODE_IGNORED && after != PopupPermissionPolicy.MODE_ERRORED) {
                        if (failed.add(c.packageName())) System.err.println("audio silencing had no effect for " + c.packageName() + ": mode is still " + after);
                        continue;
                    }
                    done++; failed.remove(c.packageName());
                    System.err.println("audio silenced for " + c.packageName() + ": " + c.from() + " -> " + PopupPermissionPolicy.MODE_IGNORED);
                } catch (Exception e) {
                    Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                    if (failed.add(c.packageName())) System.err.println("audio silencing failed for " + c.packageName() + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
            }
            return done;
        } catch (Exception e) {
            System.err.println("audio silencing unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 0;
        }
    }

    private int mode(QuietAudioPolicy.App app) {
        try { return (Integer) raw.invoke(ops, QuietAudioPolicy.OP_PLAY_AUDIO, app.uid(), app.packageName()); }
        catch (Exception e) { return -1; }
    }
}
