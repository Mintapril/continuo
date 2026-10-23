package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** New text on the AI screen that the model neither caused nor saw. An app in front of the AI screen posts no
 * notification for what arrives meanwhile (a messaging app shows the message, or a banner for another chat, instead),
 * so the notification triggers never hear of it. While no tool runs (the model is thinking) the broker reads the
 * screen about once a second ({@link #idle}): what changes then is not the model's doing. Lines that were not on the
 * screen the model last observed are recorded, except text the model typed this turn (its own message often appears
 * only after the input settled). The model's next observation reports them as {@code appeared}, also when they are gone
 * again (a banner). When the turn ends normally, {@link #detach} hands over what the model never saw, and the final
 * read before the display is parked adds what is new on it ({@link Pending#unseen}): that becomes screen news for the
 * notification triggers. No Android types, so it is unit tested on the JVM.
 *
 * Lines are the labelled texts of the compact tree in reading order; equal lines are counted, so a second "好的"
 * under a first one is new. Nothing is recorded across a change of the app in front, or after an input that returned
 * no observation (launching an app, reading a page): what the model saw is unknown until it observes again. */
public final class ScreenWatch {
    /** Lines kept per report; the newest win. */
    public static final int MAX_LINES = 20;
    /** Texts typed this turn that are remembered for the own-message filter. */
    static final int MAX_TYPED = 20;
    private String pkg = "";
    private Map<String,Integer> baseline = Map.of();
    private boolean known;
    private final LinkedHashMap<String,Integer> recorded = new LinkedHashMap<>();
    private final ArrayDeque<String> typed = new ArrayDeque<>();

    /** A new turn: nothing is known yet. */
    public synchronized void reset() { pkg = ""; baseline = Map.of(); known = false; recorded.clear(); typed.clear(); }

    /** An input the model sent: until its observation arrives, nothing new can be told apart from its effect. */
    public synchronized void acting() { known = false; }

    /** Text the model typed: its own message is never reported back to it. */
    public synchronized void typed(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        typed.addLast(t);
        while (typed.size() > MAX_TYPED) typed.removeFirst();
    }

    /** The model was shown this screen. Returns what appeared by itself since the screen it saw before (to report with
     *  this observation), and makes this screen the one to compare with. */
    public synchronized List<String> observed(String pkg, List<String> lines) {
        List<String> appeared = expand(recorded);
        recorded.clear();
        this.pkg = pkg == null ? "" : pkg; baseline = count(lines); known = true;
        return appeared;
    }

    /** A read while no tool runs. */
    public synchronized void idle(String pkg, List<String> lines) {
        if (!known || pkg == null || pkg.isEmpty() || !pkg.equals(this.pkg)) return;
        for (Map.Entry<String,Integer> e : extra(baseline, count(lines)).entrySet()) {
            if (own(e.getKey(), typed)) continue;
            recorded.merge(e.getKey(), e.getValue(), Math::max);
        }
    }

    /** The turn ends: what is recorded, plus what is needed to judge the final read. Starts over. */
    public synchronized Pending detach() {
        Pending pending = new Pending(pkg, known ? baseline : null, expand(recorded), List.copyOf(typed));
        reset();
        return pending;
    }

    /** What a finished turn left unseen. Immutable; the final read happens outside the broker's locks. */
    public static final class Pending {
        private final String pkg;
        private final Map<String,Integer> baseline;
        private final List<String> recorded, typed;
        Pending(String pkg, Map<String,Integer> baseline, List<String> recorded, List<String> typed) {
            this.pkg = pkg; this.baseline = baseline; this.recorded = recorded; this.typed = typed;
        }
        /** The app these lines belong to: the one the model last observed. */
        public String pkg() { return pkg; }
        /** The recorded lines, then the lines of the final read that the model never saw (only when that read shows the
         *  app it last observed). Newest {@link #MAX_LINES}; empty when there is nothing. */
        public List<String> unseen(String finalPkg, List<String> finalLines) {
            ArrayList<String> out = new ArrayList<>(recorded);
            if (baseline != null && pkg.equals(finalPkg) && finalLines != null) {
                Map<String,Integer> already = count(recorded);
                for (Map.Entry<String,Integer> e : extra(baseline, count(finalLines)).entrySet()) {
                    if (own(e.getKey(), typed)) continue;
                    for (int i = already.getOrDefault(e.getKey(), 0); i < e.getValue(); i++) out.add(e.getKey());
                }
            }
            return out.size() <= MAX_LINES ? List.copyOf(out) : List.copyOf(out.subList(out.size() - MAX_LINES, out.size()));
        }
    }

    /** Text the model typed this turn, as the app shows it back: the same text, part of it, or it inside a longer line. */
    public static boolean own(String line, Collection<String> typed) {
        String l = line.trim();
        for (String t : typed) {
            if (l.equals(t)) return true;
            if (l.length() >= 2 && t.contains(l)) return true;
            if (t.length() >= 2 && l.contains(t)) return true;
        }
        return false;
    }
    private static Map<String,Integer> count(List<String> lines) {
        LinkedHashMap<String,Integer> out = new LinkedHashMap<>();
        if (lines != null) for (String line : lines) if (line != null && !line.isBlank()) out.merge(line, 1, Integer::sum);
        return out;
    }
    /** How many more times each line shows now than before, in the order of {@code now}. */
    private static Map<String,Integer> extra(Map<String,Integer> before, Map<String,Integer> now) {
        LinkedHashMap<String,Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String,Integer> e : now.entrySet()) {
            int more = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (more > 0) out.put(e.getKey(), more);
        }
        return out;
    }
    private static List<String> expand(Map<String,Integer> counted) {
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String,Integer> e : counted.entrySet()) for (int i = 0; i < e.getValue(); i++) out.add(e.getKey());
        return out.size() <= MAX_LINES ? List.copyOf(out) : List.copyOf(out.subList(out.size() - MAX_LINES, out.size()));
    }
}
