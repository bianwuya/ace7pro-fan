package dev.eta.acefan;

/** Pure, opt-in battery-only floor policy. Never asks OEM to turn a stopped fan on. */
final class DailyFanPolicy {
    static final long FRESH_MS = 15_000L, COOL_MS = 30_000L;
    static final double HYSTERESIS_C = 1.0;
    enum Power { UNKNOWN, EXTERNAL, BATTERY }

    static Power power(int plugged, boolean explicitlyDischarging) {
        return plugged > 0 ? Power.EXTERNAL : plugged == 0 && explicitlyDischarging ? Power.BATTERY : Power.UNKNOWN;
    }

    static final class Fusion {
        final double celsius;
        final int contributorMask;
        Fusion(double celsius, int contributorMask) { this.celsius = celsius; this.contributorMask = contributorMask; }
    }

    static Fusion fuse(DailyFanConfig config, double[] values) {
        int mask = 0;
        if (values == null || values.length != DailyFanConfig.CHANNEL_COUNT) return new Fusion(Double.NaN, mask);
        for (int i = 0; i < values.length; i++) if (config.weight(i) > 0 && FusionTemp.valid(values[i])) mask |= 1 << i;
        if (mask == 0) return new Fusion(Double.NaN, mask);
        double sum = 0.0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        double[] proportions = config.proportions(mask);
        for (int i = 0; i < values.length; i++) if ((mask & (1 << i)) != 0) {
            sum += proportions[i] * values[i];
            min = Math.min(min, values[i]); max = Math.max(max, values[i]);
        }
        // The exact weighted mean is bounded by its inputs, even at the 0/65 °C edges.
        return new Fusion(Math.max(min, Math.min(max, sum)), mask);
    }

    static final class Frame {
        final long epoch, version, sampledAt;
        final DailyFanConfig config;
        final Fusion fusion;
        final int demand;
        final String state;
        Frame(long epoch, long version, long at, DailyFanConfig config, Fusion fusion, int demand, String state) {
            this.epoch = epoch; this.version = version; sampledAt = at; this.config = config;
            this.fusion = fusion; this.demand = demand; this.state = state;
        }
    }

    private long epoch, coolingAt = -1L;
    private boolean connectedEdge, seenReport, latched;
    private Power reported = Power.UNKNOWN;
    private Frame frame;

    synchronized long epoch() { return epoch; }

    // On a plug transition never trust a pre-transition telemetry/config read.
    synchronized boolean powerEdge(boolean connected) {
        connectedEdge = connected;
        seenReport = false;
        return invalidate();
    }

    synchronized boolean powerReport(Power value) {
        if (seenReport && reported == value) return false;
        seenReport = true;
        reported = value;
        return invalidate();
    }

    synchronized boolean accepts(long sampledEpoch, Power sampled) {
        return sampledEpoch == epoch && !connectedEdge && sampled == Power.BATTERY && (!seenReport || reported == Power.BATTERY);
    }

    synchronized boolean invalidate() {
        boolean hadDemand = frame != null && frame.demand > 0;
        frame = null;
        epoch++;
        resetLatch();
        return hadDemand;
    }

    synchronized Frame current(long now) {
        return frame != null && fresh(frame.sampledAt, now) ? frame : null;
    }

    static boolean fresh(long since, long now) { return since >= 0L && now >= since && now - since <= FRESH_MS; }

    synchronized Frame sample(long sampledEpoch, Power sampled, DailyFanConfig config, long version,
            double[] values, long sampledAt, long now, boolean oemAllowed) {
        if (sampledEpoch != epoch) return null;
        if (!accepts(sampledEpoch, sampled) || !fresh(sampledAt, now)) {
            if (frame != null) invalidate();
            return null;
        }
        boolean same = frame != null && frame.version == version && fresh(frame.sampledAt, sampledAt);
        if (!same) resetLatch();
        Fusion fusion = config == null ? new Fusion(Double.NaN, 0) : fuse(config, values);
        if (same && frame.fusion.contributorMask != fusion.contributorMask) resetLatch();
        if (config == null || !config.enabled || !oemAllowed || !FusionTemp.valid(fusion.celsius)) {
            resetLatch();
            String state = config == null ? "config_unavailable" : !config.enabled ? "disabled"
                    : !oemAllowed ? "oem_blocked" : "temperature_unavailable";
            return frame = new Frame(epoch, version, sampledAt, config, fusion, 0, state);
        }
        if (!latched) {
            latched = fusion.celsius >= config.startC;
        } else if (fusion.celsius < config.startC - HYSTERESIS_C) {
            if (coolingAt < 0L) coolingAt = sampledAt;
            if (sampledAt - coolingAt >= COOL_MS) resetLatch();
        } else coolingAt = -1L;
        return frame = new Frame(epoch, version, sampledAt, config, fusion, latched ? config.floor : 0,
                !latched ? "below_threshold" : coolingAt >= 0L ? "cooling" : "ready");
    }

    private void resetLatch() { latched = false; coolingAt = -1L; }

    static boolean ownerAllows(String main, String uiState, String mode, String auto, String protection, String abort) {
        return ("1".equals(main) || "3".equals(main)) && !"2".equals(uiState) && "0".equals(mode)
                && "1".equals(auto) && !"1".equals(protection) && !"true".equalsIgnoreCase(abort);
    }

    static boolean allowedScene(String scene) { return "Common".equals(scene); }

    static boolean healthy(String hardware, Integer event) {
        return "NORMAL".equals(hardware) && (event == null || event < 3 || event > 7);
    }

    static int candidate(int oem, int maximum, int demand) {
        return oem > 0 && maximum > 0 && demand > 0
                ? Math.max(oem, Math.min(Math.min(maximum, DailyFanConfig.MAX_LEVEL), demand)) : oem;
    }
}
