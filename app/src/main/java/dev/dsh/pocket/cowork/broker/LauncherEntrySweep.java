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
}