package dev.dsh.pocket.cowork.broker;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** The model's own app management in the verified work profile (rules: {@link ProfileAppPolicy}): phone.grant_permission
 * and phone.uninstall_app. The system's permission and uninstall dialogs refuse injected input (it is bound to the app
 * the model operates), so the decision the owner left to the model is carried out here, with root, for the profile
 * user only. Granting is the call `pm grant` makes (PackageManager.grantRuntimePermission for the profile user; the
 * system syncs the app op), uninstalling the one `pm uninstall --user` makes (IPackageManager.deletePackageVersioned
 * with flags 0, so a copy of the app in another user stays; root may uninstall without a confirmation). Signatures:
 * AOSP android16-release IPackageManager.aidl and IPackageDeleteObserver2.aidl. Both run on their own lock, never the
 * workspace's: an uninstall may wait for the system for a while. Each change or failure is logged to the root-only
 * broker.log, never with UI content. */
final class ProfileApps {
    /** How long an uninstall waits for the system's answer (the plugin aborts the request at 25 s). */
    static final long UNINSTALL_WAIT_MS = 15_000;
    private final ProfileContext profile;
    private final UserHandle user;
    private final Method flags, grant;

    ProfileApps(ProfileContext profile) throws Exception {
        this.profile = profile;
        user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, profile.userId());
        flags = PackageManager.class.getMethod("getPermissionFlags", String.class, String.class, UserHandle.class);
        grant = PackageManager.class.getMethod("grantRuntimePermission", String.class, String.class, UserHandle.class);
    }

    /** The app, verified: installed in the verified work profile (user and UID bound) and manageable by the model. */
    private PackageInfo manageable(String pkg, int pmFlags) throws Exception {
        profile.current();
        PackageManager pm = profile.context().getPackageManager();
        PackageInfo info = null;
        try { info = pm.getPackageInfo(pkg, pmFlags); profile.app(pkg); }
        catch (PackageManager.NameNotFoundException e) { ActionGate.fail("APP_UNKNOWN", "App is not installed in the work profile; call phone_apps for the installed apps"); }
        ApplicationInfo app = info.applicationInfo;
        if (app == null || !ProfileAppPolicy.manageable(pkg, (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0, ProfileWorkspace.DESKTOP))
            ActionGate.fail("APP_DENIED", "Only third-party apps of the work profile are managed: not system apps, not Continuo's own");
        return info;
    }

    /** Refuses (APP_UNKNOWN, APP_DENIED) unless the model may uninstall this app. */
    synchronized void requireManageable(String pkg) throws Exception { manageable(pkg, 0); }

    /** Grants the runtime permissions of one group (ProfileAppPolicy.GROUPS) that the app requests and nobody decided.
     *  Result: granted (newly held), held (held before) and kept (left as they were). Refused with
     *  PERMISSION_UNAVAILABLE when the app holds none of the group afterwards. */
    synchronized JSONObject grant(String pkg, String group) throws Exception {
        PackageInfo info = manageable(pkg, PackageManager.GET_PERMISSIONS);
        PackageManager pm = profile.context().getPackageManager();
        List<String> names = ProfileAppPolicy.GROUPS.get(group);
        if (names == null) ActionGate.fail("ARGUMENTS_INVALID", "Unknown permission group");
        ArrayList<ProfileAppPolicy.Permission> requested = new ArrayList<>();
        if (info.requestedPermissions != null) for (int i = 0; i < info.requestedPermissions.length; i++) {
            String name = info.requestedPermissions[i];
            if (!names.contains(name)) continue;
            boolean held = info.requestedPermissionsFlags != null && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            int current;
            try { current = (Integer) flags.invoke(pm, name, pkg, user); } catch (Exception e) { current = -1; }
            requested.add(new ProfileAppPolicy.Permission(name, held, current));
        }
        ProfileAppPolicy.Plan plan = ProfileAppPolicy.plan(group, requested);
        if (plan.refusal() != null) ActionGate.fail("PERMISSION_UNAVAILABLE", plan.refusal());
        ArrayList<String> granted = new ArrayList<>(), kept = new ArrayList<>(plan.kept());
        for (String name : plan.grant()) {
            try { grant.invoke(pm, pkg, name, user); }
            catch (Exception e) {
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                System.err.println("permission grant failed for " + pkg + " " + name + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            // Read back: only what the system now reports as held counts.
            if (pm.checkPermission(name, pkg) == PackageManager.PERMISSION_GRANTED) { granted.add(name); System.err.println("permission granted to work-profile app " + pkg + ": " + name); }
            else kept.add(name);
        }
        if (granted.isEmpty() && plan.held().isEmpty()) ActionGate.fail("PERMISSION_UNAVAILABLE", "The system did not grant this permission");
        return new JSONObject().put("granted", new JSONArray(granted)).put("held", new JSONArray(plan.held())).put("kept", new JSONArray(kept));
    }

    /** Uninstalls the app from the work profile only. Refused with UNINSTALL_FAILED when the system refuses or does not
     *  answer within UNINSTALL_WAIT_MS (it may still finish then: phone_apps tells). */
    synchronized void uninstall(String pkg) throws Exception {
        manageable(pkg, 0);
        Deletion deletion = new Deletion();
        Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        Class<?> observerType = Class.forName(Deletion.DESCRIPTOR);
        Object observer = Class.forName(Deletion.DESCRIPTOR + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, deletion);
        Method delete = pm.getClass().getMethod("deletePackageVersioned", VersionedPackage.class, observerType, int.class, int.class);
        // flags 0: this user only (never DELETE_ALL_USERS), data removed with the app.
        delete.invoke(pm, new VersionedPackage(pkg, PackageManager.VERSION_CODE_HIGHEST), observer, profile.userId(), 0);
        int result = deletion.await(UNINSTALL_WAIT_MS);
        if (result == ProfileAppPolicy.DELETE_SUCCEEDED) { System.err.println("uninstalled work-profile app " + pkg); return; }
        System.err.println("uninstall of work-profile app " + pkg + " failed: " + (result == Deletion.NO_ANSWER ? "no answer" : result == Deletion.USER_ACTION ? "confirmation required" : "return code " + result));
        ActionGate.fail("UNINSTALL_FAILED", result == Deletion.NO_ANSWER ? "The system did not confirm the uninstall in time" : "The system refused to uninstall the app");
    }

    /** IPackageDeleteObserver2 (oneway): onUserActionRequired(Intent), then onPackageDeleted(String, int, String). The
     *  transaction codes are read from the platform's generated Stub, with the AIDL declaration order as fallback. */
    private static final class Deletion extends Binder {
        static final String DESCRIPTOR = "android.content.pm.IPackageDeleteObserver2";
        static final int NO_ANSWER = Integer.MIN_VALUE, USER_ACTION = Integer.MIN_VALUE + 1;
        private final int deleted = code("TRANSACTION_onPackageDeleted", IBinder.FIRST_CALL_TRANSACTION + 1);
        private final int actionRequired = code("TRANSACTION_onUserActionRequired", IBinder.FIRST_CALL_TRANSACTION);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int result = NO_ANSWER;

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == deleted) { data.enforceInterface(DESCRIPTOR); data.readString(); result = data.readInt(); done.countDown(); return true; }
            // A confirmation the system wants from a person: not started here, counted as a refusal.
            if (code == actionRequired) { data.enforceInterface(DESCRIPTOR); result = USER_ACTION; done.countDown(); return true; }
            return super.onTransact(code, data, reply, flags);
        }

        int await(long ms) throws InterruptedException { done.await(ms, TimeUnit.MILLISECONDS); return result; }

        private static int code(String field, int fallback) {
            try { Field f = Class.forName(DESCRIPTOR + "$Stub").getDeclaredField(field); f.setAccessible(true); return f.getInt(null); }
            catch (Exception e) { return fallback; }
        }
    }
}
