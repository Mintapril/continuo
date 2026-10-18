package dev.dsh.pocket.isolation;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

/**
 * Not exported and without intent filters: only the trusted device owner/root `am start --user` can reach it.
 * Changes exactly one profile restriction (installing from unknown sources) and nothing else. It never
 * grants the per-app "install unknown apps" permission; that stays a separate user decision.
 */
public final class SideloadPolicyActivity extends Activity {
    static final String TAG = "CoworkIsolation";
    static final String ACTION_KEY = "action";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        String result = apply(getIntent().getStringExtra(ACTION_KEY));
        Log.i(TAG, "sideload-policy " + result);
        finish();
    }
    private String apply(String action) {
        if (!"allow".equals(action) && !"restrict".equals(action)) return "rejected:action";
        if (!ProfileAdminReceiver.isOwnedProfile(this)) return "rejected:not-owner";
        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        try {
            if ("allow".equals(action)) policy.clearUserRestriction(ProfileAdminReceiver.component(this), UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            else policy.addUserRestriction(ProfileAdminReceiver.component(this), UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            boolean now = policy.getUserRestrictions(ProfileAdminReceiver.component(this)).getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false);
            setResult(RESULT_OK);
            return "ok:" + action + ":restricted=" + now;
        } catch (RuntimeException e) {
            return "failed:" + e.getClass().getSimpleName();
        }
    }
}
