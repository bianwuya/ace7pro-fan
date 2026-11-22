package dev.eta.acefan;

/** 日用策略的独立配置。权重顺序固定：电池、主板、风扇周边、充电 MOS。 */
public final class DailyFanConfig {
    public static final double MIN_START_C = 20.0, MAX_START_C = 50.0, DEFAULT_START_C = 33.0;
    public static final int MAX_LEVEL = 20, DEFAULT_FLOOR = 6, CHANNEL_COUNT = 4;
    public static final DailyFanConfig DEFAULT = new DailyFanConfig(false, DEFAULT_START_C, DEFAULT_FLOOR,
            new double[]{1.0, 0.0, 0.0, 0.0});
    public final boolean enabled;
    public final double startC;
    public final int floor;
    private final double[] weights;

    public DailyFanConfig(boolean enabled, double startC, int floor, double[] weights) {
        if (!(startC >= MIN_START_C && startC <= MAX_START_C))
            throw new IllegalArgumentException("startTemp must be finite and between 20 and 50 °C");
        if (floor < 1 || floor > MAX_LEVEL) throw new IllegalArgumentException("minLevel must be between 1 and 20");
        if (weights == null || weights.length != CHANNEL_COUNT) throw new IllegalArgumentException("four weights required");
        this.weights = weights.clone();
        boolean positive = false;
        for (double w : this.weights) {
            if (!(w >= 0.0 && w <= 100.0)) throw new IllegalArgumentException("weights must be finite and between 0 and 100");
            positive |= w > 0.0;
        }
        if (!positive) throw new IllegalArgumentException("at least one weight must be positive");
        this.enabled = enabled;
        this.startC = startC;
        this.floor = floor;
    }

    public DailyFanConfig withEnabled(boolean value) {
        return value == enabled ? this : new DailyFanConfig(value, startC, floor, weights);
    }

    boolean sameSettings(DailyFanConfig other) {
        if (other == null || enabled != other.enabled || startC != other.startC || floor != other.floor) return false;
        for (int i = 0; i < CHANNEL_COUNT; i++) if (weight(i) != other.weight(i)) return false;
        return true;
    }

    public double[] weights() { return weights.clone(); }
    public double weight(int channel) { return weights[channel]; }

    public double[] proportions() { return proportions((1 << CHANNEL_COUNT) - 1); }

    /** Scale before multiplying so even tiny positive finite weights can be normalized. */
    double[] proportions(int mask) {
        double max = 0.0;
        double[] out = new double[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) if ((mask & (1 << i)) != 0) max = Math.max(max, weights[i]);
        if (!(max > 0.0)) return out;
        double total = 0.0;
        for (int i = 0; i < CHANNEL_COUNT; i++) if ((mask & (1 << i)) != 0) total += (out[i] = weights[i] / max);
        for (int i = 0; i < CHANNEL_COUNT; i++) out[i] /= total;
        return out;
    }
}
