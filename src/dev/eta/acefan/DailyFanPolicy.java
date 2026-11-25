package dev.eta.acefan;

/** Pure, opt-in battery-only temperature policy for the ordinary OEM auto target. */
final class DailyFanPolicy {
    static final long FRESH_MS = 15_000L;
    private static final long COOL_CONFIRM_MS = 30_000L;
    private static final double HYSTERESIS_C = 1.0;
    private static final double BATTERY_GUARD_C = 45.0, OTHER_SENSOR_GUARD_C = 55.0;
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
        final boolean thermalClear;
        Frame(long epoch, long version, long at, DailyFanConfig config, Fusion fusion, int demand, String state, boolean thermalClear) {
            this.epoch = epoch; this.version = version; sampledAt = at; this.config = config;
            this.fusion = fusion; this.demand = demand; this.state = state; this.thermalClear = thermalClear;
        }
        boolean hasTarget() { return "ready".equals(state) || "cooling".equals(state) || "below_threshold".equals(state); }
        boolean sameDecision(Frame other) {
            return other != null && epoch == other.epoch && version == other.version && demand == other.demand
                    && state.equals(other.state) && thermalClear == other.thermalClear
                    && fusion.contributorMask == other.fusion.contributorMask;
        }
    }

    private long epoch, coolingSince = -1L;
    private boolean connectedEdge, seenReport;
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
        boolean hadTarget = frame != null && frame.hasTarget();
        frame = null;
        coolingSince = -1L;
        epoch++;
        return hadTarget;
    }

    synchronized Frame current(long now) {
        return frame != null && fresh(frame.sampledAt, now) ? frame : null;
    }

    static boolean fresh(long since, long now) { return since >= 0L && now >= since && now - since <= FRESH_MS; }

    synchronized Frame sample(long sampledEpoch, Power sampled, DailyFanConfig config, long version,
            double[] values, long sampledAt, long now, boolean oemAllowed, int systemThermalStatus) {
        if (sampledEpoch != epoch) return null;
        if (!accepts(sampledEpoch, sampled) || !fresh(sampledAt, now)) {
            if (frame != null) invalidate();
            return null;
        }
        Fusion fusion = config == null ? new Fusion(Double.NaN, 0) : fuse(config, values);
        boolean thermalClear = thermalClear(systemThermalStatus, values);
        if (config == null || !config.enabled || !oemAllowed || !FusionTemp.valid(fusion.celsius)) {
            coolingSince = -1L;
            String state = config == null ? "config_unavailable" : !config.enabled ? "disabled"
                    : !oemAllowed ? "oem_blocked" : "temperature_unavailable";
            return frame = new Frame(epoch, version, sampledAt, config, fusion, 0, state, thermalClear);
        }
        boolean continuing = thermalClear && frame != null && frame.thermalClear && frame.demand > 0
                && frame.version == version && config.sameSettings(frame.config)
                && frame.fusion.contributorMask == fusion.contributorMask && fresh(frame.sampledAt, sampledAt);
        boolean running = continuing || fusion.celsius > config.startC;
        if (continuing && fusion.celsius <= config.startC - HYSTERESIS_C) {
            if (coolingSince < 0L) coolingSince = sampledAt;
            if (sampledAt - coolingSince >= COOL_CONFIRM_MS) running = false;
        } else coolingSince = -1L;
        if (!running) coolingSince = -1L;
        return frame = new Frame(epoch, version, sampledAt, config, fusion, running ? config.floor : 0,
                !running ? "below_threshold" : coolingSince >= 0L ? "cooling" : "ready", thermalClear);
    }

    static boolean thermalClear(int systemThermalStatus, double[] values) {
        if (systemThermalStatus != 0 || values == null || values.length != DailyFanConfig.CHANNEL_COUNT) return false;
        for (int channel = 0; channel < values.length; channel++) {
            double limit = channel == 0 ? BATTERY_GUARD_C : OTHER_SENSOR_GUARD_C;
            if (!FusionTemp.valid(values[channel]) || values[channel] >= limit) return false;
        }
        return true;
    }

    static boolean ownerAllows(String main, String uiState, String mode, String auto, String protection, String abort) {
        return ("1".equals(main) || "3".equals(main)) && !"2".equals(uiState) && "0".equals(mode)
                && "1".equals(auto) && !"1".equals(protection) && !"true".equalsIgnoreCase(abort);
    }

    static boolean allowedScene(String scene) { return "Common".equals(scene); }

    static boolean healthy(String hardware, Integer event) {
        return "NORMAL".equals(hardware) && (event == null || event < 3 || event > 7);
    }

    static boolean matchesAutoTarget(int candidate, Integer autoTarget, int reads, boolean inRefresh) {
        return inRefresh && reads == 1 && autoTarget != null && candidate >= 0 && autoTarget == candidate;
    }

    static int candidate(int oem, int maximum, int demand) {
        if (oem < 0 || maximum <= 0 || oem > maximum || demand < 0) return oem;
        return demand == 0 ? 0 : Math.max(oem, Math.min(Math.min(maximum, DailyFanConfig.MAX_LEVEL), demand));
    }
}
