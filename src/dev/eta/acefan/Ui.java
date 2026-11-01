package dev.eta.acefan;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Miuix-inspired visual layer for the standalone app: solid grouped cards, large radii,
 * restrained blue accent, generous spacing. Pure view construction; no policy logic here.
 */
public final class Ui {
    public final Context context;
    public final float density;
    public final boolean dark;

    public final int bg, surface, surface2, text, muted, divider, accent, accentSoft, good, warn;
    public final int cBattery, cBoard, cFan, cMos;

    public Ui(Context c) {
        context = c;
        density = c.getResources().getDisplayMetrics().density;
        dark = ThemePref.dark(c);
        if (dark) {
            bg = 0xff101114;
            surface = 0xff1c1d21;
            surface2 = 0xff25272d;
            text = 0xfff4f5f8;
            muted = 0xffa1a5b0;
            divider = 0xff30323b;
            accent = 0xff79a8ff;
            accentSoft = 0xff2a3648;
            good = 0xff7ccda9;
            warn = 0xfff19786;
            cBattery = 0xff79a8ff;
            cBoard = 0xff58cfcf;
            cFan = 0xffb79cf0;
            cMos = 0xfff0a45c;
        } else {
            bg = 0xfff5f6f8;
            surface = 0xffffffff;
            surface2 = 0xffedeff3;
            text = 0xff17181c;
            muted = 0xff626876;
            divider = 0xffe4e7ed;
            accent = 0xff2268dc;
            accentSoft = 0xffdde8fb;
            good = 0xff177c58;
            warn = 0xffbe4d3b;
            cBattery = 0xff2f6bd8;
            cBoard = 0xff0e7c7c;
            cFan = 0xff6a4cb0;
            cMos = 0xffb0631a;
        }
    }

    public int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    public TextView text(CharSequence s, float sizeSp, int color, boolean medium) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    /** Tabular figures so live values do not jitter horizontally. */
    public TextView number(CharSequence s, float sizeSp, int color, boolean medium) {
        TextView t = text(s, sizeSp, color, medium);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    public LinearLayout column() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout row() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(rounded(surface, 24));
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        return l;
    }

    public void ripple(View v, float radiusDp, int baseColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(baseColor);
        content.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x2effffff : 0x1a000000), content, mask));
    }

    public View divider() {
        View v = new View(context);
        v.setBackgroundColor(divider);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        v.setLayoutParams(p);
        return v;
    }

    public LinearLayout.LayoutParams params(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(l);
        p.topMargin = dp(t);
        p.rightMargin = dp(r);
        p.bottomMargin = dp(b);
        return p;
    }

    public TextView chip(CharSequence s, int color, int fill) {
        TextView t = text(s, 12, color, true);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setBackground(rounded(fill, 20));
        return t;
    }

    public static String time(long wall) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(wall));
    }
}
