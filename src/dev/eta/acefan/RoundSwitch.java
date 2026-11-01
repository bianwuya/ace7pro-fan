package dev.eta.acefan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Miuix-like pill switch. Purely visual; the Activity owns the persisted state. */
public final class RoundSwitch extends View {
    public interface Listener { void onChanged(boolean checked); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int trackOn, trackOff, thumb;

    private boolean checked;
    private float pos;
    private ValueAnimator animator;
    private Listener listener;

    public RoundSwitch(Context c, Ui ui) {
        super(c);
        density = ui.density;
        trackOn = ui.accent;
        trackOff = ui.dark ? 0xff3a3d45 : 0xffd8dce4;
        thumb = ui.dark ? 0xfff4f5f8 : 0xffffffff;
        setClickable(true);
        setFocusable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        setChecked(value, true);
    }

    public void setChecked(boolean value, boolean animate) {
        if (checked == value && (animate ? isLaidOut() : true)) {
            // still snap the thumb after a rebuild
            if (!animate) {
                pos = value ? 1f : 0f;
                invalidate();
            }
            return;
        }
        checked = value;
        if (animator != null) animator.cancel();
        float target = value ? 1f : 0f;
        if (!animate || !isLaidOut()) {
            pos = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(pos, target);
        animator.setDuration(170);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = (int) (density * 52 + 0.5f);
        int h = (int) (density * 32 + 0.5f);
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        paint.setColor(blend(trackOff, trackOn, pos));
        c.drawRoundRect(0, 0, w, h, r, r, paint);

        float inset = h * 0.12f;
        float thumbRadius = r - inset;
        float travel = w - 2 * inset - 2 * thumbRadius;
        float cx = inset + thumbRadius + travel * pos;
        float cy = h / 2f;

        paint.setColor(0x33000000);
        c.drawCircle(cx, cy + density, thumbRadius, paint);
        paint.setColor(thumb);
        c.drawCircle(cx, cy, thumbRadius, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                performClick();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        setChecked(!checked);
        if (listener != null) listener.onChanged(checked);
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int a = (int) ((from >>> 24) + ((to >>> 24) - (from >>> 24)) * f);
        int r = (int) (((from >> 16) & 0xff) + (((to >> 16) & 0xff) - ((from >> 16) & 0xff)) * f);
        int g = (int) (((from >> 8) & 0xff) + (((to >> 8) & 0xff) - ((from >> 8) & 0xff)) * f);
        int b = (int) ((from & 0xff) + ((to & 0xff) - (from & 0xff)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
