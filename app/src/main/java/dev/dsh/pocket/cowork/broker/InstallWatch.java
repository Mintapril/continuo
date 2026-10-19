package dev.dsh.pocket.cowork.broker;

/** Pure rule of the work-profile install watch ({@link ProfileWorkspace}): which package-installer session events of the
 * profile sweep it at once (launcher entries hidden, background pop-ups closed). No Android types, so the rule is unit
 * tested on the JVM. Only a session that finished successfully has installed something: creation, badging, activity
 * and progress come before its packages exist, and a failed session installed nothing. */
public final class InstallWatch {
    /** The PackageInstaller.SessionCallback events; a finished session is INSTALLED or FAILED. */
    public enum Event { CREATED, BADGING_CHANGED, ACTIVE_CHANGED, PROGRESS_CHANGED, INSTALLED, FAILED }

    private InstallWatch() {}

    public static Event finished(boolean success) { return success ? Event.INSTALLED : Event.FAILED; }

    public static boolean sweeps(Event event) { return event == Event.INSTALLED; }

    /** Cursor over the profile's PackageManager.getChangedPackages sequence, read once a second. It sees every package
     * change of the profile, also those without a session of the profile: install-existing into it, an update committed
     * by a session of the personal user, an uninstall, a component enabled or disabled (the broker's own launcher sweep
     * included, which costs one redundant check). The first read is the baseline: what changed before the broker
     * started is covered by its start-up sweep. */
    public static final class Changes {
        private int sequence;
        private boolean started;

        /** The sequence number to read changes from. */
        public synchronized int from() { return sequence; }

        /** One read: {@code reported} is the sequence number of the result, null when nothing changed for the profile
         *  since {@link #from()}. True when the profile changed since the previous read. */
        public synchronized boolean read(Integer reported) {
            boolean baseline = !started;
            started = true;
            if (reported == null) return false;
            sequence = reported;
            return !baseline;
        }
    }
}
