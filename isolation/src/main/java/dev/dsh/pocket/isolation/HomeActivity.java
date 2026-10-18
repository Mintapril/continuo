package dev.dsh.pocket.isolation;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The AI desktop: the stand-in home screen of the Cowork display.
 *
 * The owned virtual display carries no system decorations, so the personal launcher never appears on it
 * (a parent-user task there would break the profile boundary the broker verifies). This profile-owned
 * activity is what the broker shows when the agent presses HOME: the display stays in a verified
 * profile-user state while the previous app keeps running in the background.
 *
 * Deliberately plain and non-secure: the agent reads it through the accessibility tree and screenshots.
 */
public final class HomeActivity extends Activity {
    private TextView recentsBody;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        view.setGravity(Gravity.CENTER);
        return view;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Color.rgb(241, 245, 250));
        page.setPadding(dp(28), dp(28), dp(28), dp(28));
        page.setFilterTouchesWhenObscured(true);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(dp(28) + bars.left, dp(28) + bars.top, dp(28) + bars.right, dp(28) + bars.bottom);
            return insets;
        });
        TextView tag = text("CONTINUO  /  DESKTOP", 12, Color.rgb(31, 88, 166));
        tag.setTypeface(null, Typeface.BOLD);
        page.addView(tag);
        TextView title = text("AI 桌面", 30, Color.rgb(18, 31, 52));
        title.setTypeface(null, Typeface.BOLD);
        page.addView(title);
        page.addView(text("这个屏幕是 AI 的工作区。刚才的应用仍在后台运行，需要时可以切回。\n\nAI 在对话里可以继续打开已安装的应用；你也可以随时接管这个屏幕。", 16, Color.rgb(70, 86, 107)));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(20));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24);
        page.addView(card, params);
        TextView hint = text("返回：屏幕左/右边缘向内滑\n桌面：屏幕底部向上滑\n后台应用：见下方，需要时让 AI 切回", 15, Color.rgb(70, 86, 107));
        hint.setGravity(Gravity.START);
        card.addView(hint);
        LinearLayout behind = new LinearLayout(this);
        behind.setOrientation(LinearLayout.VERTICAL);
        behind.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable behindBackground = new GradientDrawable();
        behindBackground.setColor(Color.WHITE);
        behindBackground.setCornerRadius(dp(20));
        behind.setBackground(behindBackground);
        LinearLayout.LayoutParams behindParams = new LinearLayout.LayoutParams(-1, -2);
        behindParams.topMargin = dp(16);
        page.addView(behind, behindParams);
        TextView behindTitle = text("后台运行", 13, Color.rgb(31, 88, 166));
        behindTitle.setTypeface(null, Typeface.BOLD);
        behindTitle.setGravity(Gravity.START);
        behind.addView(behindTitle);
        recentsBody = text("", 16, Color.rgb(18, 31, 52));
        recentsBody.setGravity(Gravity.START);
        behind.addView(recentsBody);
        showRecents(getIntent());
        setContentView(page);
    }

    /** What keeps running behind the desktop; refreshed when HOME is pressed again (singleTask). */
    private void showRecents(Intent intent) {
        if (recentsBody == null) return;
        String list = intent == null ? null : intent.getStringExtra("recents");
        if (list == null || list.isEmpty()) {
            recentsBody.setText("没有其它应用在运行");
        } else {
            recentsBody.setText(list.replace("、", "\n"));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showRecents(intent);
    }
}
