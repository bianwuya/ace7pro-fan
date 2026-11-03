package dev.eta.acefan;

/** Pure sampling cadence and push-throttle policy; no Android or Xposed dependencies. */
public final class Sampling {
    /** Override window: the curve may change every few seconds, keep the fine cadence. */
    public static final long ACTIVE_MS = 5000L;
    /** Eligible to override but currently passthrough (screen on, unlocked): coarse is enough. */
    public static final long READY_MS = 10000L;
    /** Not eligible at all (unplugged, disabled, firmware mismatch): nothing to decide. */
    public static final long IDLE_MS = 30000L;
    /** Push at least this often so the chart never sees a gap while the cadence is coarse. */
    public static final long PUSH_FLOOR_MS = 20000L;
    /** Any temperature moving by this much is worth a stored sample. */
    public static final double PUSH_TEMP_DELTA = 0.2;

    private Sampling() { }

    /** Tiered cadence: 5 s while overriding, 10 s while eligible, 30 s while idle. */
    public static long interval(boolean gateAllowed, boolean hardAllowed) {
        if (gateAllowed) return ACTIVE_MS;
        if (hardAllowed) return READY_MS;
        return IDLE_MS;
    }

    /**
     * A tick is worth persisting when the first sample is pending, when the decision state
     * changed, when the floor elapsed, or when a temperature actually moved.
     */
    public static boolean shouldPush(boolean first, boolean stateChanged, long sinceLastPush, double tempDelta) {
        if (first || stateChanged) return true;
        if (sinceLastPush >= PUSH_FLOOR_MS) return true;
        return tempDelta >= PUSH_TEMP_DELTA;
    }

    /** Largest absolute delta across aligned temperature channels; NaN channels are skipped. */
    public static double maxDelta(double[] now, double[] previous) {
        if (now == null || previous == null) return 0;
        double max = 0;
        for (int i = 0; i < now.length && i < previous.length; i++) {
            if (Double.isNaN(now[i]) || Double.isNaN(previous[i])) continue;
            double delta = Math.abs(now[i] - previous[i]);
            if (delta > max) max = delta;
        }
        return max;
    }

    /**
     * Freshness budget tied to the current cadence: a snapshot may legitimately be one or two
     * cadences old, but never stale for longer than the tier allows. The 5 s tier keeps the
     * original 15 s bound.
     */
    public static long staleMs(long intervalMs) {
        return Math.max(15000L, 2L * intervalMs + 5000L);
    }
}
