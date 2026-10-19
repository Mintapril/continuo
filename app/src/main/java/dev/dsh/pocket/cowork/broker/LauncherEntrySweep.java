package dev.dsh.pocket.cowork.broker;

import java.util.*;
import java.util.function.ToIntFunction;

/** Pure decision for the launcher sweep: which launcher entries of the work profile are still visible to the
 * personal launcher and therefore have to be disabled. No Android types, so the rule is unit tested on the JVM.
 *
 * An entry is left alone when
 *   - we already hid it (tracked in the hidden list), so a sweep is a no-op for it,
 *   - the broker is currently running that app inside the owned display: disabling a live launcher activity makes
 *     the system force-finish the app, or
 *   - somebody else disabled it (state DISABLED): not ours to change. */
public final class LauncherEntrySweep {
    /** PackageManager.COMPONENT_ENABLED_STATE_DISABLED. */
    public static final int DISABLED = 2;

    private LauncherEntrySweep() {}

    public static List<String> targets(List<String> components, Set<String> owned, Set<String> inUse,
                                       ToIntFunction<String> state) {
        ArrayList<String> out = new ArrayList<>();
        for (String flat : components) {
            if (owned.contains(flat) || inUse.contains(flat)) continue;
            if (state.applyAsInt(flat) == DISABLED) continue;
            out.add(flat);
        }
        return out;
    }

    /** Entries we hid that are visible again, among the packages that just changed in the profile: uninstalling an app
     *  from the profile clears its component states, so installing it back enables an entry that is still on our list
     *  (which {@link #targets} leaves alone). An entry in use stays, as in {@link #targets}; one that is DISABLED, or
     *  whose state is unknown (the activity no longer exists), is left alone. Components are flattened ("package/class"). */
    public static List<String> visibleAgain(Collection<String> owned, Collection<String> changedPackages,
                                            Set<String> inUse, ToIntFunction<String> state) {
        ArrayList<String> out = new ArrayList<>();
        for (String flat : owned) {
            int slash = flat.indexOf('/');
            String pkg = slash > 0 ? flat.substring(0, slash) : flat;
            if (inUse.contains(flat) || !changedPackages.contains(pkg)) continue;
            if (state.applyAsInt(flat) == DISABLED) continue;
            out.add(flat);
        }
        return out;
    }

    /** Entries enabled for a run that may be hidden again while the owned display is parked: those of apps without a
     *  task left on it. An app that still has one keeps its entry. Components are flattened ("package/class"). */
    public static List<String> idle(Collection<String> entries, Set<String> livePackages) {
        ArrayList<String> out = new ArrayList<>();
        for (String flat : entries) {
            int slash = flat.indexOf('/');
            String pkg = slash > 0 ? flat.substring(0, slash) : flat;
            if (!livePackages.contains(pkg) && !out.contains(flat)) out.add(flat);
        }
        return out;
    }
}