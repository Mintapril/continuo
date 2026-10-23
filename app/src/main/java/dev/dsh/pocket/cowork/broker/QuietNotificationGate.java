package dev.dsh.pocket.cowork.broker;

import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import java.lang.reflect.*;
import java.util.*;

/** Holds every notification channel of the verified work profile's third-party apps at IMPORTANCE_MIN (rule:
 * {@link QuietNotificationPolicy}), so their notifications make no sound and show no banner or icon on the main screen
 * while the notification triggers still read them (Continuo's listener then clears them from the shade). Runs at broker
 * start, on every package change in the profile and every 10 s: apps create channels at any time, and a channel raised
 * again is lowered again. Writes only the importance, through the call system settings use (root passes its system
 * check). Each change is logged once to the root-only broker.log. */
final class QuietNotificationGate {
    private final ProfileContext profile;
    private final Object service;
    private final Method channels, update;
    private final Set<String> failed = new HashSet<>();

    QuietNotificationGate(ProfileContext profile) throws Exception {
        this.profile = profile;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, Context.NOTIFICATION_SERVICE);
        if (binder == null) throw new IllegalStateException("notification service unavailable");
        service = Class.forName("android.app.INotificationManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        channels = service.getClass().getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class);
        update = service.getClass().getMethod("updateNotificationChannelForPackage", String.class, int.class, NotificationChannel.class);
    }

    /** Returns how many channels were lowered. Best effort, never throws. */
    synchronized int enforce() {
        try {
            profile.current();
            int done = 0;
            for (PackageInfo pkg : profile.context().getPackageManager().getInstalledPackages(0)) {
                ApplicationInfo info = pkg.applicationInfo;
                if (info == null || !QuietNotificationPolicy.covers(info.packageName,
                    (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0, ProfileWorkspace.DESKTOP)) continue;
                Map<String, Integer> written = new HashMap<>();
                for (NotificationChannel channel : read(info)) {
                    if (!QuietNotificationPolicy.lowers(channel.getImportance())) continue;
                    String key = info.packageName + ":" + channel.getId();
                    int from = channel.getImportance();
                    try {
                        channel.setImportance(QuietNotificationPolicy.MIN);
                        update.invoke(service, info.packageName, info.uid, channel);
                        written.put(key, from);
                    } catch (Exception e) {
                        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                        if (failed.add(key)) System.err.println("notification quieting failed for " + key + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    }
                }
                if (written.isEmpty()) continue;
                // The system may keep an importance (a channel locked by a device role): count only what actually changed.
                for (NotificationChannel channel : read(info)) {
                    String key = info.packageName + ":" + channel.getId();
                    Integer from = written.get(key);
                    if (from == null) continue;
                    if (!QuietNotificationPolicy.lowers(channel.getImportance())) {
                        done++; failed.remove(key);
                        System.err.println("notifications quieted for " + key + ": importance " + from + " -> " + channel.getImportance());
                    } else if (failed.add(key)) System.err.println("notification quieting had no effect for " + key + ": importance is still " + channel.getImportance());
                }
            }
            return done;
        } catch (Exception e) {
            System.err.println("notification quieting unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 0;
        }
    }

    private List<NotificationChannel> read(ApplicationInfo info) {
        ArrayList<NotificationChannel> out = new ArrayList<>();
        try {
            Object slice = channels.invoke(service, info.packageName, info.uid, false);
            if (slice != null) for (Object o : (List<?>) slice.getClass().getMethod("getList").invoke(slice)) if (o instanceof NotificationChannel c) out.add(c);
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (failed.add(info.packageName)) System.err.println("notification channels unreadable for " + info.packageName + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        return out;
    }
}
