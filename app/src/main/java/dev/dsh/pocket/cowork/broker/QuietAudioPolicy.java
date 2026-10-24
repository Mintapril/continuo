package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure rule for keeping the work profile's apps silent ({@link QuietAudioGate}). No Android types, so it is unit tested
 * on the JVM.
 *
 * Android does not route sound by display: a video an app plays on the owned display comes out of the phone's speaker.
 * The AI hears nothing, and the user asked that its videos and voice messages never sound on the phone. So the
 * "play audio" app-op (OP_PLAY_AUDIO, what `cmd appops set PKG PLAY_AUDIO ignore` writes) is IGNORED for every
 * third-party app of the profile other than its owner, including apps installed later: the app keeps playing, at zero
 * volume. A mode that denies already (IGNORED, ERRORED) is left alone. The personal user's apps are never touched. */
public final class QuietAudioPolicy {
    /** AppOpsManager.OP_PLAY_AUDIO; the gate checks its public name before writing. */
    public static final int OP_PLAY_AUDIO = 28;
    public static final String OP_NAME = "android:play_audio";

    public record App(String packageName, int uid, boolean system) {}
    public record Change(String packageName, int uid, int from) {}
    /** Raw mode of OP_PLAY_AUDIO for `app`; a negative value means it could not be read (the app is skipped). */
    public interface Modes { int mode(App app); }

    private QuietAudioPolicy() {}

    public static List<Change> changes(List<App> apps, String profileOwner, Modes modes) {
        ArrayList<Change> out = new ArrayList<>();
        for (App app : apps) {
            if (app.system() || profileOwner.equals(app.packageName())) continue;
            int from = modes.mode(app);
            if (from < 0 || from == PopupPermissionPolicy.MODE_IGNORED || from == PopupPermissionPolicy.MODE_ERRORED) continue;
            out.add(new Change(app.packageName(), app.uid(), from));
        }
        return out;
    }
}
