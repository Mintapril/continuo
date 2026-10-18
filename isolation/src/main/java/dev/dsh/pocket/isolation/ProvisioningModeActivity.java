package dev.dsh.pocket.isolation;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;

/** System-protected handler. Never selects fully-managed/device-owner mode. */
public final class ProvisioningModeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(getIntent().getAction())) {
            ArrayList<Integer> allowed = getIntent().getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES);
            if (allowed != null && allowed.contains(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)) {
                setResult(RESULT_OK, new Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE));
            }
        }
        finish();
    }
}
