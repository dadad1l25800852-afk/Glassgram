package dev.youfree.tab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * YouFree in-app-tab proof of concept.
 * No Google code or YouTube binaries are bundled in this project.
 *
 * The navigation bar belongs to Google and changes across beta releases.
 * This module does not claim to be compatible with untested versions.
 */
public class YouFreeModule implements IXposedHookLoadPackage {
    private static final String TARGET = "com.google.android.youtube";
    private static final String TAG = "youfree.navigation.tab";
    private static final int RED = Color.rgb(255, 0, 51);
    private static final int DARK = Color.rgb(15, 15, 15);
    private static final int SURFACE = Color.rgb(32, 32, 32);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam target) throws Throwable {
        if (!TARGET.equals(target.packageName)) return;
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                final Activity activity = (Activity) p.thisObject;
                activity.getWindow().getDecorView().postDelayed(() -> attach(activity, 0), 500);
            }
        });
    }

    private static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void attach(Activity activity, int attempt) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.getHeight() <= 0) return;

        LinearLayout navigation = findNativeNav(activity, root, root);
        if (navigation == null) {
            if (attempt < 4) {
                root.postDelayed(() -> attach(activity, attempt + 1), 700);
            }
            return; // Never substitute a floating button.
        }

        if (navigation.findViewWithTag(TAG) != null) return;
        final LinearLayout tab = new LinearLayout(activity);
        tab.setTag(TAG);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setContentDescription("YouFree — Подключение");
        tab.setPadding(dp(activity, 4), 0, dp(activity, 4), 0);
        GradientDrawable ripple = new GradientDrawable();
        ripple.setColor(Color.TRANSPARENT);
        tab.setBackground(ripple);

        TextView globe = new TextView(activity);
        globe.setText("◎");
        globe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        globe.setTextColor(Color.WHITE);
        globe.setGravity(Gravity.CENTER);
        tab.addView(globe, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 30)));

        TextView name = new TextView(activity);
        name.setText("Сеть");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        name.setSingleLine(true);
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        tab.addView(name, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 17)));

        tab.setOnClickListener(view -> showConnectionScreen(activity));
        // Weight stays inside the original YouTube navigation row.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        navigation.addView(tab, lp);
    }

    /**
     * Conservative match: native horizontal row close to the screen bottom,
     * with 3–6 siblings resembling tabs. Unsupported versions fail closed.
     * Labels are read only for structure matching, never stored or logged.
     */
    private static LinearLayout findNativeNav(Activity activity, View current, ViewGroup root) {
        if (current instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) current;
            if (row.getOrientation() == LinearLayout.HORIZONTAL &&
                row.getVisibility() == View.VISIBLE &&
                row.getChildCount() >= 3 && row.getChildCount() <= 6 &&
                row.getWidth() > root.getWidth() * .75) {
                int[] rootPos = new int[2], rowPos = new int[2];
                root.getLocationOnScreen(rootPos);
                row.getLocationOnScreen(rowPos);
                int rowBottom = rowPos[1] + row.getHeight();
                int rootBottom = rootPos[1] + root.getHeight();
                int height = row.getHeight();
                if (height >= dp(activity, 45) && height <= dp(activity, 105) &&
                    Math.abs(rootBottom - rowBottom) <= dp(activity, 28) &&
                    containsNavigationLabels(row)) return row;
            }
        }
        if (current instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) current;
            for (int i=0; i<group.getChildCount(); i++) {
                LinearLayout found = findNativeNav(activity, group.getChildAt(i), root);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsNavigationLabels(View view) {
        final List<String> all = new ArrayList<>();
        collectLabels(view, all);
        int found = 0;
        for (String label : all) {
            String text = label.toLowerCase(Locale.ROOT);
            if (text.contains("главная") || text.equals("home") ||
                text.contains("shorts") || text.contains("подписки") ||
                text.contains("subscriptions") || text.equals("you") ||
                text.equals("вы")) found++;
        }
        return found >= 2;
    }

    private static void collectLabels(View view, List<String> dest) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() < 50) dest.add(text.toString());
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i<group.getChildCount(); i++) {
                collectLabels(group.getChildAt(i), dest);
            }
        }
    }

    private static TextView text(Activity activity, String str, int sp, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(str);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(activity, 9), 0, dp(activity, 7));
        return view;
    }

    private static void showConnectionScreen(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("youfree.tab", Context.MODE_PRIVATE);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(DARK);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 20), dp(activity, 20),
            dp(activity, 20), dp(activity, 20));
        scroll.addView(layout);

        layout.addView(text(activity, "YouFree · Подключение", 22, true));
        layout.addView(text(activity, "Настройки прямо в YouTube", 13, false));

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        RadioButton vpn = new RadioButton(activity);
        vpn.setId(View.generateViewId());
        vpn.setText("VPN · VLESS / подписка");
        vpn.setTextColor(Color.WHITE);
        group.addView(vpn);
        RadioButton dpi = new RadioButton(activity);
        dpi.setId(View.generateViewId());
        dpi.setText("ByeByeDPI · стратегия");
        dpi.setTextColor(Color.WHITE);
        group.addView(dpi);
        boolean isVpn = prefs.getString("mode", "vpn").equals("vpn");
        group.check(isVpn ? vpn.getId() : dpi.getId());
        layout.addView(group);

        EditText input = new EditText(activity);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setBackgroundColor(SURFACE);
        input.setPadding(dp(activity, 12), dp(activity, 10),
            dp(activity, 12), dp(activity, 10));
        input.setHint("Вставь ссылку на сервер или стратегию");
        input.setText(prefs.getString("link", ""));
        layout.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 110)));

        layout.addView(text(activity,
            "Нативный сетевой движок ещё не подключён. Эта вкладка сохраняет " +
            "параметры локально, но не изменяет подключение YouTube.", 12, false));

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("Закрыть", (d, i) -> {})
            .setPositiveButton("Сохранить", (d, i) -> {
                String value = input.getText().toString().trim();
                if (value.length() > 4096) {
                    Toast.makeText(activity, "Слишком длинная ссылка", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit()
                    .putString("link", value)
                    .putString("mode", group.getCheckedRadioButtonId() == vpn.getId() ? "vpn" : "dpi")
                    .apply();
                Toast.makeText(activity, "Сохранено. Подключение пока не активно.",
                    Toast.LENGTH_LONG).show();
            }).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(RED);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(
                android.R.color.black);
        });
        dialog.show();
    }
}
