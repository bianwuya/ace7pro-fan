package dev.eta.acefan;

/**
 * Pure policy: a configurable start temperature plus a continuous level ramp.
 *
 * Replaces the former five-point table. The ramp reproduces that table's shape
 * (33 °C→6, 35 °C→10, 37 °C→14, 39 °C→18, 40 °C→20) but is continuous, so the
 * level no longer jumps four steps at once as the fused temperature drifts.
 *
 * Zero means "no additional demand": the OEM candidate is left untouched.
 */
public final class ChargeCurve {
    public static final double DEFAULT_START = 33.0;
    /** Levels per °C above the start point; 2.0 matches the retired table. */
    public static final double DEFAULT_GAIN = 2.0;
    /** Must stay below the start point before the demand can switch off. */
    public static final double HYSTERESIS_C = 1.0;
    public static final int DEFAULT_FLOOR = 6;
    public static final int DEFAULT_CEILING = 20;
    public static final long COOL_CONFIRM_MS = 30000L;

    private double start = DEFAULT_START;
    private double gain = DEFAULT_GAIN;
    private int floor = DEFAULT_FLOOR;
    private int ceiling = DEFAULT_CEILING;
    private boolean active;
    private int demand;
    private long coolingAt = -1L;

    /** Applies validated settings; out-of-range values keep the current setting. */
    public synchronized void configure(double start, double gain, int floor, int ceiling) {
        if (start >= 20.0 && start <= 50.0) this.start = start;
        if (gain > 0.0 && gain <= 8.0) this.gain = gain;
        if (floor >= 1 && floor <= 20) this.floor = floor;
        if (ceiling >= 1 && ceiling <= 20) this.ceiling = ceiling;
        if (this.ceiling < this.floor) this.ceiling = this.floor;
        if (active) demand = levelFor(demandTemperature);
        else coolingAt = -1L;
    }

    /** Keeps the last fused temperature so a re-configure can re-evaluate immediately. */
    private double demandTemperature = Double.NaN;

    public synchronized int update(double temperature, long elapsed, boolean allowed) {
        if (!allowed || !FusionTemp.valid(temperature)) {
            reset();
            demandTemperature = Double.NaN;
            return 0;
        }
        demandTemperature = temperature;
        if (!active) {
            if (temperature >= start) { active = true; coolingAt = -1L; }
            else { demand = 0; return 0; }
        } else if (temperature < start - HYSTERESIS_C) {
            // A brief dip is not a reason to stop; require it to persist.
            if (coolingAt < 0L) coolingAt = elapsed;
            if (elapsed - coolingAt >= COOL_CONFIRM_MS) {
                active = false; coolingAt = -1L; demand = 0; return 0;
            }
        } else {
            coolingAt = -1L;
        }
        demand = levelFor(temperature);
        return demand;
    }

    private int levelFor(double temperature) {
        if (!FusionTemp.valid(temperature)) return floor;
        double raw = floor + gain * (temperature - start);
        long rounded = Math.round(raw);
        if (rounded < floor) return floor;
        if (rounded > ceiling) return ceiling;
        return (int) rounded;
    }

    public synchronized void reset() { active = false; coolingAt = -1L; demand = 0; }

    /** Current demand; 0 while inactive, otherwise the clamped ramp level. */
    public synchronized int level() { return demand; }

    public synchronized boolean active() { return active; }

    public synchronized double start() { return start; }
    public synchronized double gain() { return gain; }
    public synchronized int floor() { return floor; }
    public synchronized int ceiling() { return ceiling; }

    /** Display-only summary for the settings screen; never changes policy. */
    public static String describe() {
        StringBuilder b = new StringBuilder();
        b.append("启动 ").append(number(DEFAULT_START)).append("°C → ").append(DEFAULT_FLOOR).append(" 档起，");
        b.append("每升高 1°C 增加 ").append(number(DEFAULT_GAIN)).append(" 档，");
        b.append("上限 ").append(DEFAULT_CEILING).append(" 档。");
        b.append("\n\n低于启动点 ").append(number(HYSTERESIS_C)).append("°C 且持续 30 秒才停止；");
        b.append("需求 0 表示不额外增风，原厂行为完全保留。");
        return b.toString();
    }

    /** Runtime summary that reflects the settings in force, not the defaults. */
    public synchronized String summary() {
        return "启动=" + number(start) + "°C floor=" + floor + " gain=" + number(gain)
                + " ceiling=" + ceiling + " demand=" + demand;
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}