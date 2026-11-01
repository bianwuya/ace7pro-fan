package dev.eta.acefan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Bottom navigation with a soft pill behind the active icon. */
public final class NavBar extends View {
    public interface Listener { void onSelect(int index); }

    private static final String[] LABELS = {"概览", "趋势", "设置"};

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private int selected;
    private Listener listener;

    public NavBar(Context c, Ui ui) {
        super(c);
        this.ui = ui;
        textPaint.setTextSize(ui.density * 11f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSelected(int index) {
        selected = index;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(getResources().getDisplayMetrics().widthPixels, widthSpec),
                resolveSize(ui.dp(64), heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight() - getPaddingBottom();
        float itemWidth = w / LABELS.length;

        for (int i = 0; i < LABELS.length; i++) {
            float cx = itemWidth * i + itemWidth / 2f;
            boolean active = i == selected;

            if (active) {
                paint.setColor(ui.accentSoft);
                rect.set(cx - ui.dp(28), ui.dp(8), cx + ui.dp(28), ui.dp(38));
                c.drawRoundRect(rect, ui.dp(15), ui.dp(15), paint);
            }

            icon(c, i, cx, ui.dp(23), active ? ui.accent : ui.muted);

            textPaint.setColor(active ? ui.accent : ui.muted);
            textPaint.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            float baseline = h - ui.dp(10);
            c.drawText(LABELS[i], cx, baseline, textPaint);
        }
    }

    private void icon(Canvas c, int index, float cx, float cy, int color) {
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        if (index == 0) {
            float q = ui.dp(6.2f);
            float gap = ui.dp(2.6f);
            paint.setStyle(Paint.Style.FILL);
            float r = ui.dp(2.2f);
            rect.set(cx - gap / 2 - q, cy - gap / 2 - q, cx - gap / 2, cy - gap / 2);
            c.drawRoundRect(rect, r, r, paint);
            rect.set(cx + gap / 2, cy - gap / 2 - q, cx + gap / 2 + q, cy - gap / 2);
            c.drawRoundRect(rect, r, r, paint);
            rect.set(cx - gap / 2 - q, cy + gap / 2, cx - gap / 2, cy + gap / 2 + q);
            c.drawRoundRect(rect, r, r, paint);
            rect.set(cx + gap / 2, cy + gap / 2, cx + gap / 2 + q, cy + gap / 2 + q);
            c.drawRoundRect(rect, r, r, paint);
            return;
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1.7f));
        path.reset();

        if (index == 1) {
            path.moveTo(cx - ui.dp(8), cy + ui.dp(4.5f));
            path.lineTo(cx - ui.dp(3), cy - ui.dp(1.5f));
            path.lineTo(cx + ui.dp(2), cy + ui.dp(2.5f));
            path.lineTo(cx + ui.dp(8), cy - ui.dp(5));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + ui.dp(8), cy - ui.dp(5), ui.dp(1.5f), paint);
            return;
        }

        float[] widths = {16f, 11f, 16f};
        for (int row = 0; row < 3; row++) {
            float y = cy + (row - 1) * ui.dp(5);
            float half = ui.dp(widths[row]) / 2f;
            c.drawLine(cx - half, y, cx + half, y, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int index = (int) (e.getX() / Math.max(1f, getWidth() / (float) LABELS.length));
            if (index < 0) index = 0;
            if (index >= LABELS.length) index = LABELS.length - 1;
            if (index != selected && listener != null) listener.onSelect(index);
            else performClick();
            return true;
        }
        return true;
    }
}
