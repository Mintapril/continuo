package dev.dsh.pocket.cowork.broker;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** User takeover state (pure logic). While active the user, not the model, controls the owned display.
 *  The task phase is NOT changed (the DSH turn stays alive and blocked); the model-facing tools answer
 *  USER_CONTROL until hand-back. Outcomes: active, handed_back, expired, cancelled (none before first use). */
public final class Takeover {
    public static final long MAX_MS = 15 * 60_000L;
    private String id = "", reason = "", by = "", outcome = "none";
    private long started;

    public static String cleanReason(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length() && b.length() < 120; i++) {
            char c = raw.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().trim();
    }
    public synchronized boolean active() { return outcome.equals("active"); }
    /** Idempotent: an already active takeover keeps its id. */
    public synchronized String start(String initiator, String why, long now) {
        if (active()) return id;
        id = UUID.randomUUID().toString(); by = initiator; reason = cleanReason(why); outcome = "active"; started = now;
        return id;
    }
    public synchronized boolean finish(String result) {
        if (!active()) return false;
        if (!result.equals("handed_back") && !result.equals("expired") && !result.equals("cancelled")) throw new IllegalArgumentException(result);
        outcome = result; return true;
    }
    public synchronized boolean expired(long now) { return active() && now - started > MAX_MS; }
    public synchronized String id() { return id; }
    /** Result of the internal phone.intervention_status tool. */
    public synchronized JSONObject status() throws JSONException {
        return new JSONObject().put("active", active()).put("interventionId", id).put("outcome", outcome);
    }
    public synchronized void describe(JSONObject out) throws JSONException {
        out.put("userControl", active()).put("interventionId", active() ? id : "").put("interventionBy", active() ? by : "").put("interventionReason", active() ? reason : "");
    }
}
