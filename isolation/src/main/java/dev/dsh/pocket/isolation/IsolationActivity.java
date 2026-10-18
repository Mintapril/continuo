package dev.dsh.pocket.isolation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** User-driven setup only. No root, shell, network, service, account or data migration. */
public final class IsolationActivity extends Activity {
    private static final int PROVISION_REQUEST = 41;
    private TextView status;
    private Button provision;
    private boolean provisioning;
    private String lastResult = "";
    private ProvisioningAttempt.Store attempts() {
        android.content.SharedPreferences prefs = getSharedPreferences("provisioning_attempt",MODE_PRIVATE);
        return new ProvisioningAttempt.Store() {
            public boolean attempted() { return prefs.getBoolean("attempted",false); }
            public boolean persistAttempt() { return prefs.edit().putBoolean("attempted",true).commit(); }
        };
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (saved != null) { provisioning = saved.getBoolean("provisioning"); lastResult = saved.getString("lastResult", ""); }
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Color.rgb(241,245,250));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24),dp(24),dp(24),dp(24)); page.setFilterTouchesWhenObscured(true);
        scroll.addView(page);
        page.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(dp(24)+bars.left,dp(24)+bars.top,dp(24)+bars.right,dp(24)+bars.bottom); return insets;
        });
        TextView tag = text("CONTINUO  /  EXPERIMENT", 12, Color.rgb(31,88,166));
        tag.setTypeface(null,Typeface.BOLD); page.addView(tag);
        TextView title = text("Cowork 隔离实验", 28, Color.rgb(18,31,52));
        title.setTypeface(null,Typeface.BOLD); page.addView(title);
        page.addView(text("先验证独立资料，再验证主副屏并行。\n当前版本不保证任意应用互不干扰。",16,Color.rgb(70,86,107)));
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18),dp(16),dp(18),dp(16));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(20)); card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(20); page.addView(card,params);
        status = text("检查本地资料状态…",16,Color.rgb(18,31,52)); card.addView(status);
        card.addView(text("这个应用会成为新测试资料的「资料拥有者」。系统授予它该资料内的管理能力，不代表它获得整机设备拥有者身份。此版本只执行资料命名和启用。",15,Color.rgb(70,86,107)));
        card.addView(text("• 无网络、账号、文件读取、传感器或无障碍权限\n• 不迁移个人账号和数据；系统会保留必要系统应用\n• 不设置密码、证书、VPN，不锁屏、不清除资料\n• 不修改现有工作资料，不跳过系统同意页面\n• 不自动启动副屏；不会主动删除资料\n• 若创建失败或中途取消，Android 可能回滚刚创建的测试资料",15,Color.rgb(70,86,107)));
        provision = new Button(this); provision.setText("创建测试工作资料（系统确认）"); provision.setAllCaps(false); provision.setFilterTouchesWhenObscured(true);
        provision.setOnClickListener(v -> confirm()); card.addView(provision);
        Button refresh = new Button(this); refresh.setText("重新检查状态"); refresh.setAllCaps(false); refresh.setFilterTouchesWhenObscured(true);
        refresh.setOnClickListener(v -> refresh()); page.addView(refresh);
        page.addView(text("准备好后返回对话，告知显示的状态。不要向测试资料添加个人账号。接下来的焦点/输入实验可能短暂打扰界面；需要在你知情并监督时单独开始。",14,Color.rgb(70,86,107)));
        setContentView(scroll); refresh();
    }
    private TextView text(String value,int sp,int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setPadding(0,dp(8),0,dp(8)); return view;
    }
    /** Refuse an existing profile, a secondary user, owner state or an ambiguous system response. */
    private String refusal() {
        try {
            UserManager users = getSystemService(UserManager.class);
            DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
            if (users == null || policy == null) return "系统资料服务不可用";
            if (users.isManagedProfile() || !users.isSystemUser()) return "只允许在个人主用户发起创建";
            List<UserHandle> profiles = users.getUserProfiles();
            if (profiles == null || profiles.size() != 1 || !profiles.get(0).equals(Process.myUserHandle())) return "发现已有资料或资料身份不明确；不会修改或替换";
            if (policy.isProfileOwnerApp(getPackageName()) || policy.isDeviceOwnerApp(getPackageName()) || policy.isAdminActive(ProfileAdminReceiver.component(this))) return "本应用已有管理身份；不重复配置";
            if (!ProvisioningAttempt.available(attempts())) return "本次实验已发起过创建；请先核对系统资料状态，不重复启动或自动重试";
            if (!policy.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) return "系统当前不允许创建工作资料；不会绕过限制";
            SystemProvisioning.resolve(this);
            return "";
        } catch (RuntimeException e) { return "无法安全确认创建条件；没有更改配置"; }
    }
    private void refresh() {
        if (status == null) return;
        UserManager users = getSystemService(UserManager.class);
        boolean owned;
        try { owned = ProfileAdminReceiver.isOwnedProfile(this); } catch (RuntimeException e) { owned = false; }
        String denied = refusal();
        long serial = users == null ? -1 : users.getSerialNumberForUser(Process.myUserHandle());
        status.setText((owned ? "已确认：本应用是此工作资料的拥有者" : denied.isEmpty() ? "可以发起系统确认；尚未创建资料" : denied)
            + "\n本应用 UID：" + Process.myUid() + "  ·  资料序号：" + serial
            + (lastResult.isEmpty() ? "" : "\n" + lastResult));
        provision.setEnabled(!provisioning && denied.isEmpty());
        provision.setText(provisioning ? "系统创建流程进行中" : "创建测试工作资料（系统确认）");
    }
    private void confirm() {
        if (provisioning || !refusal().isEmpty()) { refresh(); return; }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("新增独立测试资料")
            .setMessage("仅创建一个名为 Cowork 隔离测试的工作资料。下一页由 Android/ColorOS 展示并请求资料管理授权；你可以取消。\n\n系统可能显示「工作/组织管理」文案，但这是你自己的无网络本地测试应用，不连接企业管理服务器。资料拥有者角色仍具有系统赋予的管理能力。\n\n应用不会主动删除资料或启动副屏。但创建失败或中途取消时，Android 可能回滚刚创建的测试资料；这不涉及已有个人数据。创建期间不要往测试资料写入重要内容。是否继续打开系统确认页？")
            .setNegativeButton("暂不创建", null).setPositiveButton("打开系统确认",(d,w)->begin()).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true)); dialog.show();
    }
    @SuppressWarnings("deprecation") private void begin() {
        if (provisioning || !refusal().isEmpty()) { refresh(); return; }
        // Persist a one-attempt latch before launch. Neither callbacks, activity recreation nor
        // process death reset it; reconciliation after a failed attempt is deliberately manual.
        try {
            Intent request = SystemProvisioning.request(this);
            if (!ProvisioningAttempt.claim(attempts())) { refresh(); return; }
            provisioning=true; lastResult="";
            startActivityForResult(request,PROVISION_REQUEST);
        } catch (RuntimeException e) { provisioning=false; lastResult="系统未能打开创建流程；请返回对话报告，不要改用命令强制创建或自行清除应用数据重试。"; }
        refresh();
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("provisioning",provisioning); out.putString("lastResult",lastResult); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==PROVISION_REQUEST) {
            provisioning=false;
            lastResult=result==RESULT_OK ? "系统同步步骤已完成，仍需核对工作资料最终状态。" : "创建已取消或系统拒绝；请核对资料列表，不自动重试或删除。";
            refresh();
        }
    }
}
