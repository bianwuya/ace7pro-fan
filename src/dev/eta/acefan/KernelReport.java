package dev.eta.acefan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, key-based adapter for the actual root0400 status line. Never calculates a fan target. */
public final class KernelReport {
    private static final String[] REQUIRED = ("protocol configProtocol configRevision strategy configured live blocked fault owned ready "
            + "leaseUntil nowMs ownerEpoch target output requestedLevel physicalState effective readbackMask "
            + "tempValidMask temp0 temp1 temp2 temp3 sampleAtMs power powerEpoch sourcePresentMask sourceOnlineMask "
            + "sourceFailedMask batteryStatus batteryPresent reason").split(" ");
    private static final String[] OPTIONAL = ("fusionMc fusionValid demand telemetryFusionMc telemetryFusionValid telemetryContributorMask "
            + "sampleFreshMs samplePeriodMs sampleAgeMs sampleCompletedAtMs sampleDurationMs").split(" ");
    private final Map<String, Long> values;
    private final String statusLine;
    private KernelReport(Map<String, Long> values, String line) { this.values = Collections.unmodifiableMap(values); statusLine = line.trim(); }
    public long get(String key) {
        Long value = values.get(key);
        if (value == null) throw new IllegalArgumentException("missing status key: " + key);
        return value;
    }

    public static KernelReport parse(String line) {
        if (line == null || line.length() > 8192 || line.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid kernel status");
        String[] tokens = line.trim().split("[ \\t\\r\\n]+");
        if (tokens.length < REQUIRED.length + 1 || tokens.length > 128 || !"NONRELEASE".equals(tokens[0]))
            throw new IllegalArgumentException("not eta_policy status");
        Map<String, Long> out = new LinkedHashMap<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 1; i < tokens.length; i++) {
            int p = tokens[i].indexOf('=');
            if (p <= 0 || p == tokens[i].length() - 1) throw new IllegalArgumentException("invalid status token");
            String key = tokens[i].substring(0, p), text = tokens[i].substring(p + 1);
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !seen.add(key)) throw new IllegalArgumentException("duplicate/invalid status key");
            boolean required = false;
            for (String k : REQUIRED) if (k.equals(key)) { required = true; break; }
            boolean optional = false;
            for (String k : OPTIONAL) if (k.equals(key)) { optional = true; break; }
            if (!required && !optional) continue; // future diagnostic keys may be textual
            if (!text.matches("-?[0-9]{1,19}")) throw new IllegalArgumentException("invalid status integer");
            out.put(key, Long.parseLong(text));
        }
        for (String key : REQUIRED) if (!out.containsKey(key)) throw new IllegalArgumentException("missing " + key);
        KernelReport r = new KernelReport(out, line);
        r.bound("protocol", 2, 2); r.bound("configProtocol", 0, 2); r.bound("configRevision", 0, KernelProtocol.MAX_REVISION);
        for (String key : new String[]{"configured", "live", "blocked", "owned", "ready"}) r.bound(key, 0, 1);
        r.bound("strategy", 0, 2); r.bound("readbackMask", 0, 7); r.bound("tempValidMask", 0, 15);
        r.bound("physicalState", -1, 1); r.bound("effective", -1, 20); r.bound("requestedLevel", -1, 20);
        r.bound("target", -1, 20); r.bound("output", -1, 20); r.bound("power", 0, 3);
        r.bound("batteryStatus", -1, 4); r.bound("batteryPresent", -1, 1);
        for (String key : new String[]{"sourcePresentMask", "sourceOnlineMask", "sourceFailedMask"}) r.bound(key, 0, 7);
        for (String key : new String[]{"leaseUntil", "nowMs", "ownerEpoch", "powerEpoch", "sampleAtMs", "reason"}) r.bound(key, 0, Long.MAX_VALUE);
        for (int i = 0; i < 4; i++) r.bound("temp" + i, -100000, 250000);
        if (out.containsKey("fusionMc")) r.bound("fusionMc", -100000, 250000);
        if (out.containsKey("fusionValid")) r.bound("fusionValid", 0, 1);
        if (out.containsKey("demand")) r.bound("demand", -1, 20);
        if (out.containsKey("telemetryFusionMc")) r.bound("telemetryFusionMc", -1, 65000);
        if (out.containsKey("telemetryFusionValid")) r.bound("telemetryFusionValid", 0, 1);
        if (out.containsKey("telemetryContributorMask")) r.bound("telemetryContributorMask", 0, 15);
        // Accept the kernel's 15 s policy freshness, but never an unbounded extension.
        if (out.containsKey("sampleFreshMs")) r.bound("sampleFreshMs", 1000, 15000);
        if (out.containsKey("samplePeriodMs")) r.bound("samplePeriodMs", 1, 15000);
        if (out.containsKey("sampleCompletedAtMs")) r.bound("sampleCompletedAtMs", 0, Long.MAX_VALUE);
        for (String key : new String[]{"sampleAgeMs", "sampleDurationMs"})
            if (out.containsKey(key)) r.bound(key, -1, Long.MAX_VALUE);
        if (r.get("configProtocol") == 0 && r.get("configRevision") != 0) throw new IllegalArgumentException("unconfigured revision");
        return r;
    }
    private void bound(String key, long min, long max) {
        long v = get(key);
        if (v < min || v > max) throw new IllegalArgumentException("out of range " + key);
    }
    public boolean sampleFresh() {
        long at = get("sampleAtMs"), now = get("nowMs");
        if (at <= 0 || now < at) return false;
        long age = now - at;
        if (values.containsKey("sampleAgeMs") && get("sampleAgeMs") != age) return false;
        if (values.containsKey("sampleCompletedAtMs")) {
            long completed = get("sampleCompletedAtMs");
            if (completed < at || completed > now) return false;
            if (values.containsKey("sampleDurationMs") && get("sampleDurationMs") != completed - at) return false;
        } else if (values.containsKey("sampleDurationMs")) {
            long duration = get("sampleDurationMs");
            if (duration < 0 || duration > age) return false;
        }
        return age < (values.containsKey("sampleFreshMs") ? get("sampleFreshMs") : 5000);
    }
    public boolean ready() {
        return get("ready") == 1 && get("live") == 1 && get("blocked") == 0 && get("fault") == 0
                && get("configured") == 1 && get("owned") == 1 && get("leaseUntil") > get("nowMs") && sampleFresh();
    }

    /** Parameters/version acknowledge only the exact successfully-written revision, not a guessed current setting. */
    public Map<String, Object> fields(long wall, long elapsed, String boot, String hookVersion,
            long sentRevision, KernelProtocol.Config config, long dailyVersion, long chargeVersion, String relayState) {
        return fields(wall, elapsed, boot, hookVersion, sentRevision, config, dailyVersion, chargeVersion, relayState, null);
    }
    /** Policy fault/live are not the original driver's hardware health. Missing evidence is UNKNOWN. */
    public static String hardwareStatus(String raw) {
        if (raw == null) return "UNKNOWN";
        switch (raw.trim()) {
            case "NORMAL": case "BLOCKED": case "DAMAGED": case "RPM_LOW":
            case "NOT_PRESENT": case "PRESENT": case "OCP_ISSUE": return raw.trim();
            default: return "UNKNOWN";
        }
    }
    public Map<String, Object> fields(long wall, long elapsed, String boot, String hookVersion,
            long sentRevision, KernelProtocol.Config config, long dailyVersion, long chargeVersion, String relayState,
            String driverHardwareStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wall", wall); out.put("elapsed", elapsed); out.put("boot", boot);
        out.put("hook", KernelProtocol.LABEL); out.put("hookVersion", hookVersion); out.put("backend", KernelProtocol.BACKEND);
        out.put("batterySource", "thermal:battery"); out.put("quality", "kernel_thermal_channel_sample");
        boolean fresh = sampleFresh();
        String[] names = {"battery", "board", "fan", "mos"};
        for (int i = 0; i < 4; i++) {
            boolean valid = fresh && (get("tempValidMask") & (1 << i)) != 0 && get("temp" + i) >= 0 && get("temp" + i) <= 65000;
            out.put(names[i], valid ? get("temp" + i) / 1000.0 : null);
            if (i == 0) out.put("batteryValid", valid);
        }
        // Never invent a connector type from ONLINE: masks describe source presence, not a USB/AC plug enum.
        int power = (int)get("power");
        out.put("plugged", power == 1 ? 0 : power == 2 || power == 3 ? wiredPlug() : -1);
        out.put("wired", power == 2 && (get("sourceOnlineMask") & 3) != 0 && (get("sourceOnlineMask") & 4) == 0);
        out.put("charging", power == 2); out.put("batteryDischarging", power == 1);
        out.put("state", nullable("physicalState")); out.put("driver", nullable("requestedLevel"));
        // state=0 means stopped even though level/level_eff may retain a previous positive request.
        out.put("effective", (get("readbackMask") & 6) == 6 ? get("physicalState") == 0 ? 0L : nullable("effective") : null);
        out.put("fanStatus", hardwareStatus(driverHardwareStatus));
        boolean acknowledged = config != null && sentRevision == get("configRevision") && get("configProtocol") == 2;
        out.put("configVersion", acknowledged ? chargeVersion : -1L);
        boolean daily = power == 1, charge = power == 2 && (get("sourceOnlineMask") & 4) == 0;
        out.put("controlMode", daily ? "daily" : charge ? "charge" : "oem");
        out.put("active", charge && ready());
        out.put("phase", charge && ready() ? "kernel_control" : "oem");
        // Read-only fusion is separate from control fusion/ready. If the new
        // extension is present but invalid, never fall back to old control evidence.
        boolean telemetryFusion = values.containsKey("telemetryFusionValid") || values.containsKey("telemetryFusionMc");
        String fusionValidKey = telemetryFusion ? "telemetryFusionValid" : "fusionValid";
        String fusionMcKey = telemetryFusion ? "telemetryFusionMc" : "fusionMc";
        boolean contributorsValid = !telemetryFusion || !values.containsKey("telemetryContributorMask")
                || (get("telemetryContributorMask") != 0 && (get("telemetryContributorMask") & ~get("tempValidMask")) == 0);
        Double fusion = fresh && contributorsValid && Long.valueOf(1).equals(values.get(fusionValidKey)) && values.containsKey(fusionMcKey)
                && get(fusionMcKey) >= 0 && get(fusionMcKey) <= 65000 ? get(fusionMcKey) / 1000.0 : null;
        out.put("fused", charge ? fusion : null);
        if (daily) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("version", acknowledged ? dailyVersion : -1L);
            day.put("startTemp", acknowledged ? config.daily.startMc / 1000.0 : null);
            day.put("fusedTemp", fusion);
            day.put("demand", ready() && values.containsKey("demand") ? nullable("demand") : null);
            int mask = 0;
            if (telemetryFusion && values.containsKey("telemetryContributorMask")) {
                if (fusion != null) mask = (int)get("telemetryContributorMask");
            } else if (acknowledged && fresh) for (int i = 0; i < 4; i++)
                if (config.daily.weight(i) > 0 && (get("tempValidMask") & (1 << i)) != 0) mask |= 1 << i;
            day.put("contributorMask", mask);
            day.put("state", !acknowledged ? "config_unavailable" : !config.daily.enabled ? "disabled"
                    : ready() ? "kernel_control" : "kernel_blocked");
            out.put("daily", day);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("backend", KernelProtocol.BACKEND); node.put("protocol", 2);
        node.put("available", true); node.put("controlling", ready()); node.put("ready", ready());
        node.put("statusLine", statusLine); // lets Provider verify schema before advertising the backend to legacy Hook
        node.put("target", ready() ? nullable("target") : null); node.put("output", nullable("output"));
        for (Map.Entry<String, Long> e : values.entrySet()) if (!node.containsKey(e.getKey())) node.put(e.getKey(), e.getValue());
        node.put("relay", relayState); node.put("thermalChannels", String.join(",", KernelProtocol.ZONES));
        out.put("nodeControl", node);
        return out;
    }
    private int wiredPlug() {
        long sources = get("sourceOnlineMask");
        if ((sources & 4) != 0) return 4;
        return (sources & 1) != 0 ? 2 : (sources & 2) != 0 ? 1 : -1;
    }
    private Long nullable(String key) { long n = get(key); return n < 0 ? null : n; }
}
