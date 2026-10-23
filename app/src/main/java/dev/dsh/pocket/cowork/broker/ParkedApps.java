package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** The work-profile apps parked on the owned display, kept across display rebuilds. No Android types, so it is unit
 * tested on the JVM; {@link ProfileWorkspace} reads and writes the root-only file and does the launches.
 *
 * The owned display is created with DESTROY_CONTENT, so a broker restart or a system_server restart destroys the tasks
 * parked on it. An app left without any activity is an empty process that ColorOS soon kills (seen for the work-profile
 * QQ: "o-kill", state empty, minutes after a rebuild); a dead messaging app receives nothing and posts no notification,
 * so the notification triggers never fire. After every new display the apps that were parked are started again, one
 * at a time, and parked behind the AI desktop.
 *
 * The saved set follows what is seen on the display while it is parked, except while a restore is still pending: the
 * display is then still being refilled, and what it shows is not the set to keep. */
public final class ParkedApps {
    /** A restore that keeps failing (locked phone, display not ready) is given up after this many attempts. */
    public static final int MAX_ATTEMPTS = 3;
    private static final String PACKAGE = "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    private final LinkedHashSet<String> apps = new LinkedHashSet<>();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Map<String, Integer> attempts = new HashMap<>();

    /** From the saved file's lines; anything that is not a package name is ignored. */
    public ParkedApps(Collection<String> saved) {
        for (String line : saved) { String name = line.trim(); if (name.matches(PACKAGE) && name.length() <= 255) apps.add(name); }
    }

    /** Apps seen on the parked display. True when the saved set changed and must be written. */
    public synchronized boolean seen(Collection<String> live) {
        if (!pending.isEmpty()) return false;
        LinkedHashSet<String> next = new LinkedHashSet<>();
        for (String name : live) if (name.matches(PACKAGE)) next.add(name);
        if (next.equals(apps)) return false;
        apps.clear(); apps.addAll(next);
        return true;
    }

    /** A new display: every saved app waits to be started again. */
    public synchronized void displayCreated() { pending.clear(); attempts.clear(); pending.addAll(apps); }

    /** The next app to restore, or null. It stays pending until {@link #restored} or {@link #failed}. */
    public synchronized String next() { return pending.peekFirst(); }

    public synchronized void restored(String name) { pending.remove(name); attempts.remove(name); }

    /** A restore that did not work: tried again later, up to {@link #MAX_ATTEMPTS} times. */
    public synchronized void failed(String name) {
        if (!pending.remove(name)) return;
        int count = attempts.merge(name, 1, Integer::sum);
        if (count < MAX_ATTEMPTS) pending.addLast(name); else attempts.remove(name);
    }

    /** No longer installed: forgotten. True when the saved set changed. */
    public synchronized boolean drop(String name) { pending.remove(name); attempts.remove(name); return apps.remove(name); }

    public synchronized List<String> list() { return List.copyOf(apps); }
}
