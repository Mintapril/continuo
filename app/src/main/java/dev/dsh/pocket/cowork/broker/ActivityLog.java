package dev.dsh.pocket.cowork.broker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.function.Function;

/**
 * Native, model-free activity feed for the task island: which app the model is using and a few KEY actions
 * (opening an app, switching app, typing, confirming, intervention). Taps/swipes/screenshots/waits are never
 * recorded, so the island changes rarely. Text is fixed by the broker (typed content is never included).
 * Pure logic; every method is synchronized.
 */
public final class ActivityLog {
    public static final int MAX_EVENTS = 6;
    /** An app must stay in front for this many observations (watchdog ticks, ~1 s) before it counts as a switch. */
    static final int SWITCH_CONFIRMATIONS = 2;
    private long seq;
    private String pkg = "", app = "", text = "", candidate = "";
    private int candidateSeen;
    private final ArrayDeque<String[]> events = new ArrayDeque<>();

    static String clean(String raw, int max) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length() && b.length() < max; i++) {
            char c = raw.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().trim();
    }

    /** New task or closed workspace: drop everything but keep the sequence monotonic so listeners see the clear. */
    public synchronized void reset() {
        pkg = ""; app = ""; text = ""; candidate = ""; candidateSeen = 0; events.clear(); seq++;
    }

    /** Records a key action. Consecutive identical texts are collapsed. */
    public synchronized void note(String value) {
        String clean = clean(value, 60);
        if (clean.isEmpty()) return;
        if (!events.isEmpty() && events.peekLast()[1].equals(clean) && text.equals(clean)) return;
        seq++;
        text = clean;
        events.addLast(new String[]{String.valueOf(seq), clean});
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    /** The model itself opened/launched [packageName]: announce it immediately. */
    public synchronized void launched(String packageName, String label) {
        pkg = packageName; candidate = ""; candidateSeen = 0;
        app = clean(label, 40).isEmpty() ? packageName : clean(label, 40);
        seq++;
        note("打开 " + app);
    }

    /** Foreground package of the owned display, sampled by the watchdog. Announces an app switch once it is stable. */
    public synchronized void observe(String packageName, Function<String, String> labeler) {
        if (packageName == null || packageName.isEmpty()) return;
        if (packageName.equals(pkg)) { candidate = ""; candidateSeen = 0; return; }
        if (!packageName.equals(candidate)) { candidate = packageName; candidateSeen = 1; }
        else candidateSeen++;
        if (candidateSeen < SWITCH_CONFIRMATIONS && !pkg.isEmpty()) return;
        boolean first = pkg.isEmpty();
        pkg = packageName; candidate = ""; candidateSeen = 0;
        String label = clean(labeler.apply(packageName), 40);
        app = label.isEmpty() ? packageName : label;
        seq++;
        if (!first) note("切换到 " + app);
    }

    public synchronized long seq() { return seq; }

    public synchronized JSONObject json() throws JSONException {
        JSONArray rows = new JSONArray();
        for (String[] e : events) rows.put(new JSONObject().put("id", e[0]).put("text", e[1]));
        return new JSONObject().put("seq", seq).put("app", app).put("text", text).put("events", rows);
    }
}
