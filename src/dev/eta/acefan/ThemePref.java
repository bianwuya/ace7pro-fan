package dev.eta.acefan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Persisted appearance choice; never touches the control policy. */
final class ThemePref {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private static final String FILE = "ui";
    private static final String KEY = "theme";

    private ThemePref() { }

    static int mode(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int v = p.getInt(KEY, SYSTEM);
        return v == LIGHT || v == DARK ? v : SYSTEM;
    }

    static void set(Context c, int mode) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    }

    static boolean dark(Context c) {
        int m = mode(c);
        if (m == LIGHT) return false;
        if (m == DARK) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static String label(int mode) {
        if (mode == LIGHT) return "浅色";
        if (mode == DARK) return "深色";
        return "跟随系统";
    }
}
