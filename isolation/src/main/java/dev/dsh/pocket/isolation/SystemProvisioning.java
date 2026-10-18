package dev.dsh.pocket.isolation;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit platform-signed system routing; no arbitrary matching app or chooser fallback. */
final class SystemProvisioning {
    private SystemProvisioning() {}
    @SuppressWarnings("deprecation") static ComponentName resolve(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).addCategory(Intent.CATEGORY_DEFAULT);
        Set<ComponentName> candidates = new LinkedHashSet<>();
        for (ResolveInfo match : pm.queryIntentActivities(query,PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo activity = match.activityInfo;
            if (activity == null || activity.applicationInfo == null) continue;
            ApplicationInfo app = activity.applicationInfo;
            if (!activity.enabled || !activity.exported || !app.enabled
                || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
                || (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0
                || pm.checkSignatures("android",activity.packageName) != PackageManager.SIGNATURE_MATCH) continue;
            if (activity.permission != null && !activity.permission.isEmpty()
                && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) continue;
            candidates.add(new ComponentName(activity.packageName,activity.name));
        }
        if (candidates.size() != 1) throw new SecurityException("Cannot uniquely verify platform provisioning activity");
        return candidates.iterator().next();
    }
    static Intent request(Context context) {
        return new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .addCategory(Intent.CATEGORY_DEFAULT).setComponent(resolve(context))
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,ProfileAdminReceiver.component(context))
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE,true);
    }
}
