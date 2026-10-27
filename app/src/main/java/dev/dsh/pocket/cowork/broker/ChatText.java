package dev.dsh.pocket.cowork.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The text of one phone.read_chat: a chat's message list read upward, screen by screen, joined into one text in screen
 *  order (the oldest message first, the screen reading began on last). A chat repeats sender names, times and short
 *  replies on every screen, so screens are joined by position, not by text: the distance the list moved is the one at
 *  which the lines of the screen above line up with the screen below, and of the screen above only the lines wholly
 *  above the top edge of the screen below are new. */
public final class ChatText {
    public static final int MAX = 60000;
    /** One text line of the message list; top and bottom are its visible bounds on the screen (cut by the list's edges). */
    public record Line(String text, int top, int bottom) {}
    /** The message list on one screen: its lines in screen order, the top and bottom edges of the list, and whether it
     *  is at the top of what it holds (it cannot scroll further up). */
    public record Screen(List<Line> lines, int top, int bottom, boolean atTop) {}

    private ArrayList<String> lines = new ArrayList<>();
    private Screen below;
    private int pages;

    /** Adds the screen above the one added before (the list scrolled up); false when the text is full and reading should
     *  stop. finger: how far the finger moved the list since then, which picks between the distances a chat that repeats
     *  itself (a run of 好的 from one person) leaves open; the list moves a little less (touch slop), or much less at the top. */
    public boolean add(Screen screen, int finger) {
        List<String> fresh = below == null ? texts(screen.lines()) : fresh(screen, below, finger);
        below = screen;
        ArrayList<String> next = new ArrayList<>(fresh);
        next.addAll(lines);
        int length = String.join("\n", next).length();
        // A screen that no longer fits is left out whole, so what is cut is the oldest end, not where reading began.
        if (pages > 0 && length > MAX) return false;
        lines = next; pages++;
        return length <= MAX;
    }

    /** The lines of the screen above that the screen below did not show. A line in the same place on both screens is a
     *  bar that did not move with the list (a title or input bar, a "new messages" chip inside the scrolling node) or a
     *  message that happens to sit where another with the same text sat; it is left out as a bar when the screen above,
     *  which shows the screen below that distance further down, shows no such line there. */
    static List<String> fresh(Screen above, Screen below, int finger) {
        int shift = shift(above, below, finger);
        if (shift >= 0) {
            ArrayList<String> fresh = new ArrayList<>();
            for (Line line : above.lines()) {
                if (line.bottom() > above.top() + shift) continue;
                if (bar(line, below) && line.bottom() + shift <= above.bottom() && !shows(above, line, shift)) continue;
                fresh.add(line.text());
            }
            return fresh;
        }
        // No distance to measure: the screens share at most one line, the last above and the first below (a message taller
        // than the overlap, or a sliver of one). Where they differ they share nothing.
        ArrayList<Line> lines = new ArrayList<>(above.lines());
        Line seam = seam(above, below);
        if (seam != null) lines.remove(seam);
        return texts(lines);
    }

    /** How far down the list moved from the screen below to the screen above. A candidate is a distance at which some
     *  line of the screen above lands on a line with the same text below; it holds when, checked both ways (the lines of
     *  each screen it puts on the other must be there), it misplaces at most one line for three it places (a line that
     *  changed meanwhile). Short of the top the list moves with the finger, less the touch slop, so the candidate nearest
     *  the finger's travel (at most NEAR short of it) is taken: a chat that repeats itself (a run of 好的) also holds a
     *  whole message further or nearer. When none is that near, the overlap is a sliver of the line at the seam if the
     *  screens' edge lines match (-1). At the top, where the list moves less, or otherwise (the list flung on), the
     *  candidate that places the most lines, at least two or one longer than 8 characters, so a chance match of a short
     *  label across screens that do not overlap is none. -1 when none holds. A chat of one short reply over and over can
     *  still leave the distance open at the top: the run then comes out one message short or long. */
    static int shift(Screen above, Screen below, int finger) {
        TreeSet<Integer> candidates = new TreeSet<>();
        for (Line a : above.lines()) for (Line b : below.lines()) if (a.text().equals(b.text())) {
            if (a.top() > above.top() && b.top() > below.top()) candidates.add(a.top() - b.top());
            if (a.bottom() < above.bottom() && b.bottom() < below.bottom()) candidates.add(a.bottom() - b.bottom());
        }
        int nearest = -1, most = -1, mostPlaced = 0;
        for (int d : candidates.descendingSet()) {
            if (d <= 0) break;
            // The lines of the screen above that d puts on the screen below must be there, and the other way round.
            int[] down = land(above, below, -d), up = land(below, above, d);
            int placed = down[0] + up[0], misplaced = down[1] + up[1];
            if (down[0] == 0 || misplaced * 3 > placed) continue;
            // Near the finger's travel the position itself is the evidence, so one placed line will do.
            if (d >= finger - NEAR && d <= finger + 8 && (nearest < 0 || Math.abs(d - finger) < Math.abs(nearest - finger))) nearest = d;
            if ((down[0] >= 2 || down[2] > 0) && placed > mostPlaced) { most = d; mostPlaced = placed; } // descending: a tie keeps the larger distance
        }
        if (!above.atTop()) {
            if (nearest >= 0) return nearest;
            // Nothing measurable near the finger: the overlap is a sliver of the one line at the seam (fresh() drops it).
            if (seam(above, below) != null) return -1;
        }
        return most;
    }

    /** How much less than the finger the list moves short of its top: the touch slop (8 or 16 dp), with room to spare.
     *  Far less than one message, so a chat that repeats itself cannot put a wrong distance this near. */
    static final int NEAR = 96;

    /** The line that spans the seam: the last line of the screen above when the first line below has its text. Failing
     *  that, the same leaving out the lines that may be bars (an input bar last above, a title first below). Null when
     *  neither holds. */
    private static Line seam(Screen above, Screen below) {
        Line a = last(above.lines()), b = below.lines().isEmpty() ? null : below.lines().get(0);
        if (a != null && b != null && a.text().equals(b.text())) return a;
        List<Line> listBelow = moved(below, above).lines();
        a = last(moved(above, below).lines()); b = listBelow.isEmpty() ? null : listBelow.get(0);
        return a != null && b != null && a.text().equals(b.text()) ? a : null;
    }

    private static Line last(List<Line> lines) { return lines.isEmpty() ? null : lines.get(lines.size() - 1); }

    /** The lines of from that, moved by by, land on the list of to, compared by an edge neither screen cut (a line cut on
     *  every edge it has there says nothing): {found there, not found, found and longer than 8 characters}. */
    private static int[] land(Screen from, Screen to, int by) {
        int placed = 0, misplaced = 0, longPlaced = 0;
        for (Line l : from.lines()) {
            if (l.bottom() + by <= to.top() || l.top() + by >= to.bottom()) continue;
            boolean byTop = l.top() > from.top() && l.top() + by > to.top(), byBottom = l.bottom() < from.bottom() && l.bottom() + by < to.bottom();
            if (!byTop && !byBottom) continue;
            boolean found = false;
            for (Line m : to.lines()) if (m.text().equals(l.text()) && (byTop && m.top() == l.top() + by || byBottom && m.bottom() == l.bottom() + by)) { found = true; break; }
            if (!found) { if (!bar(l, to)) misplaced++; } // a bar that stayed put on both screens says nothing
            else { placed++; if (l.text().length() > 8) longPlaced++; }
        }
        return new int[] { placed, misplaced, longPlaced };
    }

    /** Whether screen shows line moved down by shift (by either edge: an edge of the list may cut it). */
    private static boolean shows(Screen screen, Line line, int shift) {
        for (Line l : screen.lines()) if (l.text().equals(line.text()) && (l.top() == line.top() + shift || l.bottom() == line.bottom() + shift)) return true;
        return false;
    }

    /** screen without the lines that may be bars. */
    private static Screen moved(Screen screen, Screen other) {
        ArrayList<Line> lines = new ArrayList<>();
        for (Line line : screen.lines()) if (!bar(line, other)) lines.add(line);
        return new Screen(lines, screen.top(), screen.bottom(), screen.atTop());
    }

    /** A line that may be a bar: the other screen shows it in the very same place. A message can sit where another with
     *  the same text sat, so this alone does not make it one. */
    private static boolean bar(Line line, Screen other) {
        return other.lines().contains(line);
    }

    private static List<String> texts(List<Line> lines) {
        ArrayList<String> out = new ArrayList<>();
        for (Line line : lines) out.add(line.text());
        return out;
    }

    /** Screens whose lines are in the text. */
    public int pages() { return pages; }

    public String text() {
        String text = String.join("\n", lines);
        return text.length() <= MAX ? text : text.substring(text.length() - MAX);
    }
}
