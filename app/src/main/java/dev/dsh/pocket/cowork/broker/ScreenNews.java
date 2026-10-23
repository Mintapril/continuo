package dev.dsh.pocket.cowork.broker;

import org.json.*;
import java.util.*;

/** Text a finished turn left unseen on the AI screen ({@link ScreenWatch}): the app was in front, so it posted no
 * notification for it. The controller reads these from its state ({@code screenNews}, numbered within this broker
 * instance like {@code ownerNotices}) and feeds each into the notification triggers, where Jev decides whether it wakes
 * the app's session. The last {@link #KEEP} are kept, in memory only, each cut to its newest lines within
 * {@link #MAX_CHARS} characters. No Android types, so it is unit tested on the JVM. */
public final class ScreenNews {
    public static final int KEEP = 3, MAX_CHARS = 600;
    private final ArrayDeque<JSONObject> news = new ArrayDeque<>();
    private long seq;

    /** Returns the new entry's number (1, 2, ...), or 0 when there is nothing to post. */
    public synchronized long post(String pkg, String app, List<String> lines, long at) throws JSONException {
        ArrayDeque<String> kept = new ArrayDeque<>();
        int chars = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) continue;
            if (chars + line.length() > MAX_CHARS) { if (kept.isEmpty()) kept.addFirst(line.substring(line.length() - MAX_CHARS)); break; }
            kept.addFirst(line); chars += line.length();
        }
        if (kept.isEmpty() || pkg == null || pkg.isEmpty()) return 0;
        seq++;
        news.addLast(new JSONObject().put("seq", seq).put("package", pkg).put("app", app == null || app.isBlank() ? pkg : app)
            .put("lines", new JSONArray(kept)).put("at", at));
        while (news.size() > KEEP) news.removeFirst();
        return seq;
    }

    /** Oldest first. */
    public synchronized JSONArray json() {
        JSONArray out = new JSONArray();
        for (JSONObject entry : news) out.put(entry);
        return out;
    }
}
