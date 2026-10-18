package android.window;

import android.graphics.Matrix;
import android.util.Pair;
import android.util.Size;
import android.view.InputWindowHandle;

/** Android16 framework signatures ONLY. compileOnly: MUST NOT appear in the APK/Dex. */
public abstract class WindowInfosListener {
    public WindowInfosListener(){throw new AssertionError("compile-only platform stub");}
    public abstract void onWindowInfosChanged(InputWindowHandle[] windows, DisplayInfo[] displays);
    public Pair<InputWindowHandle[],DisplayInfo[]> register(){throw new AssertionError("compile-only platform stub");}
    public void unregister(){throw new AssertionError("compile-only platform stub");}
    public static final class DisplayInfo {
        public final int mDisplayId;
        public final Size mLogicalSize;
        public final Matrix mTransform;
        private DisplayInfo(){throw new AssertionError("compile-only platform stub");}
    }
}
