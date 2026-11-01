package dev.eta.acefan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import java.util.List;

/** Control-level card: module demand and driver-effective by default, diagnostics on demand. */
public final class LevelChartView extends ChartView {
    private static final String[] ALL_NAMES = {"模块需求", "驱动有效", "控制器请求", "驱动设定"};
    private static final String[] ALL_KEYS = {"candidate", "effective", "request", "driver"};

    private boolean diagnostics;

    public LevelChartView(Context c) {
        super(c);
    }

    public void setDiagnostics(boolean value) {
        diagnostics = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        List<Sample> visible = window();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        int[] allColors = {ui.accent, ui.good, ui.cMos, ui.cFan};
        boolean[] allDashed = {true, false, true, true};

        int count = diagnostics ? 4 : 2;
        String[] names = new String[count];
        int[] colors = new int[count];
        boolean[] dashed = new boolean[count];
        for (int i = 0; i < count; i++) {
            names[i] = ALL_NAMES[i];
            colors[i] = allColors[i];
            dashed[i] = allDashed[i];
        }

        boolean[] available = new boolean[count];
        for (Sample s : visible) {
            for (int i = 0; i < count; i++) {
                if (!Double.isNaN(s.number(ALL_KEYS[i]))) available[i] = true;
            }
        }

        float legendBottom = legendLines(c, names, colors, dashed, available, ui.dp(2), ui.dp(2), w - ui.dp(2));
        RectF plot = new RectF(ui.dp(30), legendBottom + ui.dp(12), w - ui.dp(10), h - ui.dp(26));
        if (plot.width() <= 0 || plot.height() <= 0) return;

        // Axis stays stable when diagnostics are toggled.
        double top = 20;
        for (Sample s : visible) {
            for (String key : ALL_KEYS) {
                double v = s.number(key);
                if (!Double.isNaN(v) && v > top) top = Math.ceil(v / 5.0) * 5.0;
            }
        }
        if (top > 40) top = 40;

        grid(c, plot, 0, top, 5, true);
        timeAxis(c, plot);

        boolean any = false;
        for (Sample s : visible) {
            for (int i = 0; i < count; i++) {
                if (!Double.isNaN(s.number(ALL_KEYS[i]))) any = true;
            }
        }
        if (!any) {
            empty(c, plot);
            return;
        }

        for (int i = 0; i < count; i++) {
            drawSeries(c, visible, ALL_KEYS[i], colors[i], 1.9f, dashed[i], true, plot, 0, top);
        }
    }
}
