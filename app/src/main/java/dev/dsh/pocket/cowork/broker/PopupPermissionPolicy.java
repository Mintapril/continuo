package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure decision for the background pop-up gate of the work profile. No Android types, so the rule is unit tested on
 * the JVM.
 *
 * Android refuses an activity start from an app that has no visible window, unless the app holds an exemption. Two of
 * them are app permissions: "display over other apps" (SYSTEM_ALERT_WINDOW) and ColorOS's own "background pop-up"
 * (BACKGROUND_START_ACTIVITY, op 10003; ColorOS's background-start check accepts only MODE_ALLOWED). Without them, a
 * profile app that is not on the owned display cannot open a screen at all, so nothing of it can land on the main
 * screen. The user agreed to close both for every third-party app of the profile, including apps installed later.
 *
 * A change is made only for a third-party app that is not the profile owner, and only when the op does not deny yet.
 * IGNORED and ERRORED already deny. DEFAULT does not: for SYSTEM_ALERT_WINDOW it falls back to the permission, which
 * pre-23 and preinstalled apps hold. SYSTEM_ALERT_WINDOW is set to ERRORED (what Settings writes for "not allowed"),
 * the ColorOS op to IGNORED (an ignored op never throws in a caller). */
public final class PopupPermissionPolicy {
    public static final int OP_SYSTEM_ALERT_WINDOW = 24;
    public static final int OP_BACKGROUND_START_ACTIVITY = 10003;
    public static final int MODE_ALLOWED = 0, MODE_IGNORED = 1, MODE_ERRORED = 2, MODE_DEFAULT = 3, MODE_FOREGROUND = 4;

    public record App(String packageName, int uid, boolean system) {}
    public record Change(String packageName, int uid, int op, int from, int to) {}
    /** Raw mode of `op` for `app`; a negative value means it could not be read (the op is skipped). */
    public interface Modes { int mode(App app, int op); }

    private PopupPermissionPolicy() {}

    public static List<Change> changes(List<App> apps, String profileOwner, Modes modes) {
        ArrayList<Change> out = new ArrayList<>();
        for (App app : apps) {
            if (app.system() || profileOwner.equals(app.packageName())) continue;
            add(out, app, OP_SYSTEM_ALERT_WINDOW, MODE_ERRORED, modes);
            add(out, app, OP_BACKGROUND_START_ACTIVITY, MODE_IGNORED, modes);
        }
        return out;
    }

    private static void add(List<Change> out, App app, int op, int to, Modes modes) {
        int from = modes.mode(app, op);
        if (from < 0 || from == MODE_IGNORED || from == MODE_ERRORED) return;
        out.add(new Change(app.packageName(), app.uid(), op, from, to));
    }
}
