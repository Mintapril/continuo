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
}
