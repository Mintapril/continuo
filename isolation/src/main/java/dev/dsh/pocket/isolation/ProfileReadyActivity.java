package dev.dsh.pocket.isolation;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Finalization stays visible until the user acknowledges; no policy or account migration. */
public final class ProfileReadyActivity extends Activity {
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        String action = getIntent().getAction();
        if (!DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE.equals(action)
            && !DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL.equals(action)) { finish(); return; }
        boolean ready;
        try { ready = ProfileAdminReceiver.enableOwnedProfile(this); } catch (RuntimeException e) { ready = false; }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        // Keep system insets on the viewport, so the bottom action can scroll fully clear of navigation.
        scroll.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(dp(24)+bars.left,dp(24)+bars.top,dp(24)+bars.right,dp(24)+bars.bottom); return insets;
        });
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFilterTouchesWhenObscured(true);
        TextView text = new TextView(this); text.setTextSize(19);
        text.setText(ready ? "隔离测试资料已就绪\n\n这是你自己的本地实验，不是公司管理。未复制个人账号或文件，未启动副屏。系统可能保留必要的系统应用。\n\n此版本只执行资料命名和启用；不设置密码、证书、VPN 或清除数据。后续只测试指定应用。\n\n请先点击下方「我已了解，完成」，再返回对话报告结果。按返回键表示取消此完成步骤；按主页键只会把流程留在后台，不能代替完成。Android 可能回滚失败或取消的新建资料。" : "尚未确认本应用是新工作资料的拥有者。没有继续配置，请点击「关闭并报告」，再返回对话报告此提示。");
        layout.addView(text);
        Button done = new Button(this); done.setText(ready ? "我已了解，完成" : "关闭并报告"); done.setFilterTouchesWhenObscured(true);
        final boolean accepted = ready; done.setOnClickListener(v -> { setResult(accepted ? RESULT_OK : RESULT_CANCELED); finish(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(20);
        layout.addView(done,params); scroll.addView(layout); setContentView(scroll); scroll.requestApplyInsets();
    }
}
