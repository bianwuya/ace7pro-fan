package dev.eta.acefan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Themed replacement for the platform dialogs so dark mode never shows black-on-black text. */
final class Sheet {
    private Sheet() { }

    private static Dialog create(Activity a, Ui ui) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            w.setAttributes(lp);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    private static LinearLayout body(Activity a, Ui ui) {
        LinearLayout col = ui.column();
        col.setBackground(ui.rounded(ui.surface, 28));
        col.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(10));
        return col;
    }

    private static void show(Dialog d, Activity a, Ui ui, View content) {
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        d.setContentView(scroll);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    static void info(Activity a, Ui ui, String title, CharSequence message) {
        Dialog d = create(a, ui);
        LinearLayout col = body(a, ui);
        TextView t = ui.text(title, 18, ui.text, true);
        col.addView(t);
        TextView m = ui.text(message, 13, ui.muted, false);
        m.setLineSpacing(ui.dp(5), 1f);
        col.addView(m, ui.params(-1, -2, 0, 10, 0, 0));
        TextView ok = ui.text("知道了", 15, ui.accent, true);
        ok.setGravity(Gravity.END);
        ok.setPadding(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(14));
        ui.ripple(ok, 16, Color.TRANSPARENT);
        ok.setOnClickListener(v -> d.dismiss());
        col.addView(ok, ui.params(-1, -2, 0, 6, 0, 0));
        show(d, a, ui, col);
    }

    static void choices(Activity a, Ui ui, String title, String[] items, int selected, IntConsumer pick) {
        Dialog d = create(a, ui);
        LinearLayout col = body(a, ui);
        col.addView(ui.text(title, 18, ui.text, true), ui.params(-1, -2, 0, 0, 0, 8));
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12));
            ui.ripple(row, 18, Color.TRANSPARENT);
            TextView label = ui.text(items[i], 15, i == selected ? ui.accent : ui.text, i == selected);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            if (i == selected) row.addView(ui.text("✓", 15, ui.accent, true));
            row.setOnClickListener(v -> {
                d.dismiss();
                pick.accept(index);
            });
            col.addView(row);
        }
        TextView cancel = ui.text("取消", 15, ui.muted, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(14));
        ui.ripple(cancel, 16, Color.TRANSPARENT);
        cancel.setOnClickListener(v -> d.dismiss());
        col.addView(cancel, ui.params(-1, -2, 0, 6, 0, 0));
        show(d, a, ui, col);
    }

    static void confirm(Activity a, Ui ui, String title, CharSequence message,
                        String positive, boolean danger, Runnable onConfirm) {
        Dialog d = create(a, ui);
        LinearLayout col = body(a, ui);
        col.addView(ui.text(title, 18, ui.text, true));
        TextView m = ui.text(message, 13, ui.muted, false);
        m.setLineSpacing(ui.dp(5), 1f);
        col.addView(m, ui.params(-1, -2, 0, 10, 0, 0));
        LinearLayout actions = ui.row();
        actions.setGravity(Gravity.END);
        TextView cancel = ui.text("取消", 15, ui.muted, true);
        cancel.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        ui.ripple(cancel, 18, Color.TRANSPARENT);
        cancel.setOnClickListener(v -> d.dismiss());
        TextView ok = ui.text(positive, 15, danger ? ui.warn : ui.accent, true);
        ok.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        ui.ripple(ok, 18, Color.TRANSPARENT);
        ok.setOnClickListener(v -> {
            d.dismiss();
            onConfirm.run();
        });
        actions.addView(cancel);
        actions.addView(ok);
        col.addView(actions, ui.params(-1, -2, 0, 6, 0, 0));
        show(d, a, ui, col);
    }
}
