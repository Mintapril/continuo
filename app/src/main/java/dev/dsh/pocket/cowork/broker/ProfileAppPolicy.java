package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure rules for the model's own app management in the work profile ({@link ProfileApps}): granting runtime
 * permissions (phone.grant_permission) and uninstalling apps (phone.uninstall_app). No Android types, so the rules are
 * unit tested on the JVM.
 *
 * The owner decided (2026-10-23) that the AI runs the work-profile apps as its own, permissions and uninstalls
 * included, but the system's permission and uninstall dialogs refuse injected input. These tools do the same thing
 * natively instead. Only a third-party app of the verified work profile is managed: never a system app, never the
 * profile owner or another package of Continuo (the acceptance fixture included), never a package ActionGate refuses
 * (settings, shells, installers). A permission is named by its group, as the system dialog asks for it; each group
 * stands for an explicit list of runtime permissions and nothing else: no special app access (overlay, usage access,
 * all files), no background location, nothing of the phone, SMS or call-log groups. A permission somebody decided
 * (the owner in system settings, a policy or the system: the flags {@link NotificationGrantPolicy#DECIDED}) stays as
 * it is, as it does for {@link NotificationGrantPolicy}. */
public final class ProfileAppPolicy {
    /** Group name the model uses -> the runtime permissions it grants. Read by strict-broker.test.mjs, which compares it
     *  with PERMISSION_GROUPS in cowork-plugin/src/broker.mjs: keep one "group", List.of(...) entry per line. */
    public static final Map<String, List<String>> GROUPS = Map.of(
        "location", List.of("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
        "camera", List.of("android.permission.CAMERA"),
        "microphone", List.of("android.permission.RECORD_AUDIO"),
        "storage", List.of("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"),
        "contacts", List.of("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"),
        "notifications", List.of("android.permission.POST_NOTIFICATIONS"));
    /** Continuo's own packages (app, profile owner, acceptance fixture): never managed by the model. */
    static final String OWN_PACKAGES = "dev.dsh.pocket.";
    /** PackageManager.DELETE_SUCCEEDED. */
    public static final int DELETE_SUCCEEDED = 1;

    private ProfileAppPolicy() {}

    /** Whether the model may grant permissions to and uninstall this app of the verified work profile. */
    public static boolean manageable(String packageName, boolean system, String profileOwner) {
        return ActionGate.eligiblePackage(packageName) && !system && !packageName.equals(profileOwner)
            && !packageName.startsWith(OWN_PACKAGES);
    }

    /** One permission the app requests: whether it holds it and its permission flags (negative: unreadable). */
    public record Permission(String name, boolean granted, int flags) {}

    /** What a grant of one group does, in the group's order. grant: requested, not held, nobody decided; held: already
     *  granted; kept: requested, not held, decided by somebody (or flags unreadable), left as it is. */
    public record Plan(List<String> grant, List<String> held, List<String> kept) {
        /** The refusal when nothing of the group can be held after the grant, else null. */
        public String refusal() {
            if (grant.isEmpty() && held.isEmpty() && kept.isEmpty()) return "The app does not request this permission";
            if (grant.isEmpty() && held.isEmpty()) return "The owner turned this permission off in system settings, or a policy or the system fixed it";
            return null;
        }
    }

    /** `requested`: the permissions the app requests (any, in any order). Unknown group: an empty plan. */
    public static Plan plan(String group, List<Permission> requested) {
        List<String> names = GROUPS.getOrDefault(group, List.of());
        Map<String, Permission> byName = new HashMap<>();
        for (Permission p : requested) if (names.contains(p.name())) byName.putIfAbsent(p.name(), p);
        List<String> grant = new ArrayList<>(), held = new ArrayList<>(), kept = new ArrayList<>();
        for (String name : names) {
            Permission p = byName.get(name);
            if (p == null) continue;
            if (p.granted()) held.add(name);
            else if (p.flags() >= 0 && (p.flags() & NotificationGrantPolicy.DECIDED) == 0) grant.add(name);
            else kept.add(name);
        }
        return new Plan(List.copyOf(grant), List.copyOf(held), List.copyOf(kept));
    }
}
