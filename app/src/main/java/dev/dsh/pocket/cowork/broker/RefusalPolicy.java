package dev.dsh.pocket.cowork.broker;

import java.util.Set;

/** Pure refusal classification and the watchdog's settling clock (no Android types, so it runs on the JVM).
 *  A survivable refusal is reported to the model; only a boundary violation pauses the task, and pausing a
 *  DSH-managed task cancels its turn ("Turn aborted"). */
public final class RefusalPolicy {
    /** Refusals a session must survive: the model retries (or asks the user) instead of losing the whole workspace.
     *  A boundary violation (foreign task, unapproved app, identity mismatch) is NOT in this class. */
    static final Set<String> SURVIVABLE=Set.of("TEXT_UNSUPPORTED","NODE_STALE","NOT_EDITABLE","SECRET_INPUT",
        "STALE_OBSERVATION","INPUT_REJECTED","APP_LAUNCH_DENIED","LAUNCH_TIMEOUT","TREE_REQUIRED","WAIT_LIMIT","KEY_DENIED",
        "INVALID_GESTURE","ARGUMENTS_INVALID","SCROLL_UNSUPPORTED","TEXT_LIMIT","CONTROL_DENIED","SCREEN_SETTLING","HIGH_IMPACT_SINGLE");
    /** How long the owned display may stay unusable (transitional) before the watchdog pauses as SCREEN_UNRESPONSIVE. */
    public static final long SETTLING_LIMIT_MS=20_000L;
    private RefusalPolicy(){}
    /** A refusal caused by a transition (app switching, a dialog closing, an animated screen) rather than by a
     *  boundary violation: the workspace stays and the model can wait and observe again. */
    public static boolean transientDenied(String code,String message){
        if(code.equals("SCREEN_SETTLING")||code.equals("STALE_OBSERVATION")||code.equals("APP_UNKNOWN"))return true;
        String text=String.valueOf(message);
        // "Cannot verify" is the probe timing out or quarantined: a transport stall, not a boundary verdict.
        if(code.equals("SECURITY_UNAVAILABLE"))return text.startsWith("Cannot verify");
        if(!code.equals("APP_DENIED"))return false;
        return !(text.startsWith("App is not approved")
            ||text.startsWith("Verified managed-profile")||text.startsWith("Verified work profile")
            ||text.startsWith("App is not installed in the work profile"));
    }
    public static boolean survives(String code,String message){return SURVIVABLE.contains(code)||transientDenied(code,message);}
    /** The refusal the model is told about. A transition becomes the actionable SCREEN_SETTLING; a refusal that paused
     *  the task keeps its own code and is never presented as "the session is still running". */
    public static ActionGate.Denied reported(ActionGate.Denied e){
        if(e.code.equals("SCREEN_SETTLING")||e.code.equals("STALE_OBSERVATION")||!transientDenied(e.code,e.getMessage()))return e;
        return new ActionGate.Denied("SCREEN_SETTLING","The screen is changing (app switch, dialog or animation); the session is still running. Call phone_wait, then phone_ui_tree");
    }
    /** Start of the current run of transitional watchdog checks. It belongs to one owner of the display: a new claim,
     *  a closed workspace or any guard that passed starts it over, so a stale start can never pause a later turn. */
    public static final class Settling {
        private long since;
        /** Records one transitional check; true once the run has lasted longer than {@link #SETTLING_LIMIT_MS}. */
        public synchronized boolean expired(long now){
            if(since==0||now<since){since=Math.max(1,now);return false;}
            return now-since>SETTLING_LIMIT_MS;
        }
        public synchronized void reset(){since=0;}
    }
}
