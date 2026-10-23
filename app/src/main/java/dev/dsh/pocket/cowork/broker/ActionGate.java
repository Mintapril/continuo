package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Platform independent capability gate. Unknown operations and stale observations fail closed. */
public final class ActionGate {
    public static final Set<String> TOOLS = Set.of("phone.status", "phone.apps", "phone.start", "phone.screenshot", "phone.ui_tree", "phone.tap", "phone.long_press", "phone.swipe", "phone.scroll", "phone.read_page", "phone.type_text", "phone.key", "phone.wait", "phone.batch", "phone.gesture", "phone.launch_app", "phone.pause", "phone.stop", "phone.request_intervention", "phone.intervention_status", "phone.notify_owner");
    public static final Set<String> INPUTS = Set.of("phone.tap", "phone.long_press", "phone.swipe", "phone.scroll", "phone.read_page", "phone.type_text", "phone.key", "phone.launch_app");
    public static final class Denied extends RuntimeException {
        public final String code;
        public Denied(String code, String message) { super(message); this.code = code; }
    }
    // Allows model vision + transport latency; screen generation and single-use checks remain mandatory.
    public static final long OBSERVATION_TTL_MS = 60_000L;
    private String owner, observation, fingerprint;
    /** The tree observation is kept beside the pixel one: a screenshot taken afterwards must not invalidate the
     *  element ids the model is still acting on, and a coordinate action may use either of the two. */
    private String treeObservation, treeFingerprint;
    private long observedAt, treeObservedAt;
    private boolean paused = true;
    private final Set<String> allowed = new HashSet<>();
    public ActionGate(Collection<String> packages) { allowed.addAll(packages); }
    public synchronized void checkTool(String tool) { if (!TOOLS.contains(tool)) fail("TOOL_DENIED", "Unknown Cowork capability"); }
    public static boolean eligiblePackage(String name) {
        return name!=null && name.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
            && !name.startsWith("com.android.settings") && !name.contains("termux") && !name.contains("kernelsu")
            && !name.equals("dev.dsh.pocket") && !name.equals("com.android.shell") && !name.contains("packageinstaller");
    }
    public synchronized void allowPackage(String name) {
        if(!eligiblePackage(name))fail("APP_DENIED","Package not eligible for Cowork");allowed.add(name);
    }
    public synchronized void revokePackage(String name) { allowed.remove(name); }
    public synchronized void requirePackage(String name) { if (!allowed.contains(name)) fail("APP_DENIED", "App is not approved for this workspace"); }
    public synchronized void start(String session) {
        if (session == null || !session.matches("[a-zA-Z0-9_-]{1,100}")) fail("SESSION_INVALID", "Invalid session identifier");
        if (owner != null) fail("SESSION_BUSY", "A workspace already owns the display");
        owner = session; paused = false; invalidate();
    }
    public synchronized void requireOwner(String session) { if (owner == null || !owner.equals(session)) fail("SESSION_MISMATCH", "Workspace belongs to another session"); }
    public synchronized void requireActive(String session) { requireOwner(session); if (paused) fail("PAUSED", "Resume is only available to the user"); }
    public synchronized String observe(String session, String screen, long now) {
        requireActive(session); observation = UUID.randomUUID().toString(); fingerprint = screen; observedAt = now; return observation;
    }
    /** A structure read also becomes the binding for element actions. */
    public synchronized String observeTree(String session, String screen, long now) {
        String id = observe(session, screen, now);
        treeObservation = id; treeFingerprint = screen; treeObservedAt = now; return id;
    }
    public synchronized long consume(String session, String id, String screen, long now) {
        requireActive(session);
        // A refused action keeps the observation: the model may retry the same screen (for example a coordinate
        // fallback after an element went stale) instead of being forced to re-observe because of the refusal itself.
        boolean pixel = observation != null && observation.equals(id) && now >= observedAt && now - observedAt <= OBSERVATION_TTL_MS && Objects.equals(fingerprint, screen);
        boolean structure = treeObservation != null && treeObservation.equals(id) && now >= treeObservedAt && now - treeObservedAt <= OBSERVATION_TTL_MS && Objects.equals(treeFingerprint, screen);
        if (!pixel && !structure) fail("STALE_OBSERVATION", "The screen changed since this observation; observe the display again");
        long deadline;
        if (pixel) { deadline = observedAt + OBSERVATION_TTL_MS; invalidate(); }
        else { deadline = treeObservedAt + OBSERVATION_TTL_MS; treeObservation = null; treeFingerprint = null; }
        return deadline;
    }
    /** True when an observation of this generation is still bound and recent (element actions verify the tree instead). */
    public synchronized boolean observing(String session){ return owner != null && owner.equals(session) && observation != null; }
    public synchronized void pause(String session) { requireOwner(session); paused = true; invalidate(); }
    public synchronized void resume(String session) { requireOwner(session); paused = false; invalidate(); }
    public synchronized void stop(String session) { requireOwner(session); owner = null; paused = true; invalidate(); }
    public synchronized void invalidate() { observation = null; fingerprint = null; treeObservation = null; treeFingerprint = null; }
    public synchronized String owner() { return owner; }
    public synchronized boolean paused() { return paused; }
    public synchronized Set<String> packages() { return Set.copyOf(allowed); }
    public static void fail(String code, String message) { throw new Denied(code, message); }
}
