package dev.eta.acefan;

/**
 * Pure weighted fusion of the four read-only thermal channels.
 *
 * The mean is normalized over channels that are both weight-bearing and valid, so a
 * missing sensor removes that channel's evidence instead of silently dragging the
 * result toward zero. Channels with weight 0 never enter the sum.
 *
 * Default weights are battery-only, which reproduces the OEM kpTemp single-channel
 * behaviour measured on this firmware (RMSE 0.152 °C against the native kpTemp).
 * Enabling mos/board shifts the fused value up by roughly 1–2 °C on this device, so
 * the start threshold must be re-calibrated before those weights are turned on.
 */
public final class FusionTemp {
    /** Channel order shared with the telemetry JSON keys. */
    public static final String[] CHANNELS = {"battery", "mos", "board", "fan"};
    /** battery-only default: no re-calibration is required. */
    public static final double[] WEIGHTS = {1.0, 0.0, 0.0, 0.0};
    private static final double MIN_C = 0.0, MAX_C = 65.0;

    private FusionTemp() { }

    /** A reading inside the verified working band; anything else is unknown, not cold. */
    public static boolean valid(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= MIN_C && v <= MAX_C;
    }

    /** Weighted mean over usable weighted channels; NaN when none carries weight. */
    public static double fuse(double[] values, double[] weights) {
        if (values == null || weights == null) return Double.NaN;
        int n = Math.min(values.length, weights.length);
        double sum = 0, total = 0;
        for (int i = 0; i < n; i++) {
            double w = weights[i];
            if (!(w > 0) || Double.isInfinite(w)) continue;
            double v = values[i];
            if (!valid(v)) continue;
            sum += w * v;
            total += w;
        }
        return total > 0 ? sum / total : Double.NaN;
    }

    public static double fuse(double[] values) { return fuse(values, WEIGHTS); }

    public static int contributors(double[] values) { return contributors(values, WEIGHTS); }

    /** Number of channels that actually contributed to a fused value. */
    public static int contributors(double[] values, double[] weights) {
        if (values == null || weights == null) return 0;
        int n = Math.min(values.length, weights.length), count = 0;
        for (int i = 0; i < n; i++) {
            if (weights[i] > 0 && !Double.isInfinite(weights[i]) && valid(values[i])) count++;
        }
        return count;
    }

    /** Compact weight summary for the diagnostic payload. */
    public static String weightSummary(double[] weights) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < CHANNELS.length && i < weights.length; i++) {
            if (b.length() > 0) b.append(',');
            b.append(CHANNELS[i]).append('=').append(number(weights[i]));
        }
        return b.toString();
    }

    /** Display-only summary for the settings screen; never changes policy. */
    public static String describe() {
        StringBuilder b = new StringBuilder("融合通道：");
        int active = 0;
        for (int i = 0; i < CHANNELS.length && i < WEIGHTS.length; i++) {
            if (!(WEIGHTS[i] > 0)) continue;
            if (active++ > 0) b.append(" · ");
            b.append(CHANNELS[i]).append(' ').append(number(WEIGHTS[i]));
        }
        b.append(active == 0 ? "无（不会启用）" : "");
        b.append("\n\n当前只使用电池温度单通道，与原厂 kpTemp 一致；")
                .append("其余通道权重为 0，不参与计算。改变权重前需要重新标定启动温度。");
        return b.toString();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}