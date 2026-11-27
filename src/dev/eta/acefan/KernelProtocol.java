package dev.eta.acefan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;

/** Pure v2 wire contract. No temperature policy, Android services, or hardware I/O. */
public final class KernelProtocol {
    public static final long MAX_REVISION = 0xffff_ffffL;
    public static final int LEASE_MS = 5000, WRITE_INTERVAL_MS = 1500;
    public static final String BACKEND = "kernel_eta_policy_v2", LABEL = "内核控制";
    public static final long PUBLISHER_LEASE_MS = 12_000L;
    public static final String[] ZONES = {"battery", "board_temp", "fan_temp", "svooc_mos_btb_usr"};
    private KernelProtocol() { }

    public static long nextRevision(long disk, long kernel) {
        range(disk, 0, MAX_REVISION, "disk revision"); range(kernel, 0, MAX_REVISION, "kernel revision");
        long n = Math.max(disk, kernel);
        if (n == MAX_REVISION) throw new IllegalStateException("kernel configuration revision exhausted");
        return n + 1;
    }

    /** Never treats null, a malformed value, or an unfamiliar enum as permission. */
    public static int mainEnabled(String value) {
        if ("1".equals(value) || "3".equals(value)) return 2;
        if ("0".equals(value) || "2".equals(value)) return 1;
        return 0;
    }
    public static int yesNo(String value) {
        return "1".equals(value) ? 2 : "0".equals(value) ? 1 : 0;
    }
    public static int mode(String value) {
        return "0".equals(value) ? 1 : "1".equals(value) ? 2 : "2".equals(value) ? 3 : 0;
    }
    public static boolean uiClear(String value) { return "0".equals(value) || "1".equals(value); }
    public static boolean zero(String value) { return "0".equals(value); }
    public static boolean falseText(String value) { return "false".equals(value); }
    /** OEM scenes/d$a reads a tagged string and tests startsWith("true"). */
    public static boolean performanceClear(String value) { return value != null && !value.startsWith("true"); }
    /** Missing-only OEM semantics. Caller must NOT call this after an IPC/security error.
     * Evidence paths are relative to /workspace/ace7oem/smali/smali_classes2/com/oplus/exsystemservice/.
     * Required main/mode/auto remain unknown even though some OEM getters have permissive defaults.
     */
    public static String oemMissingDefault(String namespace, String key) {
        if ("system".equals(namespace)) {
            switch (key) {
                case "cooling_fan_main_switch": return "0"; // b.f(): only literal 2 means UI inhibit
                case "regular_charge_protection_status":
                case "cooling_fan_charge_switch": case "cooling_fan_mic_mutual":
                case "rider_mode_main_switch": case "super_powersave_mode_state": return "0";
                case "sys_oplus_high_performance_spec": return "false";
                default: return null;
            }
        }
        if ("global".equals(namespace)) {
            switch (key) {
                case "cooling_fan_service_abort": return "false";
                case "op_audio_mic_status": case "gamemode_delayed": return "0";
                default: return null;
            }
        }
        return null;
    }
    public static String oemMissingEvidence(String namespace, String key) {
        if (oemMissingDefault(namespace, key) == null) return "required_or_unverified:no_missing_default";
        switch (key) {
            case "cooling_fan_main_switch": return "fan/switches/b.smali:f()Z:33-48 (!literal2)";
            case "regular_charge_protection_status": return "fan/scenes/i.smali:o()Z:67-94 (getIntForUser default0)";
            case "cooling_fan_charge_switch": return "fan/switches/FanSwitch.smali:<clinit>:52,138-140 + fan/switches/a.smali:c()Z:87-113 (constructor default0)";
            case "cooling_fan_mic_mutual": return "fan/switches/FanSwitch.smali:<clinit>:52,174-176 + fan/switches/a.smali:c()Z:87-113 (constructor default0)";
            case "rider_mode_main_switch": return "fan/scenes/h$a.smali:onChange(Z)V:34-65 (literal1.equals(null)=false)";
            case "super_powersave_mode_state": return "fan/scenes/j$a.smali:onChange(Z)V:34-67 (literal1.equals(null)=false)";
            case "sys_oplus_high_performance_spec": return "fan/scenes/d$a.smali:onChange(Z)V:34-77 (null or !startsWith(true) inactive)";
            case "cooling_fan_service_abort": return "fan/core/Aeolus$a.smali:onChange(Z)V:38-59 (literaltrue.equals(null)=false)";
            case "op_audio_mic_status": return "fan/core/NoiseSuppressor.smali:d()Z:217-237 (Settings.Global.getInt default0)";
            case "gamemode_delayed": return "fan/scenes/c$a.smali:onChange(Z)V:34-64 (Settings.Global.getInt default0)";
            default: throw new IllegalArgumentException("unverified OEM missing default");
        }
    }
    public static String oemSetting(String namespace, String key, String raw) {
        return raw == null ? oemMissingDefault(namespace, key) : raw;
    }

    public static boolean publisherFresh(long last, long now) {
        return last > 0 && now >= last && now - last < PUBLISHER_LEASE_MS;
    }
    public static boolean rootPublisher(int uid) { return uid == 0; }
    public static boolean fresh(long at, long now, long limit) {
        return at > 0 && now >= at && now - at < limit;
    }
    public static boolean writerFresh(long safetyAt, long eventAt, long statusAt, long publisherAt, long now) {
        return fresh(safetyAt, now, 3500) && fresh(eventAt, now, 1500)
                && fresh(statusAt, now, 7000) && fresh(publisherAt, now, 12000);
    }

    /** Absolute parameters, not a reconstruction of any user's defaults. Channel order is Provider order. */
    public static final class Settings {
        public final boolean enabled;
        public final int startMc, floor, gainMilli, ceiling;
        private final int[] weights;
        public Settings(boolean enabled, double startC, int floor, double[] weights, double gain, int ceiling) {
            this.enabled = enabled;
            this.startMc = scaled(startC, 1000, 20000, 50000, "start temperature");
            this.floor = (int)range(floor, 1, 20, "floor");
            this.gainMilli = scaled(gain, 1000, 1, 8000, "charge gain");
            this.ceiling = (int)range(ceiling, floor, 20, "ceiling");
            if (weights == null || weights.length != 4) throw new IllegalArgumentException("four weights required");
            this.weights = new int[4];
            long sum = 0;
            for (int i = 0; i < 4; ++i) {
                this.weights[i] = scaled(weights[i], 10000, 0, 100000000, "weight");
                sum += this.weights[i];
            }
            if (sum == 0) throw new IllegalArgumentException("zero weights");
        }
        public int weight(int index) { return weights[index]; }
        public Settings withEnabled(boolean on) {
            double[] w = new double[4];
            for (int i = 0; i < 4; i++) w[i] = weights[i] / 10000.0;
            return new Settings(on, startMc / 1000.0, floor, w, gainMilli / 1000.0, ceiling);
        }
        private void append(StringBuilder b, boolean charge) {
            b.append(enabled ? 2 : 1).append(' ').append(startMc).append(' ').append(floor);
            if (charge) b.append(' ').append(gainMilli).append(' ').append(ceiling);
            for (int w : weights) b.append(' ').append(w);
        }
    }

    public static final class Gates {
        public final int main, mode, auto, mask, thermalValid, thermalStatus;
        public Gates(int main, int mode, int auto, int mask, int thermalStatus) {
            this.main = (int)range(main, 0, 2, "main");
            this.mode = (int)range(mode, 0, 3, "mode");
            this.auto = (int)range(auto, 0, 2, "auto");
            this.mask = (int)range(mask, 0, 127, "gate mask");
            this.thermalValid = thermalStatus >= 0 && thermalStatus <= 6 ? 1 : 0;
            this.thermalStatus = thermalValid == 1 ? thermalStatus : 0;
        }
        public boolean clear() { return main == 2 && mode == 1 && auto == 2 && mask == 127 && thermalValid == 1 && thermalStatus == 0; }
    }

    public static final class Config {
        public final Settings daily, charge;
        public final Gates gates;
        private final String[] zones;
        public Config(Settings daily, Settings charge, Gates gates, String[] zones) {
            if (daily == null || charge == null || gates == null || zones == null || zones.length != 4)
                throw new IllegalArgumentException("complete configuration required");
            this.daily = daily; this.charge = charge; this.gates = gates; this.zones = zones.clone();
            validateZones(this.zones);
        }
        public boolean allowed() { return (daily.enabled || charge.enabled) && gates.clear(); }
        public String line(long revision, boolean enabled) {
            range(revision, 1, MAX_REVISION, "revision");
            if (enabled && !allowed()) throw new IllegalArgumentException("cannot enable unknown/blocked gates");
            StringBuilder b = new StringBuilder("2 ").append(revision).append(' ').append(enabled ? 1 : 0)
                    .append(' ').append(enabled ? LEASE_MS : 0).append(' ').append(gates.main)
                    .append(' ').append(gates.mode).append(' ').append(gates.auto).append(' ').append(gates.mask)
                    .append(' ').append(gates.thermalValid).append(' ').append(gates.thermalStatus).append(' ');
            daily.append(b, false); b.append(' '); charge.append(b, true);
            b.append(" 2"); // Explicit full-owner, never inferred from a version/default.
            for (String zone : zones) b.append(' ').append(zone);
            return b.append('\n').toString();
        }
    }

    /** Persist-before-send is owned by the writer; repeated renewals return byte-identical lines. */
    public static final class Lease {
        private long revision;
        private String semantic, line;
        public Lease(long diskRevision, long kernelRevision) {
            nextRevision(diskRevision, kernelRevision); // fail closed before the first write on exhaustion
            revision = Math.max(diskRevision, kernelRevision);
        }
        public interface Persist { void save(long revision) throws Exception; }
        public String prepare(Config config, boolean enable, Persist store) throws Exception {
            String candidate = config.line(1, enable);
            if (!candidate.equals(semantic)) {
                long next = nextRevision(revision, 0);
                store.save(next); // an error cannot create a reusable, unpersisted revision
                revision = next; semantic = candidate; line = config.line(revision, enable);
            }
            return line;
        }
        public long revision() { return revision; }
    }

    /** Validate snapshots, including older v1, without silently accepting unknown/extra fields. */
    public static long snapshotRevision(String input) {
        if ("UNCONFIGURED protocol=2 strategy=0 enabled=0".equals(input == null ? null : input.trim())) return 0;
        if (input == null || input.length() > 1024) throw new IllegalArgumentException("invalid snapshot length");
        String[] t = input.trim().split("[ \\t\\r\\n]+");
        int version = t.length == 0 ? 0 : (int)uint(t[0]);
        int n = version == 2 ? 27 : version == 1 ? 26 : -1;
        if (n < 0 || t.length != n + 4) throw new IllegalArgumentException("unknown snapshot schema");
        long[] v = new long[n];
        for (int i = 0; i < n; ++i) v[i] = uint(t[i]);
        range(v[1], 1, MAX_REVISION, "revision"); range(v[2], 0, 1, "enabled");
        range(v[3], v[2] == 1 ? 1 : 0, 5000, "lease"); range(v[4], 0, 2, "main");
        range(v[5], 0, 3, "mode"); range(v[6], 0, 2, "auto"); range(v[7], 0, 127, "mask");
        range(v[8], 0, 1, "thermal valid"); range(v[9], 0, 6, "thermal status");
        checkSettings(v, 10, false); checkSettings(v, 17, true);
        if (version == 2) range(v[26], 0, 2, "strategy");
        validateZones(Arrays.copyOfRange(t, n, n + 4));
        return v[1];
    }
    private static void checkSettings(long[] v, int i, boolean charge) {
        range(v[i], 1, 2, "settings enabled"); range(v[i + 1], 20000, 50000, "start");
        range(v[i + 2], 1, 20, "floor");
        int w = i + 3;
        if (charge) { range(v[w], 1, 8000, "gain"); range(v[w + 1], v[i + 2], 20, "ceiling"); w += 2; }
        long sum = 0;
        for (int j = 0; j < 4; ++j) sum += range(v[w + j], 0, 100000000, "weight");
        if (sum == 0) throw new IllegalArgumentException("zero weights");
    }
    /** OEM scenes/g$a compares values[0] with zero, not with one. Invalid samples stay unknown. */
    static int pocketGate(float[] values) {
        if (values == null || values.length == 0 || Float.isNaN(values[0]) || Float.isInfinite(values[0])) return -1;
        return values[0] == 0f ? 0 : 1;
    }
    static long uint(String text) {
        if (text == null || !text.matches("[0-9]{1,10}")) throw new IllegalArgumentException("unsigned integer required");
        return range(Long.parseLong(text), 0, MAX_REVISION, "uint32");
    }
    private static void validateZones(String[] zones) {
        for (int i = 0; i < zones.length; i++) {
            String s = zones[i];
            if (s == null || !s.matches("[A-Za-z0-9_-]{1,63}")) throw new IllegalArgumentException("invalid thermal channel");
            String lower = s.toLowerCase(Locale.ROOT);
            for (String deny : new String[]{"shell", "skin", "virtual", "fake", "horae"})
                if (lower.contains(deny)) throw new IllegalArgumentException("rejected thermal channel");
            for (int j = 0; j < i; j++) if (s.equals(zones[j])) throw new IllegalArgumentException("duplicate thermal channel");
        }
    }
    private static int scaled(double value, int scale, int lo, int hi, String key) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < (double)lo / scale || value > (double)hi / scale)
            throw new IllegalArgumentException("invalid " + key);
        long rounded = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(scale)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return (int)range(rounded, lo, hi, key);
    }
    private static long range(long value, long lo, long hi, String key) {
        if (value < lo || value > hi) throw new IllegalArgumentException("invalid " + key);
        return value;
    }
}
