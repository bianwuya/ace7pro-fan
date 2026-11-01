package dev.eta.acefan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shared canvas plumbing for the two trend cards. Only temperature and control levels are
 * drawn here; there is deliberately no RPM series anywhere in this UI.
 */
public class ChartView extends View {
    public static final class Sample {
        public final long wall, elapsed;
        public final String boot;
        public final JSONObject raw;

        Sample(long wall, long elapsed, String boot, JSONObject raw) {
            this.wall = wall;
            this.elapsed = elapsed;
            this.boot = boot;
            this.raw = raw;
        }

        public static Sample parse(JSONObject j) {
            return new Sample(j.optLong("wall", 0), j.optLong("elapsed", 0), j.optString("boot", ""), j);
        }

        /** NaN when the field is missing, null or not a finite number. */
        public double number(String key) {
            Object v = raw.opt(key);
            if (!(v instanceof Number)) return Double.NaN;
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? Double.NaN : d;
        }
    }

    protected final Ui ui;
    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final ArrayList<Sample> data = new ArrayList<>();

    protected int minutes = 30;
    protected long start, end;

    public ChartView(Context c) {
        super(c);
        ui = new Ui(c);
        label.setTextSize(ui.density * 10.5f);
    }

    public void setSamples(List<Sample> s) {
        data.clear();
        if (s != null) data.addAll(s);
        invalidate();
    }

    public void setRange(int m) {
        minutes = m;
        invalidate();
    }

    /** Visible samples plus the time window used by the axes. */
    protected List<Sample> window() {
        long now = System.currentTimeMillis();
        ArrayList<Sample> out = new ArrayList<>();
        if (minutes <= 0) {
            if (data.isEmpty()) {
                start = now - 30L * 60000L;
                end = now;
                return out;
            }
            start = data.get(0).wall;
            end = data.get(data.size() - 1).wall;
            if (end - start < 60000L) end = start + 60000L;
            out.addAll(data);
            return out;
        }
        start = now - minutes * 60000L;
        end = now;
        for (Sample s : data) {
            if (s.wall >= start && s.wall <= end + 2000L) out.add(s);
        }
        return out;
    }

    protected static float yFor(double value, RectF plot, double min, double max) {
        return plot.bottom - (float) ((value - min) / (max - min)) * plot.height();
    }

    protected void drawSeries(Canvas c, List<Sample> visible, String key, int color,
                              float widthDp, boolean dashed, boolean step,
                              RectF plot, double min, double max) {
        if (!(max > min)) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(ui.dp(widthDp));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(dashed ? new DashPathEffect(new float[]{ui.dp(4), ui.dp(3)}, 0) : null);

        Path path = new Path();
        boolean pen = false;
        Sample prev = null;
        for (Sample s : visible) {
            double v = s.number(key);
            if (Double.isNaN(v)) {
                pen = false;
                prev = null;
                continue;
            }
            float x = plot.left + (float) ((s.wall - start) / (double) Math.max(1L, end - start)) * plot.width();
            float y = yFor(v, plot, min, max);
            boolean gap = prev != null && (s.wall - prev.wall > 30000L || s.wall < prev.wall || !s.boot.equals(prev.boot));
            if (!pen || gap) {
                path.moveTo(x, y);
                pen = true;
            } else if (step) {
                float py = yFor(prev.number(key), plot, min, max);
                path.lineTo(x, py);
                path.lineTo(x, y);
            } else {
                path.lineTo(x, y);
            }
            prev = s;
        }
        c.drawPath(path, paint);
        paint.setPathEffect(null);
    }

    protected float legendDots(Canvas c, String[] names, int[] colors, boolean[] available,
                               float left, float top, float right) {
        float columnWidth = (right - left) / 2f;
        float rowHeight = ui.dp(20);
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(ui.density * 11f);
        for (int i = 0; i < names.length; i++) {
            float x = left + (i % 2) * columnWidth;
            float y = top + (i / 2) * rowHeight;
            boolean has = available == null || available[i];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors[i]);
            paint.setAlpha(has ? 255 : 70);
            c.drawCircle(x + ui.dp(4), y + rowHeight / 2f, ui.dp(3.2f), paint);
            paint.setAlpha(255);
            label.setColor(ui.muted);
            label.setAlpha(has ? 255 : 90);
            c.drawText(names[i], x + ui.dp(14),
                    y + rowHeight / 2f - (label.descent() + label.ascent()) / 2f, label);
            label.setAlpha(255);
        }
        label.setTextSize(ui.density * 10.5f);
        return top + ((names.length + 1) / 2) * rowHeight;
    }

    protected float legendLines(Canvas c, String[] names, int[] colors, boolean[] dashed, boolean[] available,
                                float left, float top, float right) {
        float columnWidth = (right - left) / 2f;
        float rowHeight = ui.dp(20);
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(ui.density * 11f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < names.length; i++) {
            float x = left + (i % 2) * columnWidth;
            float y = top + (i / 2) * rowHeight + rowHeight / 2f;
            boolean has = available == null || available[i];
            paint.setColor(colors[i]);
            paint.setAlpha(has ? 255 : 70);
            paint.setPathEffect(dashed[i] ? new DashPathEffect(new float[]{ui.dp(3), ui.dp(2.5f)}, 0) : null);
            c.drawLine(x, y, x + ui.dp(11), y, paint);
            paint.setPathEffect(null);
            paint.setAlpha(255);
            label.setColor(ui.muted);
            label.setAlpha(has ? 255 : 90);
            c.drawText(names[i], x + ui.dp(16),
                    y - (label.descent() + label.ascent()) / 2f, label);
            label.setAlpha(255);
        }
        label.setTextSize(ui.density * 10.5f);
        return top + ((names.length + 1) / 2) * rowHeight;
    }

    protected void gridLines(Canvas c, RectF plot, int ticks) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(ui.divider);
        for (int i = 0; i < ticks; i++) {
            float y = plot.bottom - plot.height() * (i / (float) (ticks - 1));
            c.drawLine(plot.left, y, plot.right, y, paint);
        }
    }

    protected void grid(Canvas c, RectF plot, double min, double max, int ticks, boolean integer) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(ui.divider);
        label.setTextAlign(Paint.Align.RIGHT);
        label.setColor(ui.muted);
        for (int i = 0; i < ticks; i++) {
            float f = i / (float) (ticks - 1);
            float y = plot.bottom - plot.height() * f;
            c.drawLine(plot.left, y, plot.right, y, paint);
            double v = min + (max - min) * f;
            String s;
            if (integer) s = String.valueOf(Math.round(v));
            else s = (max - min) >= 8 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
            c.drawText(s, plot.left - ui.dp(7), y + ui.dp(3.5f), label);
        }
    }

    /** Rounds a range to readable tick steps so labels do not read 33.3 / 37.0 / 40.7. */
    protected static double[] axisRange(double min, double max, int ticks) {
        double span = Math.max(0.5, max - min);
        double raw = span / Math.max(1, ticks - 1);
        double[] candidates = {0.5, 1, 2, 2.5, 3, 4, 5, 10, 20, 25, 50};
        double step = candidates[candidates.length - 1];
        double lo = Math.floor(min / step) * step;
        double hi = lo + step * (ticks - 1);
        // Keep exactly `ticks` labels on step multiples instead of appending a half step.
        for (double candidate : candidates) {
            if (candidate < raw) continue;
            double candidateLo = Math.floor(min / candidate) * candidate;
            double candidateHi = candidateLo + candidate * (ticks - 1);
            step = candidate;
            lo = candidateLo;
            hi = candidateHi;
            if (candidateHi >= max - 1e-9) break;
        }
        return new double[]{lo, hi, step};
    }

    protected void gridValues(Canvas c, RectF plot, double lo, double hi, double step) {
        int count = (int) Math.round((hi - lo) / step) + 1;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(ui.divider);
        label.setTextAlign(Paint.Align.RIGHT);
        label.setColor(ui.muted);
        boolean decimals = step < 1;
        for (int i = 0; i < count; i++) {
            double v = lo + step * i;
            float y = yFor(v, plot, lo, hi);
            c.drawLine(plot.left, y, plot.right, y, paint);
            c.drawText(decimals ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.0f", v),
                    plot.left - ui.dp(7), y + ui.dp(3.5f), label);
        }
    }

    protected void timeAxis(Canvas c, RectF plot) {
        label.setColor(ui.muted);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 0; i <= 2; i++) {
            long t = start + (long) ((end - start) * (i / 2.0));
            float x = plot.left + plot.width() * i / 2f;
            label.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            c.drawText(f.format(new Date(t)), x, plot.bottom + ui.dp(16), label);
        }
        label.setTextAlign(Paint.Align.LEFT);
    }

    protected void empty(Canvas c, RectF area) {
        label.setColor(ui.muted);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(ui.density * 12f);
        c.drawText("该时间范围内暂无数据", area.centerX(), area.centerY(), label);
        label.setTextSize(ui.density * 10f);
        label.setTextAlign(Paint.Align.LEFT);
    }
}
