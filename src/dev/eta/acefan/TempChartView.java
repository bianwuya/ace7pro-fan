package dev.eta.acefan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.List;

/** Temperature card: legend outside the plot, shared Celsius scale, gaps kept as gaps. */
public final class TempChartView extends ChartView {
    private static final String[] KEYS = {"battery", "board", "fan", "mos"};
    private static final String[] NAMES = {"电池", "板级", "风扇周边", "充电相关"};

    private boolean mini;

    public TempChartView(Context c) {
        super(c);
    }

    public void setMini(boolean value) {
        mini = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        List<Sample> visible = window();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mini) {
            drawMini(c, visible, w, h);
            return;
        }

        int[] colors = {ui.cBattery, ui.cBoard, ui.cFan, ui.cMos};
        boolean[] available = new boolean[KEYS.length];
        for (Sample s : visible) {
            for (int i = 0; i < KEYS.length; i++) {
                if (!Double.isNaN(s.number(KEYS[i]))) available[i] = true;
            }
        }
        float legendBottom = legendDots(c, NAMES, colors, available, ui.dp(2), ui.dp(2), w - ui.dp(2));
        RectF plot = new RectF(ui.dp(34), legendBottom + ui.dp(12), w - ui.dp(10), h - ui.dp(26));
        if (plot.width() <= 0 || plot.height() <= 0) return;

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Sample s : visible) {
            for (String key : KEYS) {
                double v = s.number(key);
                if (!Double.isNaN(v)) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
        }

        if (min == Double.POSITIVE_INFINITY) {
            gridLines(c, plot, 4);
            timeAxis(c, plot);
            empty(c, plot);
            return;
        }

        double pad = Math.max(0.4, (max - min) * 0.10);
        double[] axis = axisRange(min - pad, max + pad, 4);

        gridValues(c, plot, axis[0], axis[1], axis[2]);
        timeAxis(c, plot);
        for (int i = 0; i < KEYS.length; i++) {
            drawSeries(c, visible, KEYS[i], colors[i], 1.8f, false, false, plot, axis[0], axis[1]);
        }
    }

    private void drawMini(Canvas c, List<Sample> visible, float w, float h) {
        RectF plot = new RectF(ui.dp(2), ui.dp(6), w - ui.dp(2), h - ui.dp(6));
        if (plot.width() <= 0 || plot.height() <= 0) return;

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Sample s : visible) {
            double v = s.number("battery");
            if (!Double.isNaN(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min == Double.POSITIVE_INFINITY) {
            empty(c, plot);
            return;
        }
        double pad = Math.max(0.4, (max - min) * 0.25);
        min -= pad;
        max += pad;
        if (max - min < 1) max = min + 1;

        Path line = new Path();
        Path fill = new Path();
        boolean pen = false;
        Sample prev = null;
        float prevX = 0, prevY = 0;
        for (Sample s : visible) {
            double v = s.number("battery");
            if (Double.isNaN(v)) {
                if (pen) {
                    fill.lineTo(prevX, plot.bottom);
                    fill.close();
                    pen = false;
                    prev = null;
                }
                continue;
            }
            float x = plot.left + (float) ((s.wall - start) / (double) Math.max(1L, end - start)) * plot.width();
            float y = yFor(v, plot, min, max);
            boolean gap = prev != null && (s.wall - prev.wall > 30000L || !s.boot.equals(prev.boot));
            if (!pen || gap) {
                if (pen) {
                    fill.lineTo(prevX, plot.bottom);
                    fill.close();
                }
                line.moveTo(x, y);
                fill.moveTo(x, plot.bottom);
                fill.lineTo(x, y);
                pen = true;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            prev = s;
            prevX = x;
            prevY = y;
        }
        if (pen) {
            fill.lineTo(prevX, plot.bottom);
            fill.close();
        }

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(new LinearGradient(0, plot.top, 0, plot.bottom,
                (ui.cBattery & 0x00ffffff) | 0x4d000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawPath(fill, fillPaint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(ui.cBattery);
        c.drawPath(line, paint);
    }
}
