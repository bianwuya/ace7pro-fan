package dev.eta.acefan;

/** Quantized OEM normal-profile slew, transcribed from the audited native loop.
 * Demand remains DailyFanPolicy/ChargeCurve; this is not the OEM temperature model.
 * State is the last software output, NEVER the driver's effective/derated level.
 */
final class OemFanRamp {
    private final int minimum, maximum;
    private final double up, down;
    private int level;
    private long changedSeconds;

    OemFanRamp(int minimum, int maximum) { this(minimum, maximum, 0.2, 0.1); }
    OemFanRamp(int minimum, int maximum, double up, double down) {
        if (maximum < 1 || minimum < 1 || minimum > maximum || !(up > 0) || !(down > 0)
                || Double.isInfinite(up) || Double.isInfinite(down)) throw new IllegalArgumentException("ramp profile");
        this.minimum = minimum; this.maximum = maximum; this.up = up; this.down = down;
    }

    int update(int target, long nowSeconds, boolean newlyStartedWhileOff) {
        int wanted = Math.max(0, Math.min(target, maximum));
        if (newlyStartedWhileOff && wanted > 0) return set(minimum, nowSeconds);
        if (wanted == 0 && level <= minimum) return set(0, nowSeconds);
        if (wanted > 0 && wanted <= minimum) wanted = minimum;
        if (changedSeconds == 0) return set(wanted, nowSeconds);
        long elapsed = Math.max(1L, nowSeconds - changedSeconds);
        double rate = wanted > level ? up : down;
        int rateTarget = wanted == 0 ? minimum : wanted;
        if (rateTarget == level) return set(level, nowSeconds);
        int delta = (int) Math.min(Integer.MAX_VALUE, rate * elapsed); // truncate; no carried fractions
        if (delta < 1) return level; // pending MUST NOT change the timestamp
        return set(level < rateTarget ? (int)Math.min(rateTarget, (long)level + delta)
                : (int)Math.max(rateTarget, (long)level - delta), nowSeconds);
    }

    void seed(int requestedLevel, long nowSeconds) { set(Math.max(0, Math.min(maximum, requestedLevel)), nowSeconds); }
    void reset() { level = 0; changedSeconds = 0; }
    int level() { return level; }
    long changedSeconds() { return changedSeconds; }
    private int set(int next, long now) { level = next; changedSeconds = now; return next; }
}
