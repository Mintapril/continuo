package dev.dsh.pocket.isolation;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/** Only enables the newly provisioned profile of this exact owner; never personal/profile parent. */
public final class ProfileAdminReceiver extends DeviceAdminReceiver {
    static ComponentName component(Context context) { return new ComponentName(context, ProfileAdminReceiver.class); }
    static boolean isOwnedProfile(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        return users != null && users.isManagedProfile() && policy != null
            && policy.isProfileOwnerApp(context.getPackageName()) && policy.isAdminActive(component(context));
    }
    static boolean enableOwnedProfile(Context context) {
        if (!isOwnedProfile(context)) return false;
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        policy.setProfileName(component(context), "Cowork 隔离测试");
        policy.setProfileEnabled(component(context));
        return true;
    }
    @Override public void onProfileProvisioningComplete(Context context, Intent intent) {
        if (ACTION_PROFILE_PROVISIONING_COMPLETE.equals(intent.getAction())) {
            try { enableOwnedProfile(context); } catch (RuntimeException ignored) { /* UI shows actual state. */ }
        }
    }
}
