package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure decision for pulling work-profile tasks back onto the owned display. No Android types, so the rule is unit
 * tested on the JVM.
 *
 * A profile app, its installer or a notification can start an activity without naming a display (from a service, a
 * receiver, a pending intent). The owned display never takes global focus (OWN_FOCUS, STEAL_TOP_FOCUS_DISABLED), so the
 * system puts such a task on the main screen: the app store's post-install page showed up there and stayed until a
 * reboot, and the broker paused the turn as APP_CONFLICT. Such a task is moved to the owned display instead.
 *
 * A task is taken only when
 *   - an owned display exists,
 *   - it belongs to the verified work profile (a personal task is never touched),
 *   - it is on the main screen (other virtual displays, e.g. an app's own private install display, are left alone),
 *   - it is a root task (a task nested in a container such as split screen may share it with personal tasks), and
 *   - its base package is not the profile owner, whose setup screens the user opens on the main screen on purpose. */
public final class StrayTaskSweep {
    public static final int MAIN_DISPLAY = 0;
    /** TaskInfo.parentTaskId of a root task (ActivityTaskManager.INVALID_TASK_ID). */
    public static final int NO_PARENT = -1;

    public record Task(int taskId, int parentTaskId, int userId, int displayId, String basePackage) {}

    private StrayTaskSweep() {}

    public static List<Integer> targets(List<Task> tasks, int ownedDisplay, int profileUser, String profileOwner) {
        ArrayList<Integer> out = new ArrayList<>();
        if (ownedDisplay <= 0) return out;
        for (Task t : tasks) {
            if (t.userId() != profileUser || t.displayId() != MAIN_DISPLAY) continue;
            if (t.parentTaskId() != NO_PARENT) continue;
            if (profileOwner.equals(t.basePackage())) continue;
            if (t.taskId() > 0 && !out.contains(t.taskId())) out.add(t.taskId());
        }
        return out;
    }
}
