package dev.dsh.pocket.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
/** Networkless disposable fixture. No permissions, storage, external intents or user data. */
public final class FixtureActivity extends Activity {
    private int clicks=0,longPresses=0;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("Cowork isolated tool fixture");title.setTextSize(22);layout.addView(title);
        TextView status=new TextView(this);status.setText("clicks=0 long=0");status.setTextSize(18);layout.addView(status);
        Button button=new Button(this);button.setText("Tap target");button.setContentDescription("fixture.tap");
        button.setOnClickListener(v->{clicks++;status.setText("clicks="+clicks+" long="+longPresses);});
        button.setOnLongClickListener(v->{longPresses++;status.setText("clicks="+clicks+" long="+longPresses);return true;});layout.addView(button);
        EditText input=new EditText(this);input.setHint("Unicode input target");input.setContentDescription("fixture.input");input.setSingleLine(true);layout.addView(input);
        Button next=new Button(this);next.setText("Open detail");next.setOnClickListener(v->{TextView page=new TextView(this);page.setText("Detail page — Back returns");page.setTextSize(24);setContentView(page);});layout.addView(next);
        Button secure=new Button(this);secure.setText("Enable protected fixture");secure.setContentDescription("fixture.secure");
        secure.setOnClickListener(v->{getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);TextView page=new TextView(this);page.setText("SECURE FIXTURE — must not be exported");page.setTextSize(24);setContentView(page);});layout.addView(secure);
        ScrollView scroll=new ScrollView(this);scroll.setContentDescription("fixture.scroll");LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        for(int i=1;i<=40;i++){TextView row=new TextView(this);row.setText("Fixture row "+i);row.setTextSize(20);row.setPadding(8,24,8,24);rows.addView(row);}scroll.addView(rows);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
    }
    @Override public void onBackPressed(){onCreate(null);}
}
