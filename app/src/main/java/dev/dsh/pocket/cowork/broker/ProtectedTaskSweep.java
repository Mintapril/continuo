package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure decision for clearing a protected window (FLAG_SECURE) off the owned display. No Android types, so the rule is
 * unit tested on the JVM.
 *
 * Such a window can be neither read nor touched: every check refuses it, the AI's HOME and launches as well as the
 * person's input during a takeover. Left alone it pauses every later turn at its first tool: on 2026-10-30 a Zhihu page
 * kept the AI screen blocked for 31 hours, until the broker restarted. Covering it with the AI desktop is not enough,
 * since a window behind the desktop stays in the window hierarchy (and in the check). The task that holds it is removed,
 * as a person closes a page from the recents list: task management only, nothing of the window is read or captured.
 *
 * The protection check names the root task of each protected window (WindowStateProto.stack_id); the task list names
 * leaf tasks, which are the same on the owned display unless one is nested in a container. A task is removed only when
 *   - an owned display exists,
 *   - it is a named root task that is in the task list itself, or the parent of listed tasks, and
 *   - every listed task it stands for is on the owned display, belongs to the verified work profile (a personal task is
 *     never touched) and is not the AI desktop (the profile owner). */
public final class ProtectedTaskSweep {
    private ProtectedTaskSweep() {}

    public static List<Integer> targets(Set<Integer> protectedTasks, List<StrayTaskSweep.Task> tasks, int ownedDisplay, int profileUser, String desktop) {
        ArrayList<Integer> out = new ArrayList<>();
        if (ownedDisplay <= 0) return out;
        for (int root : protectedTasks) {
            if (root <= 0 || out.contains(root)) continue;
            boolean found = false, ours = true;
            for (StrayTaskSweep.Task t : tasks) {
                if (t.taskId() != root && t.parentTaskId() != root) continue;
                found = true;
                if (t.userId() != profileUser || t.displayId() != ownedDisplay || desktop.equals(t.basePackage())) ours = false;
            }
            if (found && ours) out.add(root);
        }
        return out;
    }
}
