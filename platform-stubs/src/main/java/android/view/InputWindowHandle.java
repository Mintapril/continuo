package android.view;

import android.os.IBinder;

/** Android16 framework signatures ONLY. compileOnly: MUST NOT appear in the APK/Dex. */
public final class InputWindowHandle {
    public int ownerUid;
    public int displayId;
    public int inputConfig;
    public int layoutParamsFlags;
    public int layoutParamsType;
    public IBinder getWindowToken(){throw new AssertionError("compile-only platform stub");}
}
