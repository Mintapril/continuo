package dev.dsh.pocket.cowork.broker;

import org.json.*;
import java.util.*;

/** Notes for the owner from runs nobody watches (phone.notify_owner): when such a run needs the owner or cannot go on,
 * the model leaves one and ends the task. The controller shows each as a notification; it reads them from the controller
 * state ({@code ownerNotices}), numbered within this broker instance ({@code instanceId}). The last {@link #KEEP} are
 * kept, in memory only. No Android types, so it is unit tested on the JVM. */
public final class OwnerNotices {
    public static final int KEEP = 5, MAX_TEXT = 200;
    private final ArrayDeque<JSONObject> notices = new ArrayDeque<>();
    private long seq;

    /** Returns the new notice's number (1, 2, ...). */
    public synchronized long post(String text, long at) throws JSONException {
        seq++;
        notices.addLast(new JSONObject().put("seq", seq).put("text", text).put("at", at));
        while (notices.size() > KEEP) notices.removeFirst();
        return seq;
    }

    /** Oldest first. */
    public synchronized JSONArray json() {
        JSONArray out = new JSONArray();
        for (JSONObject notice : notices) out.put(notice);
        return out;
    }
}
