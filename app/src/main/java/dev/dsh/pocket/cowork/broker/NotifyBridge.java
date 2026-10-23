package dev.dsh.pocket.cowork.broker;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.dsh.pocket.notify.NotifyConfig;
import dev.dsh.pocket.notify.NotifyDefaults;
import dev.dsh.pocket.notify.NotifyRelay;
import dev.dsh.pocket.notify.NotifyThrottle;
import dev.dsh.pocket.notify.NotifyWire;
import dev.dsh.pocket.notify.Posted;
import dev.dsh.pocket.notify.ProfileShade;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The notification triggers' reader, in the root broker: a system notification listener for every user. Continuo used
 * to read them itself, but ColorOS freezes Continuo a few seconds after the screen goes off and holds its listener
 * callbacks until the screen is on again; the broker is never frozen. Watched notifications go to {@link NotifyRelay}
 * (batch, Jev ingress, wake lock); once read or not, those of the Cowork profile's third-party apps leave the owner's
 * shade ({@link ProfileShade}). Continuo edits the settings ({@code POST /v1/notify-settings}); they are kept in a root-only
 * file, so the triggers work after a boot before Continuo runs.
 */
final class NotifyBridge {
    private static final String TOKEN = "/data/local/dsh-native/rootfs/root/.config/dsh/cowork-notify.token";
    private static final String WAKE_LOCK = "cowork-notify";
    private final Path settings;
    private final Context system;
    private final IntSupplier profileUserId;
    private final Function<String, String> profileLabel;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "notify-relay"));
    private final NotifyRelay relay;
    private ScheduledFuture<?> pending;
    private volatile String secret;

    NotifyBridge(Context system, Path settings, IntSupplier profileUserId, Function<String, String> profileLabel) {
        this.system = system; this.settings = settings; this.profileUserId = profileUserId; this.profileLabel = profileLabel;
        relay = new NotifyRelay(new NotifyRelay.Host() {
            @Override public long now() { return System.currentTimeMillis(); }
            @Override public void arm(long delayMs) { schedule(delayMs); }
            @Override public NotifyWire.Outcome send(NotifyThrottle.Batch batch) { return deliver(batch); }
            @Override public void awake(long ms) { holdCpu(ms); }
        });
        try { NotifyConfig saved = NotifyConfig.Companion.parse(new JSONObject(new String(Files.readAllBytes(settings), StandardCharsets.UTF_8))); if (saved != null) relay.configure(saved); }
        catch (Exception none) {} // no file yet: off until Continuo sends the settings
    }

    /** Registers the listener on the main looper's thread; callbacks arrive there. */
    void start() throws Exception {
        // The listener rebuilds old apps' notifications for clients targeting an SDK before N, judged by its context's
        // ApplicationInfo; the system context reports 0 there, and rebuilding a work-profile app's notification from it
        // fails, which drops the notification. A wrapper reports the current SDK for the listener only.
        ApplicationInfo info = new ApplicationInfo(system.getApplicationInfo());
        info.targetSdkVersion = Build.VERSION.SDK_INT;
        Context context = new ContextWrapper(system) { @Override public ApplicationInfo getApplicationInfo() { return info; } };
        NotificationListenerService listener = new NotificationListenerService() {
            @Override public void onNotificationPosted(StatusBarNotification sbn) { try { posted(this, sbn); } catch (Throwable e) { System.err.println("notify read failed: " + e.getClass().getSimpleName()); } }
        };
        NotificationListenerService.class.getMethod("registerAsSystemService", Context.class, ComponentName.class, int.class)
            .invoke(listener, context, new ComponentName("android", "dev.dsh.pocket.cowork.NotifyBridge"), -1); // UserHandle.USER_ALL
    }

    private void posted(NotificationListenerService listener, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (n == null || pkg == null) return;
        // The system puts the posting app's ApplicationInfo into every notification (Notification.EXTRA_BUILDER_APPLICATION_INFO).
        ApplicationInfo app = n.extras.getParcelable("android.appInfo", ApplicationInfo.class);
        if (relay.watches(pkg)) relay.posted(read(sbn, n), label(pkg, app));
        Boolean system = app == null ? null : (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        boolean sticky = (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
        if (ProfileShade.INSTANCE.clears(sbn.getUserId(), profileUserId.getAsInt(), pkg, system, sticky)) listener.cancelNotification(sbn.getKey());
    }

    private static Posted read(StatusBarNotification sbn, Notification n) {
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (title == null) title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        return new Posted(sbn.getPackageName(), sbn.getKey(), sbn.getUserId() != 0, n.getChannelId() == null ? "" : n.getChannelId(), n.category,
            (n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0, (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
            title == null ? "" : title.toString(), text == null ? "" : text.toString(), sbn.getPostTime(), false);
    }

    private String label(String pkg, ApplicationInfo app) {
        String found = null;
        try { if (app != null && pkg.equals(app.packageName)) found = app.loadLabel(system.getPackageManager()).toString(); } catch (Exception ignored) {}
        if (found == null || found.isBlank() || found.equals(pkg)) try { found = profileLabel.apply(pkg); } catch (Exception ignored) {}
        return NotifyDefaults.INSTANCE.label(found == null || found.equals(pkg) ? null : found, pkg);
    }

    /** Text a finished turn left unseen on the AI screen of [pkg]. Never throws. */
    void screen(String pkg, String app, java.util.List<String> lines, long at, String key) {
        try { relay.screen(pkg, app, lines, at, key); } catch (Throwable e) { System.err.println("notify screen text failed: " + e.getClass().getSimpleName()); }
    }

    /** Applies and keeps settings from Continuo; null when the body is no valid config. */
    JSONObject configure(JSONObject body) throws Exception {
        NotifyConfig config = NotifyConfig.Companion.parse(body);
        if (config == null) return null;
        Path next = settings.resolveSibling(settings.getFileName() + ".new");
        Files.write(next, config.json().toString().getBytes(StandardCharsets.UTF_8));
        Files.move(next, settings, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        relay.configure(config);
        return json();
    }

    /** For the controller state: the settings in force and the newest outcomes. */
    JSONObject json() throws Exception {
        JSONArray log = new JSONArray();
        for (NotifyRelay.LogEntry entry : relay.log()) log.put(new JSONObject().put("at", entry.getAt()).put("text", entry.getText()));
        return new JSONObject().put("settings", relay.getConfig().json()).put("log", log);
    }

    private synchronized void schedule(long delayMs) {
        if (pending != null) pending.cancel(false);
        pending = timer.schedule(() -> { try { relay.flush(); } catch (Throwable e) { System.err.println("notify flush failed: " + e.getClass().getSimpleName()); } }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** A kernel wake lock with a timeout: it lapses on its own, also if the broker dies while holding it. */
    private static void holdCpu(long ms) {
        try {
            if (ms > 0) Files.write(Path.of("/sys/power/wake_lock"), (WAKE_LOCK + " " + TimeUnit.MILLISECONDS.toNanos(ms)).getBytes(StandardCharsets.US_ASCII));
            else Files.write(Path.of("/sys/power/wake_unlock"), WAKE_LOCK.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {} // not held (ms == 0 and none held), or the kernel has no wake locks
    }

    private NotifyWire.Outcome deliver(NotifyThrottle.Batch batch) {
        for (boolean retried = false; ; retried = true) {
            String key = secret != null ? secret : (secret = token());
            long sentAt = System.currentTimeMillis();
            byte[] body = NotifyWire.INSTANCE.body(batch, UUID.randomUUID().toString(), sentAt).getBytes(StandardCharsets.UTF_8);
            int code; String text;
            try {
                HttpURLConnection http = (HttpURLConnection) new URL(NotifyWire.URL).openConnection();
                http.setInstanceFollowRedirects(false); http.setConnectTimeout(3000); http.setReadTimeout(15000);
                http.setRequestMethod("POST"); http.setDoOutput(true); http.setFixedLengthStreamingMode(body.length);
                http.setRequestProperty("Content-Type", "application/json");
                http.setRequestProperty(NotifyWire.SIGNATURE_HEADER, NotifyWire.INSTANCE.mac(key, body));
                try (OutputStream out = http.getOutputStream()) { out.write(body); }
                code = http.getResponseCode();
                try (InputStream in = code < 400 ? http.getInputStream() : http.getErrorStream()) { text = in == null ? "" : readSmall(in); }
                http.disconnect();
            } catch (java.io.IOException e) { throw new IllegalStateException("DSH 未运行，或 Cowork 插件未部署通知入口"); }
            if (code == 401 && !retried) { secret = null; continue; } // token re-provisioned: read it again
            if (code != 200 && code != 202) throw new IllegalStateException("通知入口拒绝（HTTP " + code + (text.isBlank() ? "" : "：" + text.substring(0, Math.min(80, text.length()))) + "）");
            return NotifyWire.INSTANCE.outcome(text);
        }
    }

    /** The shared ingress token, created by the service script on first use. */
    private static String token() {
        try {
            if (!Files.exists(Path.of(TOKEN))) new ProcessBuilder("/system/bin/sh", "/data/local/dsh-native/cowork-service.sh", "notify-token").redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS);
            String text = new String(Files.readAllBytes(Path.of(TOKEN)), StandardCharsets.US_ASCII).trim();
            if (text.matches("[0-9a-f]{64}")) return text;
        } catch (Exception ignored) {}
        throw new IllegalStateException("无法读取通知入口令牌");
    }

    private static String readSmall(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
