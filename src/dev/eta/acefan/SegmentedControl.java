package dev.eta.acefan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Themed segmented control; replaces the unthemed platform Spinner. */
public final class SegmentedControl extends View {
    public interface Listener { void onSelect(int index); }

    private final Ui ui;
    private final String[] items;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF thumb = new RectF();

    private int selected;
    private Listener listener;

    public SegmentedControl(Context c, Ui ui, String[] items) {
        super(c);
        this.ui = ui;
        this.items = items;
        textPaint.setTextSize(ui.density * 13f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSelected(int index, boolean notify) {
        if (index < 0 || index >= items.length) return;
        selected = index;
        invalidate();
        if (notify && listener != null) listener.onSelect(index);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(getResources().getDisplayMetrics().widthPixels, widthSpec),
                resolveSize(ui.dp(42), heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        paint.setColor(ui.surface2);
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, ui.dp(16), ui.dp(16), paint);

        float itemWidth = w / items.length;
        float inset = ui.dp(3);
        thumb.set(selected * itemWidth + inset, inset, (selected + 1) * itemWidth - inset, h - inset);
        paint.setColor(ui.accentSoft);
        c.drawRoundRect(thumb, ui.dp(13), ui.dp(13), paint);

        for (int i = 0; i < items.length; i++) {
            boolean active = i == selected;
            textPaint.setColor(active ? ui.accent : ui.muted);
            textPaint.setTypeface(android.graphics.Typeface.create(
                    active ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
            float base = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(items[i], itemWidth * i + itemWidth / 2f, base, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int index = (int) (e.getX() / Math.max(1f, getWidth() / (float) items.length));
            if (index < 0) index = 0;
            if (index >= items.length) index = items.length - 1;
            if (index != selected) setSelected(index, true);
            else performClick();
            return true;
        }
        return true;
    }
}
