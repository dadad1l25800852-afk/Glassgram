package app.exteraless.crash;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Minimal crash screen independent of Telegram UI initialization. */
public final class GlassGramCrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String report;
        try {
            report = CrashLog.take();
        } catch (Throwable error) {
            report = "Could not read crash report: " + error;
        }
        if (report == null || report.isEmpty()) {
            report = "GlassGram stopped unexpectedly. No Java crash report was captured.";
        }
        final String text = report;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 36, 24, 24);
        TextView title = new TextView(this);
        title.setText("GlassGram — ошибка запуска");
        title.setTextSize(22);
        root.addView(title);
        TextView details = new TextView(this);
        details.setText(text);
        details.setTextIsSelectable(true);
        details.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button copy = new Button(this);
        copy.setText("Скопировать ошибку");
        copy.setGravity(Gravity.CENTER);
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("GlassGram crash", text));
                copy.setText("Скопировано");
            }
        });
        root.addView(copy);
        setContentView(root);
    }
}
