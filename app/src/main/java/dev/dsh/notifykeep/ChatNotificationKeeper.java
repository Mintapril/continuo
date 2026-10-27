package dev.dsh.notifykeep;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Root sidecar, no activity/service launch, no app data access, no global power-policy change.
 * Importance is an AMS PID lease, not persistence: a force-stop still works. */
public final class ChatNotificationKeeper {
    private static final String REASON = "continuo-chat-notify-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private final Context system;
    private final Object am;
    private final Method important;
    /** Read once: a held PID that drops out of the list mid-life would end the whole keeper (see the cleanup in tick). */
    private final List<String> packages;
    private final Map<Integer, Lease> held = new java.util.concurrent.ConcurrentHashMap<>();
    private record Identity(int pid, int uid, String name, long start) {}
    private record Lease(Identity identity, Binder token) {}
    private ChatNotificationKeeper() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        system = (Context) at.getMethod("getSystemContext").invoke(thread);
        am = ActivityManager.class.getMethod("getService").invoke(null);
        important = Class.forName("android.app.IActivityManager").getMethod(
                "setProcessImportant", IBinder.class, int.class, boolean.class, String.class);
        Path list = Path.of(ChatKeepPolicy.LIST_FILE);
        packages = Files.exists(list) ? ChatKeepPolicy.parsePackages(text(list)) : ChatKeepPolicy.DEFAULT_PACKAGES;
    }
    private static Object service(String name, String type) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("missing " + name);
        return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private static Object call(Object target, String type, String method, int id) throws Exception {
        return Class.forName(type).getMethod(method, int.class).invoke(target, id);
    }
    /** Re-check owner, parent, serial, running/unlocked/nonquiet at every sweep; no user0 fallback. */
    private Context profile() throws Exception {
        String ut = "android.os.IUserManager", dt = "android.app.admin.IDevicePolicyManager";
        Object users = service("user", ut), policy = service("device_policy", dt);
        Object info = call(users, ut, "getUserInfo", ChatKeepPolicy.USER);
        Object parent = call(users, ut, "getProfileParent", ChatKeepPolicy.USER);
        ComponentName owner = (ComponentName) call(policy, dt, "getProfileOwnerAsUser", ChatKeepPolicy.USER);
        if (info == null || parent == null || owner == null
                || !ChatKeepPolicy.OWNER.equals(owner.flattenToShortString())
                || !"android.os.usertype.profile.MANAGED".equals(info.getClass().getField("userType").get(info))
                || info.getClass().getField("id").getInt(info) != 10
                || info.getClass().getField("partial").getBoolean(info)
                || info.getClass().getField("serialNumber").getInt(info) != 10
                || parent.getClass().getField("id").getInt(parent) != 0
                || !(Boolean) call(users, ut, "isUserRunning", 10)
                || !(Boolean) call(users, ut, "isUserUnlocked", 10)
                || (Boolean) call(users, ut, "isQuietModeEnabled", 10))
            throw new IllegalStateException("work profile unavailable");
        UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, 10);
        Context work = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                .invoke(system, user, 0);
        PackageInfo ownerInfo = work.getPackageManager().getPackageInfo(owner.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] signatures = ownerInfo.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length != 1) throw new IllegalStateException("profile signer unavailable");
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder signer = new StringBuilder();
        for (byte b : hash) signer.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!"131906a5339de6283c227af40f6bf06f9abcfd10736d453bd203c1543dc2bf81".equals(signer.toString()))
            throw new IllegalStateException("profile signer changed");
        return work;
    }
    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static Identity identity(int pid) throws Exception {
        Path base = Path.of("/proc", Integer.toString(pid));
        String name = text(base.resolve("cmdline")).split("\u0000", 2)[0];
        Matcher uid = Pattern.compile("(?m)^Uid:\\s+(\\d+)").matcher(text(base.resolve("status")));
        if (!uid.find()) throw new IllegalStateException("uid missing");
        return new Identity(pid, Integer.parseInt(uid.group(1)), name,
                ChatKeepPolicy.startTicks(text(base.resolve("stat"))));
    }
    private static List<Identity> processes(int uid) throws Exception {
        ArrayList<Identity> out = new ArrayList<>();
        for (String root : new String[]{"/sys/fs/cgroup/apps/uid_", "/sys/fs/cgroup/uid_"}) {
            Path dir = Path.of(root + uid);
            if (!Files.isDirectory(dir)) continue;
            try (var entries = Files.list(dir)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    String name = entry.getFileName().toString();
                    if (!name.matches("pid_[0-9]+")) continue;
                    try { Identity value = identity(Integer.parseInt(name.substring(4)));
                        if (value.uid == uid) out.add(value);
                    } catch (Exception exited) { /* race with process exit */ }
                }
            }
            break;
        }
        return out;
    }
    /** Package-filtered process metadata only; no notification bodies, prefs, accounts or app DB. */
    private static Map<Integer, ChatKeepPolicy.ProcessState> states(String pkg) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/system/bin/dumpsys", "activity", "-p", pkg, "processes");
        builder.environment().remove("CLASSPATH"); builder.redirectErrorStream(true);
        java.lang.Process child = builder.start();
        String dump;
        java.util.concurrent.FutureTask<byte[]> read = new java.util.concurrent.FutureTask<>(
                () -> child.getInputStream().readNBytes(2 * 1024 * 1024));
        Thread reader = new Thread(read, "chat-notify-metadata"); reader.setDaemon(true); reader.start();
        try {
            byte[] bytes = read.get(5, TimeUnit.SECONDS);
            if (bytes.length == 2 * 1024 * 1024 || !child.waitFor(1, TimeUnit.SECONDS) || child.exitValue() != 0)
                throw new IllegalStateException("process metadata unavailable");
            dump = new String(bytes, StandardCharsets.UTF_8);
        } finally { child.destroyForcibly(); read.cancel(true); }
        Map<Integer, ChatKeepPolicy.ProcessState> result = ChatKeepPolicy.parseStates(dump, REASON);
        if (result.isEmpty() && dump.indexOf("ACTIVITY MANAGER") < 0)
            throw new IllegalStateException("unexpected process metadata");
        return result;
    }
    private void tick() throws Exception {
        Context work = profile();
        Set<Integer> seen = new HashSet<>();
        for (String pkg : packages) {
            ApplicationInfo info;
            try { info = work.getPackageManager().getApplicationInfo(pkg, 0); }
            catch (PackageManager.NameNotFoundException absent) { continue; }
            if (!info.enabled || (info.flags & (ApplicationInfo.FLAG_STOPPED | ApplicationInfo.FLAG_SUSPENDED)) != 0) continue;
            List<Identity> candidates = new ArrayList<>();
            for (Identity value : processes(info.uid))
                if (ChatKeepPolicy.accepts(pkg, value.name, value.uid, info.uid, info.enabled, false, false)) candidates.add(value);
            if (candidates.isEmpty()) continue;
            Map<Integer, ChatKeepPolicy.ProcessState> observed = states(pkg);
            for (Identity value : candidates) {
                seen.add(value.pid);
                ChatKeepPolicy.ProcessState state = observed.get(value.pid);
                if (state == null || state.other() || state.fgs()) continue; // never replace somebody else's importance / FGS
                Lease old = held.get(value.pid);
                if (state.ours() && old != null && old.identity.equals(value)) continue;
                if (old != null && !old.identity.equals(value))
                    throw new IllegalStateException("registered PID changed identity");
                if (!value.equals(identity(value.pid))) continue; // PID + full UID + name + birth time
                ApplicationInfo fresh = work.getPackageManager().getApplicationInfo(pkg, 0);
                if (fresh.uid != value.uid || !fresh.enabled ||
                        (fresh.flags & (ApplicationInfo.FLAG_STOPPED | ApplicationInfo.FLAG_SUSPENDED)) != 0) continue;
                Binder token = new Binder();
                important.invoke(am, token, value.pid, true, REASON);
                held.put(value.pid, new Lease(value, token));
                System.out.println("protect user=10 process=" + value.name + " pid=" + value.pid);
            }
        }
        // Clean absent PID entries promptly. Live/recycled PIDs are never blindly cleared with false.
        Iterator<Map.Entry<Integer, Lease>> it = held.entrySet().iterator();
        while (it.hasNext()) {
            var row = it.next();
            if (seen.contains(row.getKey())) continue;
            if (!Files.exists(Path.of("/proc", row.getKey().toString()))) {
                // Null ProcessRecord is supported by AOSP (full OOM recalculation, once).
                // The kernel PID check and AMS call are not atomic; see README's PID API limitation.
                important.invoke(am, row.getValue().token, row.getKey(), false, REASON);
                it.remove();
            } else {
                // Disabled/suspended or changed identity: release the whole helper, no blind per-PID revoke.
                throw new IllegalStateException("registered process no longer eligible");
            }
        }
        System.out.flush();
    }
    public static void main(String[] ignored) throws Exception {
        Looper.prepareMainLooper();
        ChatNotificationKeeper keeper = new ChatNotificationKeeper();
        System.out.println("READY version=2 user=10 mode=existing-processes-only packages=" + String.join(",", keeper.packages));System.out.flush();
        new Thread(() -> {
            try { while (true) { keeper.tick(); Thread.sleep(10000); } }
            catch (Throwable e) {
                System.err.println("keeper stopped: " + e.getClass().getSimpleName());
                System.err.flush(); System.exit(1); // Binder death releases only tokens still owned by this process.
            }
        }, "chat-notify-keeper").start();
        Looper.loop();
    }
}
